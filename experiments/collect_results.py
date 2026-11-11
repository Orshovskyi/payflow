"""Collects the results of one experiment run into summary.json and appends a row to results/summary.csv.

Sources:
  * database (passed in by the shell script) - exact counts and the money-conservation invariant;
  * Prometheus - end-to-end saga latency percentiles and anomaly counters over the measured window;
  * Gatling console summary (gatling.log) - HTTP latency percentiles, throughput and errors as seen by the client.
    Gatling 3.12+ no longer writes stats.json and its simulation.log is binary, so the printed summary is parsed.
"""
import argparse
import csv
import json
import os
import re
import urllib.parse
import urllib.request
from decimal import Decimal

PROMETHEUS = "http://localhost:9090"


def prom(query, at):
    url = f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": query, "time": at})
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)["data"]["result"]
    if not result:
        return None
    value = float(result[0]["value"][1])
    return None if value != value else value  # NaN -> None


def ms(seconds):
    return None if seconds is None else round(seconds * 1000, 1)


GATLING_ROWS = {
    "request count": "http_requests",
    "response time 50th percentile (ms)": "http_p50_ms",
    "response time 95th percentile (ms)": "http_p95_ms",
    "response time 99th percentile (ms)": "http_p99_ms",
    "max response time (ms)": "http_max_ms",
    "mean throughput (rps)": "http_rps_mean",
}


def gatling_stats(run_dir):
    """Reads the "Global Information" block: columns Total | OK | KO."""
    path = os.path.join(run_dir, "gatling.log")
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        text = f.read()
    block = text.split("---- Global Information")[-1].split("---- Response Time Distribution")[0]
    stats = {}
    for line in block.splitlines():
        match = re.match(r"> (.+?)\s+\|\s*([\d,.\-]+)\s*\|\s*([\d,.\-]+)\s*\|\s*([\d,.\-]+)", line)
        if not match or match.group(1).strip() not in GATLING_ROWS:
            continue
        key = GATLING_ROWS[match.group(1).strip()]
        total, ok, ko = (None if v == "-" else float(v.replace(",", "")) for v in match.groups()[1:])
        if key == "http_requests":
            stats["http_requests_ok"] = int(ok or 0)
            stats["http_requests_ko"] = int(ko or 0)
        else:
            stats[key] = total
    return stats


def main():
    p = argparse.ArgumentParser()
    for name in ["run-dir", "run-id", "strategy", "rate", "duration", "poll-interval", "start", "end",
                 "total-before", "total-after", "new-money", "pending", "commit", "label", "gatling-exit"]:
        p.add_argument("--" + name, required=name != "label", default="")
    a = p.parse_args()

    window = f"{int(a.end) - int(a.start)}s"
    e2e = "payflow_transfers_e2e_seconds_bucket"

    def quantile(q, metric, selector=""):
        return prom(f"histogram_quantile({q}, sum by (le) (increase({metric}{selector}[{window}])))", a.end)

    def increase(metric):
        # Counters are registered lazily: a series that never appeared means the event never happened.
        value = prom(f"sum(increase({metric}[{window}]))", a.end)
        return 0 if value is None else round(value)

    expected_total = Decimal(a.total_before) + Decimal(a.new_money)
    statuses = {}
    with open(os.path.join(a.run_dir, "transfers-by-status.txt")) as f:
        for line in f:
            if "|" in line:
                status, count = line.strip().split("|")
                statuses[status] = int(count)

    summary = {
        "run_id": a.run_id, "commit": a.commit, "label": a.label,
        "strategy": a.strategy, "outbox_poll_interval": a.poll_interval if a.strategy == "outbox" else "",
        "target_rate": int(a.rate), "duration_s": int(a.duration), "measured_window_s": int(a.end) - int(a.start),
        "gatling_exit_code": int(a.gatling_exit),
        **gatling_stats(a.run_dir),
        "e2e_p50_ms": ms(quantile(0.5, e2e)),
        "e2e_p95_ms": ms(quantile(0.95, e2e)),
        "e2e_p99_ms": ms(quantile(0.99, e2e)),
        "outbox_lag_p99_ms": ms(quantile(0.99, "payflow_outbox_lag_seconds_bucket")),
        # Totals over all runs of this stack (warm-up included): exact values from the database.
        "transfers_completed": statuses.get("COMPLETED", 0),
        "transfers_failed": statuses.get("FAILED", 0),
        "transfers_pending_after_drain": int(a.pending),
        "money_conserved": Decimal(a.total_after) == expected_total,
        "total_balance_expected": str(expected_total), "total_balance_actual": a.total_after,
        # Anomalies inside the measured window (Prometheus increase() is extrapolated, so values are approximate).
        "naive_publish_failures": increase("payflow_events_publish_failed_total"),
        "outbox_relay_failures": increase("payflow_outbox_relay_failures_total"),
        "duplicate_requests_skipped": increase("payflow_wallet_duplicates_skipped_total"),
        "duplicate_results_ignored": increase("payflow_transfers_duplicate_results_total"),
    }
    with open(os.path.join(a.run_dir, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2, default=str)

    csv_path = os.path.join(os.path.dirname(a.run_dir.rstrip("/")), "summary.csv")
    new_file = not os.path.exists(csv_path)
    with open(csv_path, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(summary))
        if new_file:
            writer.writeheader()
        writer.writerow(summary)

    for key, value in summary.items():
        print(f"  {key:32} {value}")


if __name__ == "__main__":
    main()
