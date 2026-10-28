#!/usr/bin/env python3
"""Summarize alternating baseline/optimized fixture rounds.

Usage: scripts/summarize-chat-perf-pairs.py OUTPUT.json BASELINE_DIR OPTIMIZED_DIR [BASELINE_DIR OPTIMIZED_DIR ...]

Each directory is one chat-render-perf.sh run. Prints per-round and median
FrameMetrics percentiles, >32 ms share, gfxinfo jank and ART GC deltas.
"""
import json
import re
import statistics
import sys
from pathlib import Path

KNOWN_PROFILES = ("short", "chinese", "mixed", "history-cold", "long-unbroken")


def discover_profiles(pairs):
    names = None
    for baseline, optimized in pairs:
        for directory in (Path(baseline), Path(optimized)):
            found = {path.stem for path in directory.glob("*.json") if path.stem != "pairs-summary"}
            names = found if names is None else names & found
    if not names:
        return KNOWN_PROFILES[:3]
    return tuple(name for name in KNOWN_PROFILES if name in names) or tuple(sorted(names))


def percentile(values, p):
    values = sorted(values)
    return round(values[int((len(values) - 1) * p)], 3) if values else None


def frames(directory, profile):
    data = json.loads((directory / f"{profile}.json").read_text())
    assert data["settled"], f"{directory}/{profile} did not settle"
    values = data["framesMs"]
    gc = {}
    for key, after in data["gcAfter"].items():
        try:
            gc[key] = int(after) - int(data["gcBefore"][key])
        except (ValueError, TypeError, KeyError):
            pass
    gfx_text = (directory / f"{profile}-gfxinfo.txt").read_text()
    match = re.search(r"Total frames rendered: (\d+)\s+Janky frames: (\d+) \(([\d.]+)%\)", gfx_text)
    return {
        "refresh": float(re.search(r"refresh=([\d.]+)", data["report"]).group(1)),
        "source_chars": data["finalSourceChars"],
        "frames": len(values),
        "p50_ms": percentile(values, 0.50),
        "p95_ms": percentile(values, 0.95),
        "p99_ms": percentile(values, 0.99),
        "over_16_667_pct": round(100 * sum(v > 1000 / 60 for v in values) / len(values), 2),
        "over_32_pct": round(100 * sum(v > 32 for v in values) / len(values), 2),
        "gfxinfo_frames": int(match.group(1)) if match else None,
        "gfxinfo_janky_pct": float(match.group(3)) if match else None,
        "gc_count": gc.get("art.gc.gc-count"),
        "gc_time_ms": gc.get("art.gc.gc-time"),
        "blocking_gc_count": gc.get("art.gc.blocking-gc-count"),
        "blocking_gc_time_ms": gc.get("art.gc.blocking-gc-time"),
        "bytes_allocated": gc.get("art.gc.bytes-allocated"),
    }


def median(values):
    values = [v for v in values if v is not None]
    return round(statistics.median(values), 3) if values else None


def main(output, pairs):
    profiles = discover_profiles(pairs)
    rounds = {"baseline": [], "optimized": []}
    for index, (baseline, optimized) in enumerate(pairs, 1):
        rounds["baseline"].append({p: frames(Path(baseline), p) for p in profiles})
        rounds["optimized"].append({p: frames(Path(optimized), p) for p in profiles})
    medians = {}
    for side in rounds:
        medians[side] = {}
        for profile in profiles:
            medians[side][profile] = {
                key: median([r[profile][key] for r in rounds[side]])
                for key in ("p50_ms", "p95_ms", "p99_ms", "over_32_pct",
                            "gfxinfo_janky_pct", "gc_count", "gc_time_ms",
                            "blocking_gc_count", "blocking_gc_time_ms", "bytes_allocated", "frames")
            }
    result = {"profiles": list(profiles), "rounds": rounds, "medians": medians}
    Path(output).write_text(json.dumps(result, indent=2) + "\n")
    for profile in profiles:
        print(profile)
        for side in ("baseline", "optimized"):
            m = medians[side][profile]
            print(f"  {side:9s} median p50={m['p50_ms']} p95={m['p95_ms']} p99={m['p99_ms']} "
                  f">32={m['over_32_pct']}% janky={m['gfxinfo_janky_pct']}% "
                  f"gc={m['gc_count']}/{m['gc_time_ms']}ms alloc={m['bytes_allocated']}B")
        print("  per-round p95: baseline", [r[profile]["p95_ms"] for r in rounds["baseline"]],
              "optimized", [r[profile]["p95_ms"] for r in rounds["optimized"]])


if __name__ == "__main__":
    if (len(sys.argv) - 2) % 2 or len(sys.argv) < 4:
        raise SystemExit(__doc__)
    args = sys.argv[2:]
    main(sys.argv[1], list(zip(args[0::2], args[1::2])))
