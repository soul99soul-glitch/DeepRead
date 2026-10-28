#!/usr/bin/env python3
"""Summarize paired fixture JSON/gfxinfo; optional perfetto Python package for traces.
Usage: python scripts/summarize-chat-perf.py BASELINE_DIR OPTIMIZED_DIR OUTPUT_JSON
"""
import json
import re
import sys
from pathlib import Path


def percentile(values, p):
    values = sorted(values)
    return round(values[int((len(values) - 1) * p)], 3) if values else None


def frame_summary(values):
    return dict(frames=len(values), p50_ms=percentile(values, .5),
                p95_ms=percentile(values, .95), p99_ms=percentile(values, .99),
                over_16_667_pct=round(100 * sum(v > 1000 / 60 for v in values) / len(values), 2),
                over_32_pct=round(100 * sum(v > 32 for v in values) / len(values), 2)) if values else {}


def trace_summary(path):
    from perfetto.trace_processor import TraceProcessor
    with TraceProcessor(trace=str(path)) as tp:
        markers = list(tp.query("""select s.name,s.ts,t.utid from slice s
          join thread_track tt on s.track_id=tt.id join thread t using(utid)
          where s.name glob 'Amber fixture*' order by s.ts"""))
        first = next(r for r in markers if r.name == 'Amber fixture pinned')
        last = next(r for r in markers if r.name == 'Amber fixture history')
        start, end, utid = first.ts, last.ts, first.utid
        # Clip each scheduler interval to the exact pinned-through-drain window.
        states = list(tp.query(f"""select state,count(*) n,
          sum(min(ts+dur,{end})-max(ts,{start}))/1e6 ms,
          max(min(ts+dur,{end})-max(ts,{start}))/1e6 max_ms
          from thread_state where utid={utid} and dur>0 and ts<{end}
          and ts+dur>{start} group by state"""))
        slices = list(tp.query(f"""select s.name,count(*) n,
          sum(min(s.ts+s.dur,{end})-max(s.ts,{start}))/1e6 inclusive_ms,
          max(min(s.ts+s.dur,{end})-max(s.ts,{start}))/1e6 max_ms
          from slice s join thread_track tt on tt.id=s.track_id
          where tt.utid={utid} and s.dur>0 and s.ts<{end} and s.ts+s.dur>{start}
          and s.name in ('traversal','TextAnnotatedStringNode:measure','TextLayout:initLayout',
            'AndroidOwner:measureAndLayout','AndroidOwner:draw','Recomposer:recompose')
          group by s.name"""))
        duration_ms = (end - start) / 1e6
        return dict(window_ms=round(duration_ms, 3), main_thread_states={r.state: dict(
            count=r.n, ms=round(r.ms, 3), percent=round(100 * r.ms / duration_ms, 2),
            max_ms=round(r.max_ms, 3)) for r in states},
            main_slices={r.name: dict(count=r.n, inclusive_ms=round(r.inclusive_ms, 3),
                max_ms=round(r.max_ms, 3)) for r in slices})


def summarize(directory):
    output = {}
    for profile in ('short', 'chinese', 'mixed'):
        data = json.loads((directory / f'{profile}.json').read_text())
        assert data['settled'] and data['finalSourceChars'] > 0
        gc = {}
        for key, after in data['gcAfter'].items():
            try:
                gc[key] = int(after) - int(data['gcBefore'][key])
            except (ValueError, TypeError, KeyError):
                pass
        gfx = (directory / f'{profile}-gfxinfo.txt').read_text()
        match = re.search(r'Total frames rendered: (\d+)\s+Janky frames: (\d+) \(([\d.]+)%\)', gfx)
        item = dict(source_chars=data['finalSourceChars'], settled=True,
                    frames=frame_summary(data['framesMs']),
                    phases={k: frame_summary(v) for k, v in data['phaseFramesMs'].items()},
                    unknown_delay_p95_ms=percentile(data['unknownDelayMs'], .95), gc_delta=gc,
                    gfxinfo=dict(frames=int(match[1]), janky=int(match[2]), janky_pct=float(match[3])) if match else {})
        try:
            item['trace'] = trace_summary(directory / f'{profile}.ctrace')
        except ImportError:
            item['trace'] = 'Install perfetto Python package to summarize local scheduler traces.'
        output[profile] = item
    return output


if __name__ == '__main__':
    baseline, optimized, output = map(Path, sys.argv[1:])
    result = dict(baseline=summarize(baseline), optimized=summarize(optimized))
    output.write_text(json.dumps(result, indent=2) + '\n')
    for profile in ('short', 'chinese', 'mixed'):
        print(profile)
        for label in result:
            value = result[label][profile]
            print(label, value['frames'], 'gfxinfo', value['gfxinfo'], 'GC', value['gc_delta'])
