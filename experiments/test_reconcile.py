"""Run: python3 -m unittest discover -s experiments"""
import unittest

from reconcile import (CONSISTENT_COMPLETED, CONSISTENT_FAILED, FINISHED_WITHOUT_JOURNAL, JournalEntry,
                       LOST_REQUEST, LOST_RESULT_MONEY_MOVED, LOST_RESULT_REJECTED, ORPHAN_JOURNAL,
                       OUTCOME_MISMATCH, Transfer, classify, reconcile)

COMPLETED = Transfer("COMPLETED", "")
FAILED_FUNDS = Transfer("FAILED", "INSUFFICIENT_FUNDS")
PENDING = Transfer("PENDING", "")
APPLIED = JournalEntry("COMPLETED", "")
REJECTED_FUNDS = JournalEntry("FAILED", "INSUFFICIENT_FUNDS")


class ClassifyTest(unittest.TestCase):

    def test_every_combination(self):
        cases = [
            (COMPLETED, APPLIED, CONSISTENT_COMPLETED),
            (FAILED_FUNDS, REJECTED_FUNDS, CONSISTENT_FAILED),
            (PENDING, None, LOST_REQUEST),
            (PENDING, APPLIED, LOST_RESULT_MONEY_MOVED),
            (PENDING, REJECTED_FUNDS, LOST_RESULT_REJECTED),
            (COMPLETED, REJECTED_FUNDS, OUTCOME_MISMATCH),
            (FAILED_FUNDS, APPLIED, OUTCOME_MISMATCH),
            (Transfer("FAILED", "WALLET_NOT_FOUND"), REJECTED_FUNDS, OUTCOME_MISMATCH),
            (COMPLETED, None, FINISHED_WITHOUT_JOURNAL),
            (None, APPLIED, ORPHAN_JOURNAL),
        ]
        for transfer, entry, expected in cases:
            with self.subTest(transfer=transfer, entry=entry):
                self.assertEqual(classify(transfer, entry), expected)


class ReconcileTest(unittest.TestCase):

    def test_summary_and_anomalies(self):
        transfers = {"a": COMPLETED, "b": PENDING, "c": PENDING}
        journal = {"a": APPLIED, "c": APPLIED, "d": APPLIED}

        summary, anomalies = reconcile(transfers, journal)

        self.assertEqual(summary[CONSISTENT_COMPLETED], 1)
        self.assertEqual(summary[LOST_REQUEST], 1)
        self.assertEqual(summary[LOST_RESULT_MONEY_MOVED], 1)
        self.assertEqual(summary[ORPHAN_JOURNAL], 1)
        self.assertEqual(summary["total"], 4)
        self.assertEqual(summary["inconsistent"], 3)
        self.assertEqual(summary["consistency_rate"], 0.25)
        self.assertEqual([a["transfer_id"] for a in anomalies], ["b", "c", "d"])

    def test_empty_input(self):
        summary, anomalies = reconcile({}, {})
        self.assertEqual(summary["total"], 0)
        self.assertIsNone(summary["consistency_rate"])
        self.assertEqual(anomalies, [])


if __name__ == "__main__":
    unittest.main()
