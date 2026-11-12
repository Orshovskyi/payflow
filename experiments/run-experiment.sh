#!/usr/bin/env bash
# One reproducible experiment run: clean stack -> warm-up -> measured load -> drain -> collect results.
#
# Usage:  experiments/run-experiment.sh <naive|outbox> [rate] [durationSec]
# Env:    OUTBOX_POLL_INTERVAL (default 50ms), WALLETS (100), WARMUP_RATE (20), WARMUP_SEC (20),
#         DRAIN_TIMEOUT_SEC (60), LABEL (free text stored with the results), BUILD=1 (rebuild jars and images first)
# Chaos:  CHAOS_MODE      none | kill (docker kill -s KILL at an arbitrary moment)
#                         | crash-after-commit (process halts right after the next business commit)
#         CHAOS_TARGET    wallet-service | transaction-service
#         CHAOS_EVENTS (3), CHAOS_FIRST_AT (20 s after the measured load starts), CHAOS_INTERVAL (30 s)
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
CHAOS_MODE=${CHAOS_MODE:-none}
CHAOS_TARGET=${CHAOS_TARGET:-wallet-service}
CHAOS_EVENTS=${CHAOS_EVENTS:-3}
CHAOS_FIRST_AT=${CHAOS_FIRST_AT:-20}
CHAOS_INTERVAL=${CHAOS_INTERVAL:-30}
INITIAL_BALANCE=1000000

case "$STRATEGY" in naive|outbox) ;; *) echo "strategy must be naive or outbox" >&2; exit 2 ;; esac
case "$CHAOS_MODE" in none|kill|crash-after-commit) ;; *) echo "CHAOS_MODE must be none, kill or crash-after-commit" >&2; exit 2 ;; esac
case "$CHAOS_TARGET" in wallet-service) CHAOS_PORT=8081 ;; transaction-service) CHAOS_PORT=8082 ;;
  *) echo "CHAOS_TARGET must be wallet-service or transaction-service" >&2; exit 2 ;; esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
RUN_ID="$(date +%Y%m%d-%H%M%S)_${STRATEGY}_r${RATE}"
[ "$STRATEGY" = outbox ] && RUN_ID="${RUN_ID}_poll${OUTBOX_POLL_INTERVAL}"
[ "$CHAOS_MODE" != none ] && RUN_ID="${RUN_ID}_${CHAOS_MODE}-${CHAOS_TARGET%-service}"
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

wait_healthy() {  # wait_healthy <port> <timeout seconds>
  for _ in $(seq 1 "$2"); do curl -sf "localhost:$1/actuator/health" >/dev/null && return 0; sleep 1; done
  return 1
}
is_running() { [ -n "$(docker compose --profile app ps --status running -q "$1")" ]; }

# Background loop during the measured load: CHAOS_EVENTS failures of CHAOS_TARGET, each followed by a restart.
# Every event is written to chaos.log with its timestamps, so it can be matched with the Grafana graphs.
chaos_loop() {
  sleep "$CHAOS_FIRST_AT"
  for i in $(seq 1 "$CHAOS_EVENTS"); do
    local started down_at up_at
    started=$(date +%s)
    case "$CHAOS_MODE" in
      kill) docker compose --profile app kill -s KILL "$CHAOS_TARGET" >/dev/null 2>&1 || true ;;
      # The response may never arrive: another thread can commit and halt the process first.
      # Whether the crash happened is checked below by watching the container.
      crash-after-commit) curl -s -m 2 -XPOST "localhost:$CHAOS_PORT/chaos/crash-after-commit" >/dev/null || true ;;
    esac
    # crash-after-commit waits for the next commit, which can take a while if the service is still recovering
    for _ in $(seq 1 300); do is_running "$CHAOS_TARGET" || break; sleep 0.1; done
    if is_running "$CHAOS_TARGET"; then
      echo "event $i: $CHAOS_TARGET did not go down" >> "$RUN_DIR/chaos.log"
    else
      down_at=$(date +%s)
      docker compose --profile app start "$CHAOS_TARGET" >/dev/null 2>&1
      wait_healthy "$CHAOS_PORT" 90 || echo "event $i: $CHAOS_TARGET did not come back" >> "$RUN_DIR/chaos.log"
      up_at=$(date +%s)
      echo "event $i: $CHAOS_MODE $CHAOS_TARGET down_at=$down_at up_at=$up_at downtime_s=$((up_at - down_at))" \
        >> "$RUN_DIR/chaos.log"
    fi
    [ "$i" -lt "$CHAOS_EVENTS" ] && sleep $(( CHAOS_INTERVAL - ($(date +%s) - started) > 0 ? CHAOS_INTERVAL - ($(date +%s) - started) : 0 ))
  done
}

if [ "${BUILD:-0}" = 1 ]; then
  log "Building jars and images"
  ./mvnw -q -B package -DskipTests
  docker compose --profile app build -q
fi

log "Run $RUN_ID: strategy=$STRATEGY rate=$RATE/s duration=${DURATION_SEC}s poll=$OUTBOX_POLL_INTERVAL chaos=$CHAOS_MODE"
log "Resetting the stack (database volumes, Kafka, metrics)"
docker compose --profile app down -v --remove-orphans >/dev/null 2>&1
CHAOS_ENABLED=$([ "$CHAOS_MODE" = crash-after-commit ] && echo true || echo false)
STRATEGY=$STRATEGY OUTBOX_POLL_INTERVAL=$OUTBOX_POLL_INTERVAL CHAOS_ENABLED=$CHAOS_ENABLED \
  docker compose --profile app up -d --wait >/dev/null 2>&1 || \
  STRATEGY=$STRATEGY OUTBOX_POLL_INTERVAL=$OUTBOX_POLL_INTERVAL CHAOS_ENABLED=$CHAOS_ENABLED \
  docker compose --profile app up -d >/dev/null
export STRATEGY OUTBOX_POLL_INTERVAL CHAOS_ENABLED   # `docker compose start` after a crash keeps the same settings
for port in 8081 8082; do
  wait_healthy "$port" 90 || { log "Service on :$port did not start"; exit 1; }
done

log "Warm-up: ${WARMUP_RATE}/s for ${WARMUP_SEC}s (JIT, connection pools, Kafka consumer groups); not measured"
gatling "$RUN_DIR/warmup.log" "$WARMUP_RATE" "$WARMUP_SEC" 10 || log "Warm-up reported errors (see warmup.log)"
for _ in $(seq 1 "$DRAIN_TIMEOUT_SEC"); do [ "$(psql_tx "select count(*) from transfers where status='PENDING'")" = 0 ] && break; sleep 1; done
sleep 10   # let at least one Prometheus scrape pass, so the measured window starts clean

TOTAL_BEFORE=$(psql_wallet "select coalesce(sum(balance),0) from wallets")
START_TS=$(date +%s)
log "Measured load: ${RATE}/s for ${DURATION_SEC}s"
CHAOS_PID=""
if [ "$CHAOS_MODE" != none ]; then
  log "Chaos: $CHAOS_EVENTS x $CHAOS_MODE on $CHAOS_TARGET, first at +${CHAOS_FIRST_AT}s, every ${CHAOS_INTERVAL}s"
  : > "$RUN_DIR/chaos.log"
  chaos_loop &
  CHAOS_PID=$!
fi
set +e
gatling "$RUN_DIR/gatling.log" "$RATE" "$DURATION_SEC" "$WALLETS"
GATLING_EXIT=$?
[ -n "$CHAOS_PID" ] && wait "$CHAOS_PID"
set -e
if [ "$CHAOS_MODE" != none ]; then
  sed 's/^/    /' "$RUN_DIR/chaos.log" | tee -a "$RUN_DIR/run.log"
  is_running "$CHAOS_TARGET" || { log "$CHAOS_TARGET is down after the load, restarting"; docker compose --profile app start "$CHAOS_TARGET" >/dev/null; wait_healthy "$CHAOS_PORT" 90; }
fi
[ $GATLING_EXIT -ne 0 ] && log "Gatling exited with $GATLING_EXIT (assertion failed or errors, see gatling.log)"

log "Draining: waiting up to ${DRAIN_TIMEOUT_SEC}s for PENDING transfers to finish"
for _ in $(seq 1 "$DRAIN_TIMEOUT_SEC"); do [ "$(psql_tx "select count(*) from transfers where status='PENDING'")" = 0 ] && break; sleep 1; done
sleep 10   # final Prometheus scrape
END_TS=$(date +%s)

psql_tx "select status, count(*) from transfers group by status order by status" > "$RUN_DIR/transfers-by-status.txt"
log "Reconciliation (transaction_db.transfers vs wallet_db.applied_transfers)"
python3 -I experiments/reconcile.py --json-out "$RUN_DIR/reconciliation.json" \
  --anomalies-out "$RUN_DIR/anomalies.csv" > /dev/null || log "Reconciliation found inconsistent transfers (see anomalies.csv)"
python3 -I experiments/collect_results.py \
  --run-dir "$RUN_DIR" --run-id "$RUN_ID" --strategy "$STRATEGY" --rate "$RATE" --duration "$DURATION_SEC" \
  --poll-interval "$OUTBOX_POLL_INTERVAL" --start "$START_TS" --end "$END_TS" \
  --total-before "$TOTAL_BEFORE" --total-after "$(psql_wallet "select coalesce(sum(balance),0) from wallets")" \
  --new-money "$((WALLETS * INITIAL_BALANCE))" \
  --pending "$(psql_tx "select count(*) from transfers where status='PENDING'")" \
  --commit "$(git rev-parse --short HEAD)$(git diff --quiet || echo '-dirty')" --label "${LABEL:-}" \
  --gatling-exit "$GATLING_EXIT" --chaos-mode "$CHAOS_MODE" --chaos-target "$CHAOS_TARGET" \
  --chaos-events-done "$(grep -c 'downtime_s=' "$RUN_DIR/chaos.log" 2>/dev/null || echo 0)" | tee -a "$RUN_DIR/run.log"
log "Done. Results: $RUN_DIR (the stack is left running: Grafana http://localhost:3000)"
