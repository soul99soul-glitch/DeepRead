#!/usr/bin/env python3
"""Aggregate app-main-thread atrace sections after the fixture warmup marker.

Usage: scripts/analyze-chat-render-trace.py INPUT.ctrace OUTPUT.json
Durations are elapsed wall time (including scheduling), not sampled CPU time.
"""
import collections
import json
from pathlib import Path
import re
import sys
import zlib

raw = Path(sys.argv[1]).read_bytes()
trace = zlib.decompress(raw.split(b"TRACE:\n", 1)[1]).decode()
marker = next(line for line in trace.splitlines() if "|Amber fixture pinned" in line)
pid = re.search(r"B\|(\d+)\|", marker)[1]
start = float(re.search(r"(\d+\.\d+): tracing_mark_write:", marker)[1])
stack = []
sections = collections.defaultdict(lambda: [0, 0.0, 0.0])
for line in trace.splitlines():
    match = re.search(r"-(\d+)\s+.*?\s(\d+\.\d+): tracing_mark_write: (.*)", line)
    if not match or match[1] != pid:
        continue
    now, event = float(match[2]), match[3]
    if event.startswith("B|"):
        stack.append([event.split("|", 2)[-1], now, 0.0])
    elif event.startswith("E") and stack:
        name, begin, children = stack.pop()
        elapsed = now - begin
        if stack:
            stack[-1][2] += elapsed
        if begin >= start:
            row = sections[name]
            row[0] += 1
            row[1] += elapsed * 1000
            row[2] += (elapsed - children) * 1000
rows = sorted((dict(name=name, count=v[0], total_ms=round(v[1], 3),
                    self_ms=round(v[2], 3)) for name, v in sections.items()),
              key=lambda row: -row["self_ms"])
Path(sys.argv[2]).write_text(json.dumps(dict(main_pid=pid, sections=rows), indent=2) + "\n")
for row in rows[:12]:
    print(f"{row['name']}: n={row['count']} elapsed={row['total_ms']}ms self={row['self_ms']}ms")
