"""Run: python3 -m unittest discover -s experiments"""
import unittest

from reconcile import (COMPENSATION_VIOLATION, CONSISTENT_COMPENSATED, CONSISTENT_COMPLETED, CONSISTENT_REJECTED,
                       FINISHED_WITHOUT_JOURNAL, FUNDS_HELD, Hold, LOST_REQUEST, LOST_RESULT_COMPENSATED,
                       LOST_RESULT_MONEY_MOVED, LOST_RESULT_REJECTED, LedgerRecord, ORPHAN_JOURNAL, OUTCOME_MISMATCH,
                       Transfer, classify, reconcile)

COMPLETED = Transfer("COMPLETED", "")
COMPENSATED = Transfer("FAILED", "LIMIT_EXCEEDED")
NO_FUNDS = Transfer("FAILED", "INSUFFICIENT_FUNDS")
PENDING = Transfer("PENDING", "")
HELD = Hold("HELD", "")
CAPTURED = Hold("CAPTURED", "")
RELEASED = Hold("RELEASED", "LIMIT_EXCEEDED")
REJECTED_NO_FUNDS = Hold("REJECTED", "INSUFFICIENT_FUNDS")
APPROVED = LedgerRecord("APPROVED")
LEDGER_REJECTED = LedgerRecord("REJECTED")


class ClassifyTest(unittest.TestCase):

    def test_every_combination(self):
        cases = [
            (COMPLETED, CAPTURED, APPROVED, CONSISTENT_COMPLETED),
            (COMPENSATED, RELEASED, LEDGER_REJECTED, CONSISTENT_COMPENSATED),
            (NO_FUNDS, REJECTED_NO_FUNDS, None, CONSISTENT_REJECTED),
            (PENDING, None, None, LOST_REQUEST),
            (PENDING, HELD, None, FUNDS_HELD),
            (PENDING, HELD, APPROVED, FUNDS_HELD),
            (PENDING, HELD, LEDGER_REJECTED, FUNDS_HELD),
            (PENDING, CAPTURED, APPROVED, LOST_RESULT_MONEY_MOVED),
            (PENDING, RELEASED, LEDGER_REJECTED, LOST_RESULT_COMPENSATED),
            (PENDING, REJECTED_NO_FUNDS, None, LOST_RESULT_REJECTED),
            (COMPLETED, CAPTURED, LEDGER_REJECTED, COMPENSATION_VIOLATION),
            (COMPENSATED, RELEASED, APPROVED, COMPENSATION_VIOLATION),
            (PENDING, CAPTURED, LEDGER_REJECTED, COMPENSATION_VIOLATION),
            (COMPLETED, CAPTURED, None, OUTCOME_MISMATCH),
            (COMPLETED, RELEASED, LEDGER_REJECTED, OUTCOME_MISMATCH),
            (COMPENSATED, CAPTURED, APPROVED, OUTCOME_MISMATCH),
            (Transfer("FAILED", "WALLET_NOT_FOUND"), REJECTED_NO_FUNDS, None, OUTCOME_MISMATCH),
            (NO_FUNDS, REJECTED_NO_FUNDS, LEDGER_REJECTED, OUTCOME_MISMATCH),
            (COMPLETED, None, APPROVED, FINISHED_WITHOUT_JOURNAL),
            (None, CAPTURED, APPROVED, ORPHAN_JOURNAL),
            (None, None, APPROVED, ORPHAN_JOURNAL),
        ]
        for transfer, hold, ledger, expected in cases:
            with self.subTest(transfer=transfer, hold=hold, ledger=ledger):
                self.assertEqual(classify(transfer, hold, ledger), expected)


class ReconcileTest(unittest.TestCase):

    def test_summary_and_anomalies(self):
        transfers = {"a": COMPLETED, "b": PENDING, "c": PENDING, "e": COMPENSATED}
        holds = {"a": CAPTURED, "c": HELD, "d": CAPTURED, "e": RELEASED}
        ledger = {"a": APPROVED, "d": APPROVED, "e": LEDGER_REJECTED}

        summary, anomalies = reconcile(transfers, holds, ledger)

        self.assertEqual(summary[CONSISTENT_COMPLETED], 1)
        self.assertEqual(summary[CONSISTENT_COMPENSATED], 1)
        self.assertEqual(summary[LOST_REQUEST], 1)
        self.assertEqual(summary[FUNDS_HELD], 1)
        self.assertEqual(summary[ORPHAN_JOURNAL], 1)
        self.assertEqual(summary["total"], 5)
        self.assertEqual(summary["inconsistent"], 3)
        self.assertEqual(summary["consistency_rate"], 0.4)
        self.assertEqual([a["transfer_id"] for a in anomalies], ["b", "c", "d"])

    def test_empty_input(self):
        summary, anomalies = reconcile({}, {}, {})
        self.assertEqual(summary["total"], 0)
        self.assertIsNone(summary["consistency_rate"])
        self.assertEqual(anomalies, [])


if __name__ == "__main__":
    unittest.main()
