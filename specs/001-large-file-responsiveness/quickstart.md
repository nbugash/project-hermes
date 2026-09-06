# Quickstart: Spike 001 — Large-File Editor Responsiveness

How to run this spike and confirm it answers its question. Every scenario below maps to a success
criterion in [spec.md](./spec.md); the entity shapes are in [data-model.md](./data-model.md) and
the protocol surface is in [contracts/](./contracts/).

This spike **succeeds** when every scenario passes and **fails** when any does not — and a failure
produces an ADR in `/docs/adr` proposing a constitutional amendment, never a workaround.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| JDK 25+ | Backend toolchain; Gradle resolves it via the toolchain block |
| Node LTS + npm | Frontend toolchain |
| The reference fixture | `fixtures/large-java-file/` — generated and checked in; nothing below is measurable without it |
| A fixed machine class for budgets | Benchmark numbers are only comparable within one runner class |

## Generate the fixture (once)

The fixture is deterministic and checked in. Regenerate it only when deliberately changing the
baseline — doing so voids every stored benchmark baseline (data-model, Reference Fixture).

```bash
./gradlew :fixtures:generateLargeJavaFile
sha256sum fixtures/large-java-file/Large.java   # must match the recorded contentHash
```

**Corpus caveat**: research measurements used a synthetic, uniform, ASCII-only file. Real Java with
deep generics, annotations, lambdas and text blocks may parse more slowly and recover differently.
Re-measure against a realistic file before locking any number as a CI baseline.

## Run the whole gate

What CI runs, in one command each. All must pass for the spike to be considered answered.

```bash
./gradlew build            # backend unit + LSP integration tests, ArchUnit rules
./gradlew jmh              # backend budget benchmarks (SC-004, SC-005a, SC-005b)
npm --prefix app test      # component tests
npm --prefix app run e2e   # Playwright smoke + shell budgets (SC-001a/b, SC-002, SC-003)
```

## Scenario 1 — Backend headless, no editor (SC-007)

The constitutional check that the backend is not secretly coupled to the shell.

```bash
./gradlew :server:integrationTest --tests '*HeadlessLargeFile*'
```

**Expected**: the test client opens the fixture over stdio, requests full semantic tokens, applies
single-character edits, requests deltas, and cancels superseded requests — with no Electron process
running. Any test needing the shell to pass indicates a Principle II violation.

## Scenario 2 — Backend budgets (SC-004, SC-005a, SC-005b)

```bash
./gradlew jmh
```

**Expected**:
- Full parse of the 50,000-line fixture completes under 500 ms (SC-005a).
- Full-document tokenization completes under 800 ms (SC-005b) — measured separately, since it is
  off the cold-start critical path.
- Single-character edit re-analysis completes under 5 ms at p95.
- Results are compared against the stored baseline; a regression fails the build (SC-008).

## Scenario 3 — Hexagonal boundary (Constitution Principle III)

```bash
./gradlew :server:test --tests '*ArchitectureRules*'
```

**Expected**: `server/core` imports none of Vert.x, lsp4j, jOOQ, SQLite or tree-sitter, and no
core-to-adapter dependency exists. A forbidden import fails the build. Because each adapter is its
own Gradle module, most violations fail at compile time before the rule even runs.

## Scenario 4 — Launch and cold start (SC-003)

```bash
npm --prefix app run dev -- fixtures/large-java-file/Large.java
```

**Expected, observed by hand and asserted in Playwright**:
- The window is visible and interactive well before the backend is ready, showing that language
  features are still starting.
- The file is displayed with Java highlighting and accepting input within 2 s of launch.
- The editor performs no filesystem read of the document — content arrives via `vega/readDocument`.

## Scenario 5 — Typing latency at three positions (SC-001a, SC-001b)

```bash
npm --prefix app run e2e -- --grep "keystroke latency"
```

**Expected**: sustained typing at line 1, line 25,000 and line 50,000 each hold handler-start to
committed frame under 16 ms at p95 (SC-001a) and drop at most one frame (SC-001b). Timings come from
in-page performance marks around the committed frame, not from harness wall-clock — the harness
reads the marks back. Frame counts come from a browser trace.

**What failure means**: this is the spike's central hypothesis. A miss here is the ADR case, not a
tuning exercise.

## Scenario 6 — Scroll (SC-002)

```bash
npm --prefix app run e2e -- --grep "scroll"
```

**Expected**: wheel, keyboard and scrollbar-drag traversal of the full file each stay within the
dropped-frame threshold, with no blank or unstyled region visible at any point, including regions
never previously visited. Frames are counted from a browser trace under synthetic gestures, not from
`requestAnimationFrame` deltas.

## Scenario 7 — Broken code (SC-006)

```bash
npm --prefix app run e2e -- --grep "unclosed brace"
```

**Expected**: inserting an unclosed brace at line 100 causes no visible re-highlighting beyond the
enclosing block; the editor stays responsive while the file is invalid; closing the brace restores
the previous highlighting.

**Known risk, now measured**: an unclosed brace really does restructure the parse tree globally —
top-level children collapse from 453 to 7, and the reported changed-range covers the whole file. The
criterion still holds because *highlighting* damage stays within roughly 40 lines, but only if
re-highlighting is narrowed by edit proximity and token-diffed rather than driven off the reported
changed ranges. See [research.md](./research.md) D12 and D13. A naive implementation fails this
scenario on the first `{` typed.

## Scenario 8 — Timing panel (SC-009)

Open the panel in a production-configuration build and type.

**Expected**: per-keystroke input-to-render and, where an interaction crossed the boundary,
round-trip time, plus rolling p50 and p95. Available without a developer flag or debug build. The
panel's own presence must not push typing out of budget — the e2e latency assertions run with the
panel open as well as closed.

## Scenario 9 — Save round-trip (SC-010)

```bash
./gradlew :server:integrationTest --tests '*SaveRoundTrip*'
```

**Expected**:
- An edited document saves to bytes matching the buffer, preserving original encoding, line-ending
  style and trailing-newline state.
- An unmodified document saves byte-identical to the original.
- A drifted mirror returns `mirror-mismatch` and writes nothing.
- A file changed on disk returns `disk-changed` and writes nothing.
- A failed write leaves the buffer intact and surfaces the reason.

## Interpreting the outcome

| Result | Action |
|--------|--------|
| All scenarios pass | The architecture holds for B1a, B1b and B3. Record the achieved numbers as the CI baseline. B2 remains unvalidated — completion was never exercised. |
| Any scenario fails | Write an ADR in `/docs/adr` proposing the amendment the failure implies. Do not move analysis into the editor process, weaken the protocol boundary, or quietly restate a budget. |
