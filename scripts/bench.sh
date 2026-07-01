#!/usr/bin/env bash
# Runs ONE benchmark scenario: (transport × concurrency × handler-latency × protocol),
# replicated $REPS times against a single server container.
#
# Lifecycle per invocation:
#   1. (Re)create the server container with the requested env (server.type, sleep ms,
#      pool size, etc.). Wait until it answers HTTP.
#   2. Warmup with wrk2 for $WARMUP seconds, output discarded.
#   3. For each rep:
#        a. Start metric collector in background (collect-server-metrics.sh).
#        b. Run wrk2 for $DURATION seconds. Save its full output.
#        c. Snapshot threads via `jcmd Thread.print`.
#   4. Save the server's stdout/stderr log (includes -Djdk.tracePinnedThreads output).
#   5. Stop and remove the server.
#
# Output tree:
#   $OUT_DIR/$EXP_ID/${transport}_c${C}_L${L}_${protocol}/
#     warmup.txt
#     server.log
#     rep1/wrk2.txt
#     rep1/server-metrics.csv
#     rep1/threads.txt
#     rep2/...
#
# Defaults align with docs/BENCHMARK.md §5 common controls.
set -euo pipefail

# ---- defaults --------------------------------------------------------------
TRANSPORT=nio
C=100
SLEEP_MS=0
RATE=10000
DURATION=60
WARMUP=30
REPS=5
PROTOCOL=http
REQ_PATH=/hello
OUT_DIR=results/raw
EXP_ID=E0
POOL_SIZE=100
NUM_REACTORS=2
BACKLOG=128
SSL_ENABLED=false
HEAP=256m
WRK_THREADS=""   # auto = max(2, c/10)
METRIC_INTERVAL=2
WRK_EXTRA=""     # extra args appended to wrk2 invocation (e.g. -HConnection:close)

# ---- arg parsing -----------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --transport)     TRANSPORT="$2"; shift 2;;
    --c)             C="$2"; shift 2;;
    --sleep-ms)      SLEEP_MS="$2"; shift 2;;
    --rate)          RATE="$2"; shift 2;;
    --duration)      DURATION="$2"; shift 2;;
    --warmup)        WARMUP="$2"; shift 2;;
    --reps)          REPS="$2"; shift 2;;
    --protocol)      PROTOCOL="$2"; shift 2;;
    --path)          REQ_PATH="$2"; shift 2;;
    --out-dir)       OUT_DIR="$2"; shift 2;;
    --exp-id)        EXP_ID="$2"; shift 2;;
    --pool-size)     POOL_SIZE="$2"; shift 2;;
    --num-reactors)  NUM_REACTORS="$2"; shift 2;;
    --backlog)       BACKLOG="$2"; shift 2;;
    --ssl)           SSL_ENABLED=true; PROTOCOL=https; shift;;
    --heap)          HEAP="$2"; shift 2;;
    --wrk-threads)   WRK_THREADS="$2"; shift 2;;
    --wrk-extra)     WRK_EXTRA="$2"; shift 2;;
    --metric-interval) METRIC_INTERVAL="$2"; shift 2;;
    -h|--help)
      sed -n '1,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

# ---- derived ---------------------------------------------------------------
if [[ -z "$WRK_THREADS" ]]; then
  WRK_THREADS=$(( C / 10 ))
  [[ $WRK_THREADS -lt 2 ]] && WRK_THREADS=2
fi

EXP_DIR="$OUT_DIR/$EXP_ID/${TRANSPORT}_c${C}_L${SLEEP_MS}_${PROTOCOL}"
mkdir -p "$EXP_DIR"

URL="${PROTOCOL}://server:8080${REQ_PATH}"

# ---- print run header so the log self-describes ----------------------------
cat <<EOF | tee "$EXP_DIR/run.meta"
EXP_ID=$EXP_ID
TRANSPORT=$TRANSPORT
C=$C
SLEEP_MS=$SLEEP_MS
RATE=$RATE
DURATION=$DURATION
WARMUP=$WARMUP
REPS=$REPS
PROTOCOL=$PROTOCOL
PATH=$REQ_PATH
POOL_SIZE=$POOL_SIZE
NUM_REACTORS=$NUM_REACTORS
BACKLOG=$BACKLOG
SSL_ENABLED=$SSL_ENABLED
HEAP=$HEAP
WRK_THREADS=$WRK_THREADS
URL=$URL
STARTED_AT=$(date -Iseconds)
EOF

# ---- export env consumed by docker-compose.yml -----------------------------
# Map the bench-level "transport" onto the server's SERVER_TYPE + ASYNC_DISPATCH:
#   threaded   → SERVER_TYPE=threaded
#   nio        → SERVER_TYPE=nio,     ASYNC_DISPATCH=false
#   nio-async  → SERVER_TYPE=nio,     ASYNC_DISPATCH=true   ASYNC_DISPATCH_EXECUTOR=fixed:500
#   virtual    → SERVER_TYPE=virtual
case "$TRANSPORT" in
  nio-async)
    export SERVER_TYPE=nio
    export ASYNC_DISPATCH=true
    export ASYNC_DISPATCH_EXECUTOR=${ASYNC_DISPATCH_EXECUTOR:-fixed:500}
    ;;
  threaded|nio|virtual)
    export SERVER_TYPE="$TRANSPORT"
    export ASYNC_DISPATCH=false
    ;;
  *)
    echo "unknown transport: $TRANSPORT (expected threaded|nio|nio-async|virtual)" >&2
    exit 2
    ;;
esac
export SLEEP_MS POOL_SIZE NUM_REACTORS BACKLOG SSL_ENABLED HEAP

# ---- start server fresh ----------------------------------------------------
echo "[bench] (re)creating server"
docker compose down -t 5 >/dev/null 2>&1 || true
docker compose up -d --force-recreate server

# ---- wait until server answers --------------------------------------------
echo "[bench] waiting for server to answer ${URL}"
ready=false
for i in $(seq 1 60); do
  if docker compose run --rm -T --entrypoint curl bench \
        --silent --output /dev/null --fail --insecure --max-time 2 "$URL"; then
    echo "[bench] server ready after ${i}s"
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "[bench] FATAL: server never answered ${URL}" >&2
  docker compose logs server | tail -50 >&2
  docker compose down -t 5 || true
  exit 1
fi

# ---- warmup (discarded) ----------------------------------------------------
echo "[bench] warmup ${WARMUP}s @ rate=${RATE} c=${C}"
docker compose run --rm -T bench \
    -t"$WRK_THREADS" -c"$C" -d"${WARMUP}s" -R"$RATE" --latency ${WRK_EXTRA} "$URL" \
    >"$EXP_DIR/warmup.txt" 2>&1 || true

# ---- replicated reps -------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
for REP in $(seq 1 "$REPS"); do
  REP_DIR="$EXP_DIR/rep${REP}"
  mkdir -p "$REP_DIR"
  echo "[bench] rep ${REP}/${REPS}: ${DURATION}s @ rate=${RATE} c=${C}"

  bash "$SCRIPT_DIR/collect-server-metrics.sh" "$REP_DIR" "$DURATION" "$METRIC_INTERVAL" &
  METRIC_PID=$!

  docker compose run --rm -T bench \
      -t"$WRK_THREADS" -c"$C" -d"${DURATION}s" -R"$RATE" --latency ${WRK_EXTRA} "$URL" \
      >"$REP_DIR/wrk2.txt" 2>&1 || true

  wait "$METRIC_PID" 2>/dev/null || true

  # Mid/end-of-rep thread snapshot.
  docker exec bench-server jcmd 1 Thread.print \
      >"$REP_DIR/threads.txt" 2>&1 || true
done

# ---- archive server stderr (carries pinning + GC info) ---------------------
docker compose logs --no-color server >"$EXP_DIR/server.log" 2>&1 || true

# ---- cleanup ---------------------------------------------------------------
echo "[bench] stopping server"
docker compose down -t 5 >/dev/null 2>&1 || true

echo "[bench] done -> $EXP_DIR"
