#!/usr/bin/env bash
# The experiment series for the thesis. Every configuration is repeated REPEATS times; repetitions are
# interleaved (round 1 of all configurations, then round 2, ...) so that slow drift of the machine
# (temperature, background processes) spreads evenly over configurations instead of biasing one of them.
#
# Usage: experiments/run-series.sh [series-name]      (wrap in caffeinate -i to keep the Mac awake)
# Rows land in experiments/results/summary-v2.csv with LABEL=<series-name>/<config>.
set -uo pipefail
cd "$(dirname "$0")/.."

SERIES=${1:-series-$(date +%Y%m%d)}
REPEATS=${REPEATS:-3}

if ! git diff --quiet; then
  echo "Uncommitted changes in tracked files: commit first, otherwise the runs cannot be reproduced." >&2
  exit 1
fi

# Series for the second axis of the comparison (ADR-0008): choreography vs orchestration of the three-service saga.
# The first series (naive vs outbox, two services, series-20261112) is reproducible from commit e5d0573.
# name | coordination | strategy | rate | durationSec | outbox poll | chaos mode | chaos target
CONFIGS=(
  "base-choreo|choreography|outbox|50|90|50ms|none|wallet-service"
  "base-orch|orchestration|outbox|50|90|50ms|none|wallet-service"
  "high-choreo|choreography|outbox|200|90|50ms|none|wallet-service"
  "high-orch|orchestration|outbox|200|90|50ms|none|wallet-service"
  "crash-wallet-choreo|choreography|outbox|50|100|50ms|crash-after-commit|wallet-service"
  "crash-wallet-orch|orchestration|outbox|50|100|50ms|crash-after-commit|wallet-service"
  "crash-ledger-choreo|choreography|outbox|50|100|50ms|crash-after-commit|ledger-service"
  "crash-ledger-orch|orchestration|outbox|50|100|50ms|crash-after-commit|ledger-service"
  "crash-transaction-choreo|choreography|outbox|50|100|50ms|crash-after-commit|transaction-service"
  "crash-transaction-orch|orchestration|outbox|50|100|50ms|crash-after-commit|transaction-service"
  "kill-transaction-choreo|choreography|outbox|50|100|50ms|kill|transaction-service"
  "kill-transaction-orch|orchestration|outbox|50|100|50ms|kill|transaction-service"
  "naive-crash-wallet-choreo|choreography|naive|50|100|50ms|crash-after-commit|wallet-service"
  "naive-crash-wallet-orch|orchestration|naive|50|100|50ms|crash-after-commit|wallet-service"
)

total=$(( ${#CONFIGS[@]} * REPEATS ))
n=0
build=1
started=$(date +%s)
for round in $(seq 1 "$REPEATS"); do
  for config in "${CONFIGS[@]}"; do
    IFS='|' read -r name coordination strategy rate duration poll chaos target <<< "$config"
    n=$((n + 1))
    echo "[$(date +%H:%M:%S)] ($n/$total) round $round: $name"
    BUILD=$build LABEL="$SERIES/$name" COORDINATION=$coordination OUTBOX_POLL_INTERVAL=$poll CHAOS_MODE=$chaos CHAOS_TARGET=$target \
      experiments/run-experiment.sh "$strategy" "$rate" "$duration" > /dev/null 2>&1 \
      || echo "    run failed (exit $?), see the newest directory in experiments/results"
    build=0
  done
done
echo "[$(date +%H:%M:%S)] Series $SERIES finished: $total runs in $(( ($(date +%s) - started) / 60 )) min"
