#!/usr/bin/env python3
"""Compares two JMH result files measured back-to-back on one runner.

Relative, never absolute. Absolute thresholds fail on runner variance alone -- research D9 measured
hosted-runner noise as enough to trip a 2% gate about half the time -- so the base commit is
re-measured on the same machine minutes before the candidate and the two are compared to each other.

Two tiers, because one threshold cannot be both quiet and useful:
  * 5% slower  -> warn. Visible in the log, does not block.
  * 10% slower -> fail. Large enough to be a real regression rather than noise.
"""
import json
import sys

WARN_RATIO = 1.05
FAIL_RATIO = 1.10

# p95 is what the budgets are stated in, so it is what the gate compares. A mean would hide exactly
# the tail behaviour the criteria are about.
PERCENTILE = "95.0"


def p95_by_benchmark(path):
    with open(path) as handle:
        return {
            entry["benchmark"]: entry["primaryMetric"]["scorePercentiles"][PERCENTILE]
            for entry in json.load(handle)
        }


def main():
    base = p95_by_benchmark(sys.argv[1])
    candidate = p95_by_benchmark(sys.argv[2])

    failed = False
    for name in sorted(candidate):
        if name not in base:
            print(f"::notice::{name} is new; nothing to compare against")
            continue

        before, after = base[name], candidate[name]
        ratio = after / before if before else float("inf")
        short = name.rsplit(".", 1)[-1]
        summary = f"{short}: {before:.3f} -> {after:.3f} ms p95 ({ratio:.2f}x)"

        if ratio >= FAIL_RATIO:
            print(f"::error::{summary} exceeds the {FAIL_RATIO:.0%} regression threshold")
            failed = True
        elif ratio >= WARN_RATIO:
            print(f"::warning::{summary} exceeds the {WARN_RATIO:.0%} warning threshold")
        else:
            print(f"::notice::{summary}")

    # A benchmark that disappears is reported but does not fail the build: deleting a benchmark is a
    # deliberate act, and blocking on it would make removing dead measurement code painful.
    for name in sorted(set(base) - set(candidate)):
        print(f"::warning::{name.rsplit('.', 1)[-1]} was measured on the base but not the candidate")

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
