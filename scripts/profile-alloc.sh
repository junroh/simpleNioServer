#!/usr/bin/env bash
# Allocation-profiling pass: run ONE scenario under load with a JFR recording,
# then extract the top allocation sites / classes and the GC summary. Answers
# "where do the bytes come from?" before any GC-reduction work (ADR 0004 follow-up).
#
# This is NOT a throughput measurement — JFR + allocation sampling perturb the
# numbers. Use scripts/bench.sh for latency/throughput. Here the wrk2 output is
# only there to generate representative load; the asset is results/alloc/<id>/alloc.jfr.
#
# Lifecycle:
#   1. Recreate the server with EXTRA_JAVA_OPTS enabling a JFR recording
#      (settings=profile → allocation sampling) written to /app/alloc.jfr.
#   2. Warm up, then drive load with wrk2 for $DURATION.
#   3. jcmd JFR.dump (robust — does not rely on clean shutdown), copy the .jfr out.
#   4. If a host `jfr` tool is available (JDK 21), render allocation-by-site,
#      allocation-by-class and gc views into alloc-views.txt.
#
# Output: results/alloc/<exp-id>/{alloc.jfr, alloc-views.txt, server.log, wrk2.txt, run.meta}
set -euo pipefail

# ---- defaults --------------------------------------------------------------
TRANSPORT=nio
C=100
RATE=20000
DURATION=60
WARMUP=15
PROTOCOL=http
REQ_PATH=/hello
OUT_DIR=results/alloc
EXP_ID=alloc-nio
NUM_REACTORS=2
BACKLOG=128
POOL_SIZE=100
SSL_ENABLED=false
HEAP=256m
WRK_THREADS=""

# ---- arg parsing -----------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --transport)     TRANSPORT="$2"; shift 2;;
    --c)             C="$2"; shift 2;;
    --rate)          RATE="$2"; shift 2;;
    --duration)      DURATION="$2"; shift 2;;
    --warmup)        WARMUP="$2"; shift 2;;
    --path)          REQ_PATH="$2"; shift 2;;
    --out-dir)       OUT_DIR="$2"; shift 2;;
    --exp-id)        EXP_ID="$2"; shift 2;;
    --num-reactors)  NUM_REACTORS="$2"; shift 2;;
    --pool-size)     POOL_SIZE="$2"; shift 2;;
    --backlog)       BACKLOG="$2"; shift 2;;
    --ssl)           SSL_ENABLED=true; PROTOCOL=https; shift;;
    --heap)          HEAP="$2"; shift 2;;
    --wrk-threads)   WRK_THREADS="$2"; shift 2;;
    -h|--help)       sed -n '1,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

if [[ -z "$WRK_THREADS" ]]; then
  WRK_THREADS=$(( C / 10 )); [[ $WRK_THREADS -lt 2 ]] && WRK_THREADS=2
fi

EXP_DIR="$OUT_DIR/$EXP_ID"
mkdir -p "$EXP_DIR"
URL="${PROTOCOL}://server:8080${REQ_PATH}"

# ---- transport → server env (mirrors bench.sh) -----------------------------
case "$TRANSPORT" in
  nio-async) export SERVER_TYPE=nio;  export ASYNC_DISPATCH=true;
             export ASYNC_DISPATCH_EXECUTOR=${ASYNC_DISPATCH_EXECUTOR:-fixed:500};;
  threaded|nio|virtual) export SERVER_TYPE="$TRANSPORT"; export ASYNC_DISPATCH=false;;
  *) echo "unknown transport: $TRANSPORT (threaded|nio|nio-async|virtual)" >&2; exit 2;;
esac

# JFR: settings=profile turns on jdk.ObjectAllocationSample (allocation sites).
# dumponexit is a safety net; step 3 dumps explicitly via jcmd regardless.
export EXTRA_JAVA_OPTS="-XX:StartFlightRecording=name=alloc,settings=profile,disk=true,dumponexit=true,filename=/app/alloc.jfr"
export SLEEP_MS=0 POOL_SIZE NUM_REACTORS BACKLOG SSL_ENABLED HEAP

cat <<EOF | tee "$EXP_DIR/run.meta"
EXP_ID=$EXP_ID
TRANSPORT=$TRANSPORT
C=$C
RATE=$RATE
DURATION=$DURATION
WARMUP=$WARMUP
PROTOCOL=$PROTOCOL
PATH=$REQ_PATH
NUM_REACTORS=$NUM_REACTORS
POOL_SIZE=$POOL_SIZE
HEAP=$HEAP
URL=$URL
EXTRA_JAVA_OPTS=$EXTRA_JAVA_OPTS
STARTED_AT=$(date -Iseconds)
EOF

# ---- start server ----------------------------------------------------------
echo "[profile-alloc] (re)creating server ($TRANSPORT, JFR on)"
docker compose down -t 5 >/dev/null 2>&1 || true
docker compose up -d --force-recreate server

echo "[profile-alloc] waiting for server to answer ${URL}"
ready=false
for i in $(seq 1 60); do
  if docker compose run --rm -T --entrypoint curl bench \
        --silent --output /dev/null --fail --insecure --max-time 2 "$URL"; then
    echo "[profile-alloc] server ready after ${i}s"; ready=true; break
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "[profile-alloc] FATAL: server never answered ${URL}" >&2
  docker compose logs server | tail -50 >&2
  docker compose down -t 5 || true
  exit 1
fi

# ---- warmup + measured load ------------------------------------------------
echo "[profile-alloc] warmup ${WARMUP}s"
docker compose run --rm -T bench \
    -t"$WRK_THREADS" -c"$C" -d"${WARMUP}s" -R"$RATE" --latency "$URL" \
    >"$EXP_DIR/warmup.txt" 2>&1 || true

echo "[profile-alloc] load ${DURATION}s @ rate=${RATE} c=${C} (JFR recording)"
docker compose run --rm -T bench \
    -t"$WRK_THREADS" -c"$C" -d"${DURATION}s" -R"$RATE" --latency "$URL" \
    >"$EXP_DIR/wrk2.txt" 2>&1 || true

# ---- dump + extract the recording (while the container is still up) --------
echo "[profile-alloc] dumping JFR"
docker exec bench-server jcmd 1 JFR.dump name=alloc filename=/app/alloc.jfr >/dev/null 2>&1 || \
  echo "[profile-alloc] WARN: JFR.dump via jcmd failed; relying on dumponexit" >&2
docker cp bench-server:/app/alloc.jfr "$EXP_DIR/alloc.jfr" 2>/dev/null || \
  echo "[profile-alloc] WARN: could not copy alloc.jfr out of the container" >&2
docker compose logs --no-color server >"$EXP_DIR/server.log" 2>&1 || true

# Now the recording is on the host; tear the container down.
docker compose down -t 15 >/dev/null 2>&1 || true

if [[ ! -f "$EXP_DIR/alloc.jfr" ]]; then
  echo "[profile-alloc] NOTE: alloc.jfr not captured — check that jcmd/JFR is available in the image." >&2
fi

# ---- render views on the host if a JDK `jfr` tool is available -------------
if command -v jfr >/dev/null 2>&1 && [[ -f "$EXP_DIR/alloc.jfr" ]]; then
  echo "[profile-alloc] rendering allocation views -> alloc-views.txt"
  {
    echo "===== jfr summary ====="; jfr summary "$EXP_DIR/alloc.jfr"
    echo; echo "===== allocation-by-site ====="; jfr view allocation-by-site "$EXP_DIR/alloc.jfr"
    echo; echo "===== allocation-by-class ====="; jfr view allocation-by-class "$EXP_DIR/alloc.jfr"
    echo; echo "===== gc ====="; jfr view gc "$EXP_DIR/alloc.jfr"
  } >"$EXP_DIR/alloc-views.txt" 2>&1 || true
else
  echo "[profile-alloc] host 'jfr' tool not found; analyse manually:" >&2
  echo "  jfr view allocation-by-site $EXP_DIR/alloc.jfr" >&2
fi

echo "[profile-alloc] done -> $EXP_DIR"
