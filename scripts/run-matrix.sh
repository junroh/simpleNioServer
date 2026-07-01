#!/usr/bin/env bash
# Run benchmark scenarios in one of three profiles.
#
# Usage:
#   scripts/run-matrix.sh [--profile quick|minimal|full] [--reps N] [--out-dir DIR]
#                         [--only E1,E3,E7] [--dry-run]
#
# Profiles (default = minimal):
#   quick    — sanity test. Each transport answers a single c=50 instant scenario.
#              warmup 5s, measure 15s, reps 1. Goal: confirm the server runs.
#              ~2-3 min total.
#   minimal  — directional answers to "C10K? NIO improves? Loom effective?".
#              E7 at c ∈ {200, 1000, 2000} L=50ms, plus E6 idle keep-alive.
#              warmup 15s, measure 30s, reps 2. ~20 min total.
#   full     — precision run. E1..E7 with the full c sweeps.
#              warmup 30s, measure 60s, reps 5. ~3-5 h total.
#
# Each scenario calls scripts/bench.sh, which manages the server lifecycle and
# writes its outputs to $OUT_DIR/$EXP_ID/$transport_c${C}_L${L}_${proto}/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILE=minimal
OUT_DIR=results/raw
ONLY=""
DRY_RUN=false
REPS=""   # if unset, profile default

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)  PROFILE="$2"; shift 2;;
    --reps)     REPS="$2"; shift 2;;
    --out-dir)  OUT_DIR="$2"; shift 2;;
    --only)     ONLY="$2"; shift 2;;
    --dry-run)  DRY_RUN=true; shift;;
    -h|--help)
      sed -n '1,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

# ---- profile-driven settings ----------------------------------------------
case "$PROFILE" in
  quick)
    WARMUP=5; DURATION=15; DEFAULT_REPS=1
    WANTED=(E1)
    ;;
  minimal)
    WARMUP=15; DURATION=30; DEFAULT_REPS=2
    WANTED=(E7 E6)
    ;;
  full)
    WARMUP=30; DURATION=60; DEFAULT_REPS=5
    WANTED=(E1 E2 E3 E4 E5 E6 E7)
    ;;
  *) echo "unknown profile: $PROFILE (expected quick|minimal|full)" >&2; exit 2;;
esac

[[ -z "$REPS" ]] && REPS=$DEFAULT_REPS

if [[ -n "$ONLY" ]]; then
  IFS=',' read -r -a WANTED <<<"$ONLY"
fi

TRANSPORTS=(threaded nio-async virtual)

# ---- scenario generators --------------------------------------------------
# Each line: EXP_ID transport c sleep_ms rate path extra_args
# extra_args are appended verbatim to the bench.sh invocation.

generate_e1() {
  for t in "${TRANSPORTS[@]}"; do
    echo "E1 $t 50 0 100000 /hello --warmup $WARMUP --duration $DURATION"
  done
}

generate_e2() {
  for t in "${TRANSPORTS[@]}"; do
    for c in 50 200 1000 5000; do
      echo "E2 $t $c 0 100000 /hello --warmup $WARMUP --duration $DURATION"
    done
  done
}

generate_e3() {
  for t in "${TRANSPORTS[@]}"; do
    for L in 10 50; do
      rate=$(( 1000 * 1000 / L * 2 ))
      echo "E3 $t 1000 $L $rate /sleep --warmup $WARMUP --duration $DURATION"
    done
  done
}

generate_e4() {
  for t in "${TRANSPORTS[@]}"; do
    echo "E4 $t 1000 0 100000 /hello --warmup $WARMUP --duration $DURATION --ssl"
  done
}

generate_e5() {
  for t in "${TRANSPORTS[@]}"; do
    echo "E5 $t 500 0 50000 /hello --warmup $WARMUP --duration $DURATION --wrk-extra -HConnection:close"
  done
}

generate_e6() {
  # 10 000 idle keep-alive connections at low rate. Threaded fails at poolSize.
  for t in "${TRANSPORTS[@]}"; do
    echo "E6 $t 10000 0 100 /hello --warmup $WARMUP --duration $DURATION"
  done
}

generate_e7() {
  # In minimal profile, only the c values that separate the three transports:
  #   c=200  — Threaded just past pool=100 (saturation starts).
  #   c=1000 — Threaded fully saturated; NIO-Async still under handlerPool=500.
  #   c=2000 — Both NIO-Async (pool=500) and Loom (c) saturation visible.
  # In full profile, the full ramp.
  if [[ "$PROFILE" == "minimal" ]]; then
    cs=(200 1000 2000)
  else
    cs=(50 100 200 500 1000 2000)
  fi
  for t in "${TRANSPORTS[@]}"; do
    for c in "${cs[@]}"; do
      rate=$(( c * 20 + 1000 ))
      echo "E7 $t $c 50 $rate /sleep --warmup $WARMUP --duration $DURATION"
    done
  done
}

gen_for_exp() {
  case "$1" in
    E1) generate_e1 ;;
    E2) generate_e2 ;;
    E3) generate_e3 ;;
    E4) generate_e4 ;;
    E5) generate_e5 ;;
    E6) generate_e6 ;;
    E7) generate_e7 ;;
    *)  return 1 ;;
  esac
}

# ---- enumerate scenarios --------------------------------------------------
SCENARIOS=()
for exp in "${WANTED[@]}"; do
  if ! gen_output=$(gen_for_exp "$exp"); then
    echo "unknown experiment: $exp" >&2; exit 2
  fi
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    SCENARIOS+=("$line")
  done <<<"$gen_output"
done

echo "[matrix] profile=$PROFILE  exps=${WANTED[*]}  reps=$REPS  scenarios=${#SCENARIOS[@]}"
# Per scenario: warmup + reps×duration + ~20s server restart.
echo "[matrix] est. duration: ~$(( ${#SCENARIOS[@]} * (WARMUP + REPS * DURATION + 20) / 60 )) min wall clock"
echo "[matrix] output -> $OUT_DIR"
echo

# ---- run -------------------------------------------------------------------
i=0
for s in "${SCENARIOS[@]}"; do
  i=$(( i + 1 ))
  # shellcheck disable=SC2206
  parts=($s)
  exp=${parts[0]}
  transport=${parts[1]}
  c=${parts[2]}
  sleep_ms=${parts[3]}
  rate=${parts[4]}
  path=${parts[5]}
  extra=("${parts[@]:6}")

  echo "============================================================"
  echo "[$i/${#SCENARIOS[@]}] $exp  transport=$transport  c=$c  L=$sleep_ms  rate=$rate  path=$path"
  echo "============================================================"

  cmd=( bash "$SCRIPT_DIR/bench.sh"
        --exp-id "$exp"
        --transport "$transport"
        --c "$c"
        --sleep-ms "$sleep_ms"
        --rate "$rate"
        --path "$path"
        --reps "$REPS"
        --out-dir "$OUT_DIR"
        "${extra[@]}" )

  if $DRY_RUN; then
    printf '   '
    printf '%q ' "${cmd[@]}"
    printf '\n'
  else
    "${cmd[@]}"
  fi
done

echo
echo "[matrix] done. analyse with: scripts/format-results.py $OUT_DIR"
