"""Reconciliation: compares what the Transaction Service believes (transaction_db.transfers) with what the Wallet
Service actually did (wallet_db.applied_transfers) and classifies every transfer.

It runs outside the system under test, reading both databases directly, so the measurement does not depend on
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

# Categories. Only the first two are correct end states.
CONSISTENT_COMPLETED = "consistent_completed"
CONSISTENT_FAILED = "consistent_failed"
# Transaction says PENDING, Wallet never saw it: the request event was lost. Money did not move.
LOST_REQUEST = "lost_request"
# Transaction says PENDING, Wallet applied it: the result event was lost. Money moved, status is wrong.
LOST_RESULT_MONEY_MOVED = "lost_result_money_moved"
# Transaction says PENDING, Wallet rejected it (e.g. insufficient funds): result lost, no money moved.
LOST_RESULT_REJECTED = "lost_result_rejected"
# Both sides finished but disagree on the outcome. Must never happen.
OUTCOME_MISMATCH = "outcome_mismatch"
# Transaction finished a transfer the Wallet has no record of. Must never happen.
FINISHED_WITHOUT_JOURNAL = "finished_without_journal"
# Wallet applied a transfer the Transaction Service does not know. Must never happen.
ORPHAN_JOURNAL = "orphan_journal"

CATEGORIES = [CONSISTENT_COMPLETED, CONSISTENT_FAILED, LOST_REQUEST, LOST_RESULT_MONEY_MOVED,
              LOST_RESULT_REJECTED, OUTCOME_MISMATCH, FINISHED_WITHOUT_JOURNAL, ORPHAN_JOURNAL]


@dataclass(frozen=True)
class Transfer:
    status: str          # PENDING | COMPLETED | FAILED
    failure_reason: str  # "" when not failed


@dataclass(frozen=True)
class JournalEntry:
    outcome: str         # COMPLETED | FAILED
    failure_reason: str


def classify(transfer, entry):
    if transfer is None:
        return ORPHAN_JOURNAL
    if transfer.status == "PENDING":
        if entry is None:
            return LOST_REQUEST
        return LOST_RESULT_MONEY_MOVED if entry.outcome == "COMPLETED" else LOST_RESULT_REJECTED
    if entry is None:
        return FINISHED_WITHOUT_JOURNAL
    if transfer.status == entry.outcome and transfer.failure_reason == entry.failure_reason:
        return CONSISTENT_COMPLETED if entry.outcome == "COMPLETED" else CONSISTENT_FAILED
    return OUTCOME_MISMATCH


def reconcile(transfers, journal):
    """transfers, journal: dict transfer_id -> Transfer / JournalEntry. Returns (summary, anomalies)."""
    counts = Counter()
    anomalies = []
    for transfer_id in sorted(transfers.keys() | journal.keys()):
        transfer, entry = transfers.get(transfer_id), journal.get(transfer_id)
        category = classify(transfer, entry)
        counts[category] += 1
        if category not in (CONSISTENT_COMPLETED, CONSISTENT_FAILED):
            anomalies.append({
                "transfer_id": transfer_id, "category": category,
                "transaction_status": transfer.status if transfer else "",
                "wallet_outcome": entry.outcome if entry else "",
            })
    total = sum(counts.values())
    consistent = counts[CONSISTENT_COMPLETED] + counts[CONSISTENT_FAILED]
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
    journal = {row[0]: JournalEntry(row[1], row[2]) for row in query(
        "wallet_db", "SELECT transfer_id, outcome, coalesce(failure_reason, '') FROM applied_transfers")}
    return transfers, journal


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
            writer = csv.DictWriter(f, fieldnames=["transfer_id", "category", "transaction_status", "wallet_outcome"])
            writer.writeheader()
            writer.writerows(anomalies)
    for key, value in summary.items():
        print(f"  {key:26} {value}")
    return 0 if summary["inconsistent"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
