#!/usr/bin/env python3
"""Appends a benchmark run to a time series and reports step changes in it.

The pull-request gate compares one commit against its base and catches a single large regression.
It is blind to the other shape of decay: many changes each a percent or two slower, none tripping a
threshold, together moving a budget. This looks at the series instead.

Deliberately simple: a shifted-median comparison between the two halves of a recent window, not a
statistical change-point library. The series is short, the runners are noisy, and a method whose
behaviour is obvious from reading it is worth more here than a method with better theoretical
properties that nobody can debug at 2am. It never fails the build.
"""
import json
import os
import statistics
import sys

# Enough points that a single anomalous run cannot move the verdict, few enough that a real shift is
# still visible within a few weeks of commits.
WINDOW = 12
MIN_POINTS = 6

# Larger than the pull-request warning threshold: this is looking for a sustained level shift, and a
# 5% band would fire on ordinary runner drift across weeks.
SHIFT_RATIO = 1.08

PERCENTILE = "95.0"


def load_series(path):
    if not os.path.exists(path):
        return {}
    with open(path) as handle:
        return json.load(handle)


def main():
    results_path, series_path, commit = sys.argv[1], sys.argv[2], sys.argv[3]

    with open(results_path) as handle:
        results = json.load(handle)

    series = load_series(series_path)
    for entry in results:
        name = entry["benchmark"]
        p95 = entry["primaryMetric"]["scorePercentiles"][PERCENTILE]
        series.setdefault(name, []).append({"commit": commit, "p95": p95})
        # Bounded: the history is a rolling window, not an archive. Artifacts hold the full record.
        series[name] = series[name][-100:]

    os.makedirs(os.path.dirname(series_path) or ".", exist_ok=True)
    with open(series_path, "w") as handle:
        json.dump(series, handle, indent=2)

    for name, points in sorted(series.items()):
        recent = [point["p95"] for point in points[-WINDOW:]]
        short = name.rsplit(".", 1)[-1]

        if len(recent) < MIN_POINTS:
            print(f"::notice::{short}: {len(recent)} points, need {MIN_POINTS} before judging a trend")
            continue

        half = len(recent) // 2
        earlier = statistics.median(recent[:half])
        later = statistics.median(recent[half:])
        ratio = later / earlier if earlier else float("inf")

        if ratio >= SHIFT_RATIO:
            print(
                f"::warning::{short}: sustained shift {earlier:.3f} -> {later:.3f} ms p95 "
                f"({ratio:.2f}x) over the last {len(recent)} runs"
            )
        else:
            print(f"::notice::{short}: stable at {later:.3f} ms p95 ({ratio:.2f}x over {len(recent)} runs)")


if __name__ == "__main__":
    main()
