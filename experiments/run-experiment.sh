#!/usr/bin/env bash
# One reproducible experiment run: clean stack -> warm-up -> measured load -> drain -> collect results.
#
# Usage:  experiments/run-experiment.sh <naive|outbox> [rate] [durationSec]
# Env:    OUTBOX_POLL_INTERVAL (default 50ms), WALLETS (100), WARMUP_RATE (20), WARMUP_SEC (20),
#         DRAIN_TIMEOUT_SEC (60), LABEL (free text stored with the results), BUILD=1 (rebuild jars and images first)
#
# Every run starts from an empty database and a fresh Kafka broker, so runs do not influence each other.
set -euo pipefail

STRATEGY=${1:?usage: run-experiment.sh <naive|outbox> [rate] [durationSec]}
RATE=${2:-50}
DURATION_SEC=${3:-120}
OUTBOX_POLL_INTERVAL=${OUTBOX_POLL_INTERVAL:-50ms}
WALLETS=${WALLETS:-100}
WARMUP_RATE=${WARMUP_RATE:-20}
WARMUP_SEC=${WARMUP_SEC:-20}
DRAIN_TIMEOUT_SEC=${DRAIN_TIMEOUT_SEC:-60}
INITIAL_BALANCE=1000000

case "$STRATEGY" in naive|outbox) ;; *) echo "strategy must be naive or outbox" >&2; exit 2 ;; esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
RUN_ID="$(date +%Y%m%d-%H%M%S)_${STRATEGY}_r${RATE}"
[ "$STRATEGY" = outbox ] && RUN_ID="${RUN_ID}_poll${OUTBOX_POLL_INTERVAL}"
RUN_DIR="experiments/results/$RUN_ID"
mkdir -p "$RUN_DIR"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$RUN_DIR/run.log"; }
psql_tx() { docker compose exec -T postgres psql -U payflow -d transaction_db -tAc "$1"; }
psql_wallet() { docker compose exec -T postgres psql -U payflow -d wallet_db -tAc "$1"; }
# gatling <log file> <rate> <durationSec> <wallets>; copies the HTML report next to the log
gatling() {
  local status=0
  ./mvnw -q -f load-tests/pom.xml gatling:test \
    -Drate="$2" -DdurationSec="$3" -Dwallets="$4" -DinitialBalance=$INITIAL_BALANCE > "$1" 2>&1 || status=$?
  local report
  report=$(grep -o 'file://[^ ]*/index.html' "$1" | tail -1 | sed 's#^file://##; s#/index.html$##')
  [ -n "$report" ] && cp -R "$report" "${1%.log}-report"
  return $status
}

if [ "${BUILD:-0}" = 1 ]; then
  log "Building jars and images"
  ./mvnw -q -B package -DskipTests
  docker compose --profile app build -q
fi

log "Run $RUN_ID: strategy=$STRATEGY rate=$RATE/s duration=${DURATION_SEC}s poll=$OUTBOX_POLL_INTERVAL"
log "Resetting the stack (database volumes, Kafka, metrics)"
docker compose --profile app down -v --remove-orphans >/dev/null 2>&1
STRATEGY=$STRATEGY OUTBOX_POLL_INTERVAL=$OUTBOX_POLL_INTERVAL docker compose --profile app up -d --wait >/dev/null 2>&1 \
  || docker compose --profile app up -d >/dev/null
for port in 8081 8082; do
  for _ in $(seq 1 90); do curl -sf "localhost:$port/actuator/health" >/dev/null && break; sleep 1; done
  curl -sf "localhost:$port/actuator/health" >/dev/null || { log "Service on :$port did not start"; exit 1; }
done

log "Warm-up: ${WARMUP_RATE}/s for ${WARMUP_SEC}s (JIT, connection pools, Kafka consumer groups); not measured"
gatling "$RUN_DIR/warmup.log" "$WARMUP_RATE" "$WARMUP_SEC" 10 || log "Warm-up reported errors (see warmup.log)"
for _ in $(seq 1 "$DRAIN_TIMEOUT_SEC"); do [ "$(psql_tx "select count(*) from transfers where status='PENDING'")" = 0 ] && break; sleep 1; done
sleep 10   # let at least one Prometheus scrape pass, so the measured window starts clean

TOTAL_BEFORE=$(psql_wallet "select coalesce(sum(balance),0) from wallets")
START_TS=$(date +%s)
log "Measured load: ${RATE}/s for ${DURATION_SEC}s"
set +e
gatling "$RUN_DIR/gatling.log" "$RATE" "$DURATION_SEC" "$WALLETS"
GATLING_EXIT=$?
set -e
[ $GATLING_EXIT -ne 0 ] && log "Gatling exited with $GATLING_EXIT (assertion failed or errors, see gatling.log)"

log "Draining: waiting up to ${DRAIN_TIMEOUT_SEC}s for PENDING transfers to finish"
for _ in $(seq 1 "$DRAIN_TIMEOUT_SEC"); do [ "$(psql_tx "select count(*) from transfers where status='PENDING'")" = 0 ] && break; sleep 1; done
sleep 10   # final Prometheus scrape
END_TS=$(date +%s)

psql_tx "select status, count(*) from transfers group by status order by status" > "$RUN_DIR/transfers-by-status.txt"
python3 -I experiments/collect_results.py \
  --run-dir "$RUN_DIR" --run-id "$RUN_ID" --strategy "$STRATEGY" --rate "$RATE" --duration "$DURATION_SEC" \
  --poll-interval "$OUTBOX_POLL_INTERVAL" --start "$START_TS" --end "$END_TS" \
  --total-before "$TOTAL_BEFORE" --total-after "$(psql_wallet "select coalesce(sum(balance),0) from wallets")" \
  --new-money "$((WALLETS * INITIAL_BALANCE))" \
  --pending "$(psql_tx "select count(*) from transfers where status='PENDING'")" \
  --commit "$(git rev-parse --short HEAD)$(git diff --quiet || echo '-dirty')" --label "${LABEL:-}" \
  --gatling-exit "$GATLING_EXIT" | tee -a "$RUN_DIR/run.log"
log "Done. Results: $RUN_DIR (the stack is left running: Grafana http://localhost:3000)"
