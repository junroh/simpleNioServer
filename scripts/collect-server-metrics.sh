#!/usr/bin/env bash
# Samples server-container metrics every $INTERVAL seconds for $DURATION seconds
# and writes a single CSV. Designed to be invoked in the background by bench.sh.
#
# Each row: timestamp, cpu%, RSS MiB, thread count, voluntary ctxt switches,
# non-voluntary ctxt switches, open fd count.
#
# Cost note: each row triggers ~3 docker-exec round-trips. With the default
# INTERVAL=2 the overhead is small compared to the server's own load, but the
# numbers are best read as relative — don't compare absolute CPU% values
# between this script and a non-instrumented run.
set -euo pipefail

OUT_DIR=${1:?usage: collect-server-metrics.sh <out_dir> <duration_sec> [interval_sec]}
DURATION=${2:?missing duration}
INTERVAL=${3:-2}

CSV="$OUT_DIR/server-metrics.csv"
mkdir -p "$OUT_DIR"

CONTAINER=${BENCH_SERVER_CONTAINER:-bench-server}

echo "ts,cpu_pct,mem_mib,thread_count,vol_ctxt,nonvol_ctxt,open_fd" > "$CSV"

END=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$END" ]; do
  ts=$(date +%s)

  # CPU% and memory from docker stats (single non-streaming sample).
  stats=$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' "$CONTAINER" 2>/dev/null || true)
  cpu=$(printf '%s' "$stats" | awk -F'|' '{print $1}' | tr -d '%')
  mem_field=$(printf '%s' "$stats" | awk -F'|' '{print $2}' | awk '{print $1}')
  # mem_field looks like "123.4MiB" or "1.2GiB"
  case "$mem_field" in
    *GiB) mem_mib=$(awk -v v="${mem_field%GiB}" 'BEGIN{printf "%.1f", v*1024}') ;;
    *MiB) mem_mib=${mem_field%MiB} ;;
    *KiB) mem_mib=$(awk -v v="${mem_field%KiB}" 'BEGIN{printf "%.3f", v/1024}') ;;
    *)    mem_mib=0 ;;
  esac
  cpu=${cpu:-0}

  # Thread count and ctxt switches in a single exec to halve round-trips.
  inside=$(docker exec "$CONTAINER" sh -c \
    'echo THREADS=$(ls /proc/1/task | wc -l); cat /proc/1/status | grep -E "^(voluntary|nonvoluntary)_ctxt_switches"; echo FDS=$(ls /proc/1/fd | wc -l)' \
    2>/dev/null || true)
  threads=$(printf '%s\n' "$inside" | awk -F= '/^THREADS=/{print $2}')
  vol=$(printf '%s\n' "$inside" | awk '/^voluntary_ctxt_switches/{print $2}')
  nonvol=$(printf '%s\n' "$inside" | awk '/^nonvoluntary_ctxt_switches/{print $2}')
  fds=$(printf '%s\n' "$inside" | awk -F= '/^FDS=/{print $2}')

  echo "$ts,${cpu:-0},${mem_mib:-0},${threads:-0},${vol:-0},${nonvol:-0},${fds:-0}" >> "$CSV"

  sleep "$INTERVAL"
done
