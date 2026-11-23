"""Reconciliation of the three-service saga (ADR-0006, ADR-0008). For every transfer it compares:
  * what the Transaction Service believes  (transaction_db.transfers: PENDING | COMPLETED | FAILED);
  * what the Wallet Service did with money (wallet_db.transfer_holds: HELD | CAPTURED | RELEASED | REJECTED);
  * what the Ledger Service decided         (ledger_db.ledger_transfers: APPROVED | REJECTED).

It runs outside the system under test, reading the databases directly, so the measurement does not depend on
the services behaving correctly.

Usage:
    python3 -I experiments/reconcile.py                       # print a report for the running compose stack
    python3 -I experiments/reconcile.py --json-out FILE --anomalies-out FILE
"""
import argparse
import csv
import json
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass

LIMIT_EXCEEDED = "LIMIT_EXCEEDED"

# --- correct end states
# COMPLETED, money captured to the target, posted in the ledger.
CONSISTENT_COMPLETED = "consistent_completed"
# FAILED by the ledger rule, money returned to the source (compensation done), ledger recorded the rejection.
CONSISTENT_COMPENSATED = "consistent_compensated"
# FAILED by the Wallet Service before anything was debited (insufficient funds, unknown wallet...).
CONSISTENT_REJECTED = "consistent_rejected"

# --- the saga stopped half-way (transfer still PENDING after the drain)
# Wallet never saw it: the first message was lost. Money did not move.
LOST_REQUEST = "lost_request"
# Money left the source wallet and is held, but was neither captured nor released: frozen funds.
FUNDS_HELD = "funds_held"
# Money reached the target, but the Transaction Service still says PENDING.
LOST_RESULT_MONEY_MOVED = "lost_result_money_moved"
# Money was returned to the source, but the Transaction Service still says PENDING.
LOST_RESULT_COMPENSATED = "lost_result_compensated"
# Wallet rejected it, but the Transaction Service still says PENDING. No money moved.
LOST_RESULT_REJECTED = "lost_result_rejected"

# --- must never happen
# The Wallet Service did the opposite of the Ledger decision (captured a rejected or released an approved transfer).
COMPENSATION_VIOLATION = "compensation_violation"
# The services finished the transfer but disagree on how.
OUTCOME_MISMATCH = "outcome_mismatch"
# The Transaction Service finished a transfer the Wallet Service has no record of.
FINISHED_WITHOUT_JOURNAL = "finished_without_journal"
# The Wallet or Ledger Service has a transfer the Transaction Service does not know.
ORPHAN_JOURNAL = "orphan_journal"

CONSISTENT = (CONSISTENT_COMPLETED, CONSISTENT_COMPENSATED, CONSISTENT_REJECTED)
CATEGORIES = [CONSISTENT_COMPLETED, CONSISTENT_COMPENSATED, CONSISTENT_REJECTED, LOST_REQUEST, FUNDS_HELD,
              LOST_RESULT_MONEY_MOVED, LOST_RESULT_COMPENSATED, LOST_RESULT_REJECTED, COMPENSATION_VIOLATION,
              OUTCOME_MISMATCH, FINISHED_WITHOUT_JOURNAL, ORPHAN_JOURNAL]


@dataclass(frozen=True)
class Transfer:
    status: str          # PENDING | COMPLETED | FAILED
    failure_reason: str  # "" when not failed


@dataclass(frozen=True)
class Hold:
    status: str          # HELD | CAPTURED | RELEASED | REJECTED
    failure_reason: str


@dataclass(frozen=True)
class LedgerRecord:
    decision: str        # APPROVED | REJECTED


def classify(transfer, hold, ledger):
    if hold and ledger and ((hold.status == "CAPTURED" and ledger.decision == "REJECTED")
                            or (hold.status == "RELEASED" and ledger.decision == "APPROVED")):
        return COMPENSATION_VIOLATION
    if transfer is None:
        return ORPHAN_JOURNAL
    if transfer.status == "PENDING":
        if hold is None:
            return LOST_REQUEST
        return {"HELD": FUNDS_HELD, "CAPTURED": LOST_RESULT_MONEY_MOVED, "RELEASED": LOST_RESULT_COMPENSATED,
                "REJECTED": LOST_RESULT_REJECTED}[hold.status]
    if hold is None:
        return FINISHED_WITHOUT_JOURNAL
    decision = ledger.decision if ledger else None
    if transfer.status == "COMPLETED":
        return CONSISTENT_COMPLETED if hold.status == "CAPTURED" and decision == "APPROVED" else OUTCOME_MISMATCH
    if transfer.failure_reason == LIMIT_EXCEEDED:
        return CONSISTENT_COMPENSATED if hold.status == "RELEASED" and decision == "REJECTED" else OUTCOME_MISMATCH
    if hold.status == "REJECTED" and hold.failure_reason == transfer.failure_reason and decision is None:
        return CONSISTENT_REJECTED
    return OUTCOME_MISMATCH


def reconcile(transfers, holds, ledger):
    """dicts transfer_id -> Transfer / Hold / LedgerRecord. Returns (summary, anomalies)."""
    counts = Counter()
    anomalies = []
    for transfer_id in sorted(transfers.keys() | holds.keys() | ledger.keys()):
        transfer, hold, record = transfers.get(transfer_id), holds.get(transfer_id), ledger.get(transfer_id)
        category = classify(transfer, hold, record)
        counts[category] += 1
        if category not in CONSISTENT:
            anomalies.append({
                "transfer_id": transfer_id, "category": category,
                "transaction_status": transfer.status if transfer else "",
                "wallet_hold": hold.status if hold else "",
                "ledger_decision": record.decision if record else "",
            })
    total = sum(counts.values())
    consistent = sum(counts[c] for c in CONSISTENT)
    summary = {category: counts[category] for category in CATEGORIES}
    summary["total"] = total
    summary["inconsistent"] = total - consistent
    summary["consistency_rate"] = round(consistent / total, 6) if total else None
    return summary, anomalies


def query(database, sql):
    output = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "payflow", "-d", database,
         "-tA", "-F", "\t", "-c", sql],
        check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in output.splitlines() if line]


def load():
    transfers = {row[0]: Transfer(row[1], row[2]) for row in query(
        "transaction_db", "SELECT id, status, coalesce(failure_reason, '') FROM transfers")}
    holds = {row[0]: Hold(row[1], row[2]) for row in query(
        "wallet_db", "SELECT transfer_id, status, coalesce(failure_reason, '') FROM transfer_holds")}
    ledger = {row[0]: LedgerRecord(row[1]) for row in query(
        "ledger_db", "SELECT transfer_id, decision FROM ledger_transfers")}
    return transfers, holds, ledger


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json-out")
    parser.add_argument("--anomalies-out")
    args = parser.parse_args()

    summary, anomalies = reconcile(*load())
    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump(summary, f, indent=2)
    if args.anomalies_out:
        with open(args.anomalies_out, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["transfer_id", "category", "transaction_status", "wallet_hold",
                                                   "ledger_decision"])
            writer.writeheader()
            writer.writerows(anomalies)
    for key, value in summary.items():
        print(f"  {key:26} {value}")
    return 0 if summary["inconsistent"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
