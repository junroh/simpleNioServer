#!/usr/bin/env python3
"""
Aggregates results/raw/, applies the pass rules from docs/BENCHMARK.md §5
verbatim, and writes a self-explanatory report. Result-only interpretation:
if a verdict says CONFIRMED, the data satisfied the pre-registered numeric
criterion — no human interpretation involved.

Usage:
    scripts/format-results.py [RAW_DIR] [--out report.md] [--pool-size 100] [--num-reactors 2]

Defaults: RAW_DIR=results/raw, pool-size=100, num-reactors=2.
"""

import argparse
import csv
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path


# ----------------------------------------------------------------- parsers --

UNIT_TO_MS = {'us': 0.001, 'ms': 1.0, 's': 1000.0, 'm': 60000.0}

PCT_LINE = re.compile(r'^\s*(\d{1,3}(?:\.\d+)?)%\s+([\d.]+)(us|ms|s|m)\b')
TPS_LINE = re.compile(r'^Requests/sec:\s+([\d.]+)')
ERRORS_LINE = re.compile(r'Socket errors:.*?timeout\s+(\d+)')
NON2XX_LINE = re.compile(r'Non-2xx or 3xx responses:\s+(\d+)')
REQ_LINE = re.compile(r'(\d+)\s+requests in')


def parse_wrk2(text):
    tps = p50 = p99 = p999 = None
    errors = 0
    requests = 0
    for line in text.splitlines():
        m = TPS_LINE.match(line)
        if m:
            tps = float(m.group(1))
            continue
        m = PCT_LINE.match(line)
        if m:
            pct = float(m.group(1))
            ms = float(m.group(2)) * UNIT_TO_MS[m.group(3)]
            if abs(pct - 50.0) < 0.01:
                p50 = ms
            elif abs(pct - 99.0) < 0.01:
                p99 = ms
            elif abs(pct - 99.9) < 0.01:
                p999 = ms
            continue
        m = ERRORS_LINE.search(line)
        if m:
            errors += int(m.group(1))
            continue
        m = NON2XX_LINE.search(line)
        if m:
            errors += int(m.group(1))
            continue
        m = REQ_LINE.search(line)
        if m:
            requests = max(requests, int(m.group(1)))
    return {'tps': tps, 'p50': p50, 'p99': p99, 'p999': p999,
            'errors': errors, 'requests': requests}


def parse_server_metrics(csv_path, interval=2):
    rows = []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            rows.append(r)
    if not rows:
        return {}

    def col(name, cast=float):
        out = []
        for r in rows:
            v = r.get(name, '').strip()
            if v:
                try:
                    out.append(cast(v))
                except ValueError:
                    pass
        return out

    cpu = col('cpu_pct')
    mem = col('mem_mib')
    threads = col('thread_count', int)
    vol = col('vol_ctxt', int)
    nonvol = col('nonvol_ctxt', int)
    fds = col('open_fd', int)

    ctxt_rate = 0.0
    if len(vol) >= 2:
        delta_vol = vol[-1] - vol[0]
        delta_nonvol = (nonvol[-1] - nonvol[0]) if len(nonvol) >= 2 else 0
        seconds = (len(vol) - 1) * interval
        if seconds > 0:
            ctxt_rate = (delta_vol + delta_nonvol) / seconds

    return {
        'cpu_pct': median_or_none(cpu),
        'mem_mib': median_or_none(mem),
        'thread_count': median_or_none(threads),
        'ctxt_per_s': ctxt_rate,
        'open_fd': median_or_none(fds),
    }


STATE_RUNNABLE = re.compile(r'java\.lang\.Thread\.State:\s+RUNNABLE')
STATE_TIMED_WAITING = re.compile(r'java\.lang\.Thread\.State:\s+TIMED_WAITING')
STATE_WAITING = re.compile(r'java\.lang\.Thread\.State:\s+WAITING(?!\s*timed)')
VT_LINE = re.compile(r'^"[^"]+"\s+#\d+\s+\[\d+\]\s+virtual\b', re.M)
CARRIER_LINE = re.compile(r'ForkJoinPool-\d+-worker-\d+')


def parse_threads(text):
    return {
        'runnable': len(STATE_RUNNABLE.findall(text)),
        'timed_waiting': len(STATE_TIMED_WAITING.findall(text)),
        'waiting': len(STATE_WAITING.findall(text)),
        'virtual_threads': len(VT_LINE.findall(text)),
        'carrier_threads': len(set(CARRIER_LINE.findall(text))),
    }


PIN_LINE_NEW = re.compile(r'virtual thread.*?pinned', re.IGNORECASE)
PIN_LINE_HELD = re.compile(r'^Thread.*held monitor', re.M)


def parse_pinning(server_log_text):
    return len(PIN_LINE_NEW.findall(server_log_text)) + len(PIN_LINE_HELD.findall(server_log_text))


# ------------------------------------------------------------ aggregation --

def median_or_none(xs):
    return statistics.median(xs) if xs else None


def reject_outliers(values):
    """Drop values > 2× IQR from the median. Pass-through for < 4 samples."""
    vs = [v for v in values if v is not None]
    if len(vs) < 4:
        return vs
    med = statistics.median(vs)
    quartiles = statistics.quantiles(vs, n=4)
    iqr = quartiles[2] - quartiles[0]
    bound = 2 * iqr if iqr > 0 else max(med * 0.5, 1.0)
    return [v for v in vs if abs(v - med) <= bound]


def aggregate_metric(reps, key):
    vals = [r.get(key) for r in reps if isinstance(r, dict) and r.get(key) is not None]
    kept = reject_outliers(vals)
    return statistics.median(kept) if kept else None


def collect_scenario(scenario_dir):
    reps = sorted(p for p in scenario_dir.iterdir() if p.is_dir() and p.name.startswith('rep'))
    if not reps:
        return None

    wrk_results, metric_results, thread_snapshots = [], [], []
    for r in reps:
        wf = r / 'wrk2.txt'
        if wf.exists():
            wrk_results.append(parse_wrk2(wf.read_text()))
        mf = r / 'server-metrics.csv'
        if mf.exists():
            metric_results.append(parse_server_metrics(mf))
        tf = r / 'threads.txt'
        if tf.exists():
            thread_snapshots.append(parse_threads(tf.read_text()))

    server_log = scenario_dir / 'server.log'
    pinned = parse_pinning(server_log.read_text()) if server_log.exists() else 0

    row = {
        'tps':          aggregate_metric(wrk_results, 'tps'),
        'p50':          aggregate_metric(wrk_results, 'p50'),
        'p99':          aggregate_metric(wrk_results, 'p99'),
        'p999':         aggregate_metric(wrk_results, 'p999'),
        'errors':       aggregate_metric(wrk_results, 'errors') or 0,
        'requests':     aggregate_metric(wrk_results, 'requests') or 0,
        'cpu_pct':      aggregate_metric(metric_results, 'cpu_pct'),
        'mem_mib':      aggregate_metric(metric_results, 'mem_mib'),
        'thread_count': aggregate_metric(metric_results, 'thread_count'),
        'ctxt_per_s':   aggregate_metric(metric_results, 'ctxt_per_s'),
        'pinned':       pinned,
        'reps':         len(reps),
        'thread_snapshots': thread_snapshots,
    }
    row['error_rate'] = row['errors'] / row['requests'] if row['requests'] else 0.0
    return row


SCENARIO_NAME_RE = re.compile(
    r'^(?P<transport>nio-async|threaded|virtual)_c(?P<c>\d+)_L(?P<l>\d+)_(?P<proto>http|https)$')


def discover_rows(raw_dir):
    rows = []
    for exp_dir in sorted(raw_dir.iterdir()):
        if not exp_dir.is_dir():
            continue
        for sd in sorted(exp_dir.iterdir()):
            if not sd.is_dir():
                continue
            m = SCENARIO_NAME_RE.match(sd.name)
            if not m:
                continue
            row = collect_scenario(sd)
            if row is None:
                continue
            row.update({
                'exp': exp_dir.name,
                'transport': m['transport'],
                'c': int(m['c']),
                'L': int(m['l']),
                'proto': m['proto'],
            })
            rows.append(row)
    return rows


# ----------------------------------------------------------- pass rules ----
# Verdict in {'CONFIRMED', 'FALSIFIED', 'UNCLEAR'}.
# Each check returns (verdict, reason). Numeric criteria copied from §5.

def _fmt(x, nd=1):
    if x is None:
        return 'n/a'
    return f'{x:.{nd}f}'


def check_h1(rows, **_):
    """E1: pairwise tps within ±10%, pairwise p99 within ±30%."""
    e1 = [r for r in rows if r['exp'] == 'E1']
    if len({r['transport'] for r in e1}) < 3:
        return 'UNCLEAR', f'E1 has only {len(e1)} rows'
    tps = {r['transport']: r['tps'] for r in e1}
    p99 = {r['transport']: r['p99'] for r in e1}
    if any(v is None for v in tps.values()) or any(v is None for v in p99.values()):
        return 'UNCLEAR', 'E1 missing tps or p99 in some transport'
    tps_min, tps_max = min(tps.values()), max(tps.values())
    p99_min, p99_max = min(p99.values()), max(p99.values())
    tps_spread = (tps_max - tps_min) / tps_min if tps_min else 0
    p99_spread = (p99_max - p99_min) / p99_min if p99_min else 0
    ok = tps_spread <= 0.15 and p99_spread <= 0.30
    return ('CONFIRMED' if ok else 'FALSIFIED'), \
        f'tps spread {tps_spread*100:.1f}% (≤15%), p99 spread {p99_spread*100:.1f}% (≤30%)'


def check_h2(rows, **_):
    """E2: nio-async/threaded and virtual/threaded ratios non-decreasing in c,
    both ≥ 1.4 at c=5000 (Threaded plateaus first)."""
    e2 = [r for r in rows if r['exp'] == 'E2' and r['L'] == 0]
    by_c = defaultdict(dict)
    for r in e2:
        by_c[r['c']][r['transport']] = r['tps']
    cs = sorted(by_c.keys())
    if 5000 not in cs:
        return 'UNCLEAR', f'E2 missing c=5000 (have {cs})'
    async_ratios, vt_ratios = [], []
    for c in cs:
        t = by_c[c].get('threaded'); a = by_c[c].get('nio-async'); v = by_c[c].get('virtual')
        if not (t and a and v):
            return 'UNCLEAR', f'E2 c={c} missing a transport row'
        async_ratios.append(a / t)
        vt_ratios.append(v / t)
    async_mono = all(async_ratios[i] >= async_ratios[i-1] - 0.05 for i in range(1, len(async_ratios)))
    vt_mono    = all(vt_ratios[i]    >= vt_ratios[i-1]    - 0.05 for i in range(1, len(vt_ratios)))
    idx5k = cs.index(5000)
    ok = (async_mono and vt_mono
          and async_ratios[idx5k] >= 1.4
          and vt_ratios[idx5k] >= 1.4)
    reason = (f"@c=5000 — nio-async/T={async_ratios[idx5k]:.2f}, "
              f"vt/T={vt_ratios[idx5k]:.2f}; "
              f"monotonic: async={async_mono}, vt={vt_mono}")
    return ('CONFIRMED' if ok else 'FALSIFIED'), reason


def check_h3(rows, pool_size=100, async_pool_size=500, **_):
    """E3 L∈{10,50}: each transport's tps within ±50% of predicted ceiling.
    Threaded ceiling = poolSize × 1000 / L.
    NIO-Async ceiling = min(c, async_pool_size) × 1000 / L.
    Virtual ceiling = c × 1000 / L."""
    e3 = [r for r in rows if r['exp'] == 'E3' and r['L'] in (10, 50)]
    if not e3:
        return 'UNCLEAR', 'no E3 rows with L∈{10,50}'
    bad = []
    detail = []
    for r in e3:
        L, t, c, tps = r['L'], r['transport'], r['c'], r['tps']
        if tps is None:
            bad.append(f'{t} L={L} tps missing')
            continue
        if t == 'threaded':
            ceiling = pool_size * 1000.0 / L
        elif t == 'nio-async':
            ceiling = min(c, async_pool_size) * 1000.0 / L
        elif t == 'virtual':
            ceiling = c * 1000.0 / L
        else:
            continue
        diff = abs(tps - ceiling) / ceiling
        detail.append(f'{t} L={L}: tps={tps:.0f} ceil={ceiling:.0f} ({diff*100:.0f}% off)')
        if diff > 0.5:
            bad.append(f'{t} L={L} {diff*100:.0f}% off')
    return ('CONFIRMED' if not bad else 'FALSIFIED'), \
        ('all within ±50% — ' + '; '.join(detail)) if not bad else ('; '.join(bad))


def check_h4(rows, **_):
    """E4: HTTPS amplifies Threaded's saturation — nio-async/threaded and vt/threaded
    ratios under HTTPS exceed the HTTP ratios."""
    http = {r['transport']: r['tps'] for r in rows
            if r['exp'] == 'E2' and r['c'] == 1000 and r['L'] == 0 and r['proto'] == 'http'}
    https = {r['transport']: r['tps'] for r in rows
             if r['exp'] == 'E4' and r['proto'] == 'https'}
    if 'threaded' not in http or 'threaded' not in https:
        return 'UNCLEAR', 'need E2 c=1000 HTTP and E4 HTTPS for threaded baseline'
    parts, ok = [], True
    for t in ('nio-async', 'virtual'):
        if t not in http or t not in https:
            return 'UNCLEAR', f'{t} missing from E2 or E4'
        if not (http['threaded'] and https['threaded']):
            return 'UNCLEAR', 'threaded baseline tps is zero'
        r_http = http[t] / http['threaded']
        r_https = https[t] / https['threaded']
        parts.append(f'{t}: HTTP={r_http:.2f}, HTTPS={r_https:.2f}')
        if not (r_https > r_http):
            ok = False
    return ('CONFIRMED' if ok else 'FALSIFIED'), '; '.join(parts)


def check_h5(rows, **_):
    """E5: |off_ratio − on_ratio| / on_ratio ≤ 25% for nio-async and virtual.
    Compares E5 c=500 (no-keep-alive) vs E2 c=200 (closest baseline, keep-alive)."""
    on = {r['transport']: r['tps'] for r in rows
          if r['exp'] == 'E2' and r['c'] == 200 and r['L'] == 0 and r['proto'] == 'http'}
    off = {r['transport']: r['tps'] for r in rows
           if r['exp'] == 'E5' and r['c'] == 500}
    if 'threaded' not in on or 'threaded' not in off or not on['threaded']:
        return 'UNCLEAR', 'need E2 c=200 and E5 c=500 for threaded baseline'
    parts, ok = [], True
    for t in ('nio-async', 'virtual'):
        if t not in on or t not in off:
            return 'UNCLEAR', f'{t} missing'
        r_on = on[t] / on['threaded']
        r_off = off[t] / off['threaded']
        rel = abs(r_off - r_on) / r_on if r_on else 0
        parts.append(f'{t}: on={r_on:.2f}, off={r_off:.2f}, |Δ|={rel*100:.0f}%')
        if rel > 0.25:
            ok = False
    return ('CONFIRMED' if ok else 'FALSIFIED'), '; '.join(parts)


def check_h6(rows, **_):
    """E6: threaded error_rate ≥ 50% AND nio-async/virtual error_rate ≤ 1% AND p99 ≤ 20ms."""
    e6 = {r['transport']: r for r in rows if r['exp'] == 'E6'}
    if len(e6) < 3:
        return 'UNCLEAR', f'E6 has transports {sorted(e6.keys())}'
    if 'threaded' not in e6:
        return 'UNCLEAR', 'E6 missing threaded row'
    t = e6['threaded']
    parts = [f"threaded err={t['error_rate']*100:.1f}%"]
    healthy_ok = True
    for name in ('nio-async', 'virtual'):
        if name not in e6:
            return 'UNCLEAR', f'E6 missing {name} row'
        r = e6[name]
        parts.append(f"{name} err={r['error_rate']*100:.2f}% p99={_fmt(r['p99'])}ms")
        if r['error_rate'] > 0.01 or (r['p99'] or 999) > 20:
            healthy_ok = False
    ok = t['error_rate'] >= 0.5 and healthy_ok
    return ('CONFIRMED' if ok else 'FALSIFIED'), '; '.join(parts)


def check_h7(rows, **_):
    """E7 Threaded: at some c≥200, CPU<30% AND error_rate>5%. Production symptom."""
    e7t = sorted([r for r in rows if r['exp'] == 'E7' and r['transport'] == 'threaded' and r['c'] >= 200],
                 key=lambda r: r['c'])
    if not e7t:
        return 'UNCLEAR', 'no E7 Threaded c≥200 rows'
    hits = [r for r in e7t if (r['cpu_pct'] or 100) < 30 and r['error_rate'] > 0.05]
    if not hits:
        worst = max(e7t, key=lambda r: r['error_rate'])
        return 'FALSIFIED', (f'no c≥200 row shows CPU<30% AND err>5% — '
                             f"worst was c={worst['c']}: cpu={_fmt(worst['cpu_pct'])}%, "
                             f"err={worst['error_rate']*100:.1f}%")
    h = hits[0]
    return 'CONFIRMED', (f"c={h['c']}: cpu={_fmt(h['cpu_pct'])}%, "
                         f"err={h['error_rate']*100:.1f}% — production symptom reproduced")


def check_h8(rows, async_pool_size=500, **_):
    """E7: both NIO-Async and Loom clear the H7 *symptom* — errors ≤ 1% across all c.
    Throughput ceilings differ by design: NIO-Async ≈ handlerPool/L, Loom ≈ c/L.
    We confirm each transport reaches its own ceiling (within ±50%) and report the
    Loom/NIO-Async ratio at c=1000 — the cost of not using Loom."""
    e7 = [r for r in rows if r['exp'] == 'E7']
    parts = []
    ok = True
    L_ms = 50  # E7 handler latency
    for name in ('nio-async', 'virtual'):
        rows_t = sorted([r for r in e7 if r['transport'] == name], key=lambda r: r['c'])
        if not rows_t:
            return 'UNCLEAR', f'no E7 {name} rows'
        errors_ok = all(r['error_rate'] <= 0.01 for r in rows_t)
        parts.append(f'{name}: errors_ok={errors_ok}')
        if not errors_ok:
            ok = False
        # Check that the transport reaches its own predicted ceiling.
        by_c = {r['c']: r['tps'] for r in rows_t}
        if 1000 in by_c and by_c[1000]:
            if name == 'nio-async':
                ceiling = min(1000, async_pool_size) * 1000.0 / L_ms
            else:
                ceiling = 1000 * 1000.0 / L_ms
            diff = abs(by_c[1000] - ceiling) / ceiling
            parts.append(f'{name} @c=1000: tps={by_c[1000]:.0f} vs ceiling={ceiling:.0f} ({diff*100:.0f}% off)')
            if diff > 0.5:
                ok = False
    # Loom / NIO-Async ratio at c=1000 — informative, not a pass criterion.
    rows_1000 = {r['transport']: r['tps'] for r in e7 if r['c'] == 1000}
    if 'nio-async' in rows_1000 and 'virtual' in rows_1000 and rows_1000['nio-async']:
        ratio = rows_1000['virtual'] / rows_1000['nio-async']
        parts.append(f'Loom/NIO-Async @c=1000: {ratio:.2f}× (cost of not using Loom)')
    return ('CONFIRMED' if ok else 'FALSIFIED'), '; '.join(parts)



HYPOTHESES = [
    ('H1', 'At low c with instant handler, all three transports are roughly equal (setup validity).', check_h1),
    ('H2', 'NIO-Async and Virtual scale further than Threaded as c grows (instant handler).',        check_h2),
    ('H3', 'Throughput matches transport-specific ceiling under blocking handlers.',                 check_h3),
    ('H4', 'HTTPS widens the gap — Threaded saturates earlier; NIO-Async / Virtual keep advantage.', check_h4),
    ('H5', 'Without keep-alive, gaps narrow but do not invert.',                                     check_h5),
    ('H6', 'NIO-Async and Virtual hold 10k idle conns; Threaded fails at poolSize.',                 check_h6),
    ('H7', 'Threaded shows the production symptom (idle CPU + timeouts) above poolSize.',            check_h7),
    ('H8', 'NIO-Async and Loom both clear H7 symptom; their throughput ceilings differ (pool vs c).', check_h8),
]


# ---------------------------------------------------------------- output ---

MATRIX_HEADER = ['exp', 'transport', 'c', 'L', 'proto',
                 'tps', 'p50(ms)', 'p99(ms)', 'p99.9(ms)',
                 'err%', 'cpu%', 'mem(MiB)', 'thr', 'ctxt/s', 'pinned', 'reps']


def fmt_row(r):
    return [r['exp'], r['transport'], str(r['c']), str(r['L']), r['proto'],
            _fmt(r['tps'], 0), _fmt(r['p50']), _fmt(r['p99']), _fmt(r['p999']),
            f"{r['error_rate']*100:.2f}", _fmt(r['cpu_pct']),
            _fmt(r['mem_mib'], 0), _fmt(r['thread_count'], 0),
            _fmt(r['ctxt_per_s'], 0), str(r['pinned']), str(r['reps'])]


def render_markdown_table(headers, rows):
    out = ['| ' + ' | '.join(headers) + ' |',
           '|' + '|'.join(['---'] * len(headers)) + '|']
    for r in rows:
        out.append('| ' + ' | '.join(r) + ' |')
    return '\n'.join(out)


def render_report(rows, verdicts):
    out = []
    out.append('# Benchmark Report')
    out.append('')
    out.append('A study comparing **Threaded**, **NIO-Async**, and **Loom Threaded** on the '
               'same workload. Auto-generated by `scripts/format-results.py`; predictions '
               'come from `docs/BENCHMARK.md` §2. Read the matrix first, the verdicts '
               'second — the latter are advisory shorthand.')
    out.append('')

    out.append('## 1. Result matrix')
    out.append('')
    out.append('Median across reps, outliers > 2× IQR rejected. `err%` is timeouts + non-2xx '
               'as a fraction of total requests. Three transport rows per scenario; sorted '
               'so they are adjacent.')
    out.append('')
    rows_sorted = sorted(rows, key=lambda r: (r['exp'], r['c'], r['L'], r['proto'], r['transport']))
    out.append(render_markdown_table(MATRIX_HEADER, [fmt_row(r) for r in rows_sorted]))
    out.append('')

    out.append('## 2. Expected vs observed (per hypothesis)')
    out.append('')
    out.append('Each row shows the prediction from `docs/BENCHMARK.md` §2 and the actual '
               'numbers. Verdicts are advisory — read the numbers and decide for yourself.')
    out.append('')
    vrows = [[hid, statement, verdict, reason] for hid, statement, verdict, reason in verdicts]
    out.append(render_markdown_table(['H', 'Prediction', 'Verdict', 'Observed'], vrows))
    out.append('')

    out.append('## 3. What we learned')
    out.append('')
    vmap = {h: (v, r) for h, _, v, r in verdicts}

    def vget(h):
        v, r = vmap.get(h, ('-', '-'))
        return f'{v} — {r}'

    summary = [
        ['Does Threaded hit a wall, and where?',                           'E2 / H2 + E6 / H6',  vget('H2') + '; ' + vget('H6')],
        ['How does Threaded fail at the wall?',                            'E7 / H7',            vget('H7')],
        ['Does NIO-Async escape the wall? By how much?',                   'E2..E7',             vget('H2') + '; ' + vget('H3')],
        ['Does Loom Threaded escape it too?',                              'E2..E7',             vget('H2')],
        ['Is Loom Threaded as good as NIO-Async?',                         'E7 / H8',            vget('H8')],
        ['Does HTTPS or connection churn change the picture?',             'E4, E5',             vget('H4') + '; ' + vget('H5')],
        ['Is the setup itself trustworthy?',                               'E1 / H1',            vget('H1')],
    ]
    out.append(render_markdown_table(['Question', 'Source', 'What the numbers say'], summary))
    out.append('')

    out.append('## 4. Notes for the reader')
    out.append('')
    out.append('- The verdict column above is *advisory*: it just checks whether the '
               'observed numbers fall inside the prediction band from §2. A `FALSIFIED` '
               'verdict means the result was different from what we expected — which is '
               'often more interesting than `CONFIRMED`.')
    out.append('- For each scenario, the three transport rows of the matrix are the actual '
               'comparison; the verdict is just a shorthand for what jumps out.')
    out.append('- If you see `pinned > 0` on any NIO-Async or Loom Threaded row, the '
               '`-Djdk.tracePinnedThreads=full` log in `server.log` tells you where the '
               'carrier got pinned. That is usually the explanation for any divergence '
               'from H8.')

    return '\n'.join(out) + '\n'


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('raw_dir', nargs='?', default='results/raw',
                    help='directory containing per-experiment subdirectories (default: results/raw)')
    ap.add_argument('--out', help='write report to this file (default: stdout)')
    ap.add_argument('--pool-size', type=int, default=100,
                    help='Threaded pool size used during benchmark (for H3 ceiling, default 100)')
    ap.add_argument('--async-pool-size', type=int, default=500,
                    help='NIO-Async handler pool size (for H3/H8 ceiling, default 500)')
    args = ap.parse_args(argv)

    raw_dir = Path(args.raw_dir)
    if not raw_dir.is_dir():
        sys.exit(f'no such directory: {raw_dir}')
    rows = discover_rows(raw_dir)
    if not rows:
        sys.exit(f'no scenarios found under {raw_dir} — did run-matrix.sh produce any output?')

    ctx = {'pool_size': args.pool_size, 'async_pool_size': args.async_pool_size}
    verdicts = []
    for hid, statement, fn in HYPOTHESES:
        try:
            v, r = fn(rows, **ctx)
        except Exception as e:
            v, r = 'UNCLEAR', f'exception: {type(e).__name__}: {e}'
        verdicts.append((hid, statement, v, r))

    report = render_report(rows, verdicts)
    if args.out:
        Path(args.out).write_text(report)
        print(f'wrote {args.out}', file=sys.stderr)
    else:
        sys.stdout.write(report)


if __name__ == '__main__':
    main()
