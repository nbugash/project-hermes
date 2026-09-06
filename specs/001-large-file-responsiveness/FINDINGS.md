# Spike 001 — Findings

**Date**: 2026-09-06 · **Platform**: Linux x86_64, Java 25, Electron 33, tree-sitter 0.26.13 core /
0.23.5 Java grammar, natives built `-O3 -DNDEBUG`

**The spike's question was whether a JVM backend and an Electron editor can hold interactive budgets
on a 50,000-line Java file. They can.** Every success criterion that could be measured on this
platform is met. What follows is the evidence, the things that turned out to be false along the way,
and what remains unknown.

---

## Criteria

| Criterion | Budget | Measured | Verdict |
|---|---|---|---|
| SC-001a keystroke to committed frame | 16 ms p95 | 22–31 ms p95 software-rendered | **Not judgeable here** — see below |
| SC-002 scroll smoothness | no dropped-frame breach | 0 long frames sustained; 1 on a full-document jump | **PASS** |
| SC-003 timing panel ships | reachable without a developer flag | verified with none set | **PASS** |
| SC-004 single-character re-analysis | 5 ms p95 | 2.2 ms full keystroke path (after T107) | **PASS** |
| SC-005a cold open | 500 ms | 170–242 ms full parse | **PASS** |
| SC-005b full-document tokenization | 800 ms | 137 ms p50 / 195 ms p95 | **PASS** |
| SC-006 broken code degrades locally | local damage, full recovery | 2.16 ms p95 while invalid vs 2.21 healthy | **PASS** |

**SC-001a is unresolved rather than failed.** Every measurement here ran under `xvfb` with no GPU,
where Chromium falls back to software compositing and inflates every frame. Asserting a 16 ms budget
on that number would be meaningless, and loosening the budget so it passed would be worse — it would
quietly redefine the criterion. The assertion is gated behind `VEGA_PERF_GATE=1` for real hardware.
What *is* meaningful here, and holds: **zero dropped frames at line 1 and at line 50,038**, with the
panel open and with the document syntactically invalid.

---

## The central result

Incremental reparse of a 50,000-line file costs **2.9 ms p95 at the worst of three edit positions**,
against a 5 ms budget. Position does not matter — 2.9, 2.9 and 2.9 ms at the start, middle and end —
which is the property the whole incremental design exists to produce.

The full per-keystroke path, including updating the backend's mirror, measures **2.208 ms p95** after
T107 removed the two whole-document copies. Before that change it measured 4.16–5.50 ms across runs
and straddled the budget; it now sits inside it with 56% headroom, and the p99.9 tail fell from
25.4 ms to 3.7 ms.

---

## Four things that were false

**1. "SC-004 fails."** The first measurement said 6.2 ms p95 and looked like a miss. It was measured
on a synthetic fixture that nests 1,270 types inside a single outer class. Real Java is shallow, and
on a real corpus the same operation costs half as much despite the file being 25% larger and
re-reading 70× more source. Structure dominates, not size. ADR-0002 records the confirmation.

**2. "Research's 0.70 ms figure was achievable."** It was measured on a uniform, ASCII-only synthetic
file. Neither that number nor the 6.2 ms one describes real Java; the truth is between them, and both
fixtures are retained — the synthetic one as a deliberate worst case.

**3. "The measurements were measuring keystrokes."** Every real-corpus keystroke benchmark anchored
its edit on `lastIndexOf("return ")`, which in this corpus lands inside a javadoc comment. Edits in
comments are structurally insignificant. Found only because the error-state benchmark's guard refused
to run — garbage inserted into a comment is still valid Java. Re-anchoring on real code did not change
the verdict, which is itself worth knowing.

**4. "Error recovery would be the expensive case."** It costs nothing measurable: 4.33 ms p95 with a
long-lived unmatched brace against 4.20 ms healthy, and typing while invalid drops no frames.

---

## Defects found

Each of these was silent — no exception, no failed test, until something was built that could see it.

| Defect | How it presented |
|---|---|
| `reparse` consumes the tree it is given | Reading the old tree afterwards returned offsets shifted by the edit |
| Renderer never sent `didOpen`/`didChange` | Both ends built, no wire between them; found via a refused save |
| Every keystroke re-read the file from disk | An effect depending on inline callbacks re-ran per render, resetting the mirror mid-edit |
| Protocol handshake could never succeed | Version read from the wrong level of the initialize result |
| `startListening()` called twice | Two readers splitting the message stream; the distribution was broken while every in-process test passed |
| Shutdown hook built Vert.x during shutdown | `IllegalStateException: Shutdown in progress` |
| CLI file and backend status pushed before listeners existed | Messages silently lost; fixed by making both pullable |
| Editor container had no height | Monaco rendered one line — an editor that looks empty rather than broken |
| CSP blocked Monaco's workers | Tokenization silently moved to the main thread, which would corrupt the latency it was there to measure |
| Backend stderr discarded | Logs and stack traces vanished; an unread pipe also eventually blocks the writer |
| Sandboxed preload cannot be ESM | The bridge silently never loaded |
| Non-UTF-8 files decoded as UTF-8 | Would have corrupted such a file on its first save |

---

## What is not known

- **macOS and Windows are entirely unverified.** Native packaging, library loading, and every timing
  figure. See `docs/tech-radar.md`.
- **SC-001a needs GPU-accelerated hardware** to be judged at all.
- ~~Two whole-document copies per keystroke remain~~ **Fixed (T107, ADR-0004).** Keystroke p95 fell
  from 4.202 ms to 2.208 ms and the p99.9 tail from 25.4 ms to 3.7 ms. ADR-0003 had deferred this on
  an estimate that counted only the copying cost and missed the allocation pressure behind the tail;
  the reversal and what it teaches are recorded in ADR-0004.
- **The corpus is assembled**, not one natural 50,000-line file: real Java with `package` and `import`
  stripped, so it parses but does not compile. Sufficient for parsing measurements, misleading for
  anything else. It also happens to contain no non-ASCII, so the UTF-8/UTF-16 translation is covered
  by unit tests rather than by the corpus.

---

## Verification

198 JVM tests, 95 TypeScript tests, 11 Playwright specs, four JMH benchmarks. ArchUnit enforces the
hexagonal boundary and was checked by deliberately introducing a violation. The performance gate
compares a pull request against its base **back-to-back on one runner**, because research measured
hosted-runner variance as enough to trip an absolute 2% gate about half the time.
