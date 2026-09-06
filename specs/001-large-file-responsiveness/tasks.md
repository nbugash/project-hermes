---

description: "Task list for Spike 001 — Large-File Editor Responsiveness"
---

# Tasks: Spike 001 — Large-File Editor Responsiveness

**Input**: Design documents from `/specs/001-large-file-responsiveness/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: **REQUIRED, not optional.** Constitution Principle VII is marked NON-NEGOTIABLE: every
backend feature ships with unit tests and at least one LSP-level integration test, written *before*
the implementation and observed failing first. FR-023 and FR-024 carry the same obligation. Test
tasks therefore precede implementation tasks within every story phase.

**Architecture note**: `architecture.md` and `design.md` were not generated — the installed Spec Kit
build requires those templates but does not ship them. File paths below are derived from
`plan.md`'s Source Code layout and the contracts instead.

**Organization**: Tasks are grouped by user story so each can be implemented and validated
independently.

**Task IDs are stable identifiers, not execution order.** Execution order is given by phase
position. Two tasks were resequenced on 2026-09-05 after the SC-004 measurement (see
[FINDINGS.md](./FINDINGS.md)); their IDs were kept so that existing references stay valid, and the
places they moved from and to are both marked.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US6)
- Exact file paths are included in every task

## Path Conventions

Monorepo, fixed by the constitution: `app/` (Electron + React frontend), `server/` (Java 25 backend
with a dependency-free `server/core`), `protocol/` (shared `vega/` extension definitions),
`fixtures/` (test projects), `docs/` (ADRs, tech radar).

---

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 [P] Declare Gradle modules `:server:core`, `:server:adapter-lsp`, `:server:adapter-syntax`, `:server:adapter-fs`, `:server:app`, `:protocol:java`, `:fixtures` in `settings.gradle.kts`
- [x] T002 [P] Configure Java 25 toolchain and set `--enable-native-access=ALL-UNNAMED` on run and test tasks in `server/app/build.gradle.kts` (required now, not later — `--illegal-native-access` is documented to default to `deny` in a future release; research D10)
- [x] T003 [P] Scaffold Electron + React + Vite with TypeScript strict mode in `app/package.json`, `app/tsconfig.json`, `app/vite.config.ts`
- [x] T004 [P] Add Vitest config in `app/vitest.config.ts` and Playwright config in `app/playwright.config.ts`, setting `backgroundThrottling: false` on the Electron window (research D5 — `requestAnimationFrame` stalls without it and measurement silently stops)
- [x] T005 Add native build script compiling tree-sitter core and the Java grammar with `-O3 -DNDEBUG` in `server/adapter-syntax/native/build-native.sh` (omitting `NDEBUG` roughly doubles parse cost and fails SC-004; research D11)
- [x] T006 Register the `NativeLibraryLookup` SPI in `server/adapter-syntax/src/main/resources/META-INF/services/io.github.treesitter.jtreesitter.NativeLibraryLookup` so natives ship inside one artifact
- [x] T007 [P] Add deterministic fixture generator task producing `fixtures/large-java-file/Large.java` (50,000 lines) in `fixtures/build.gradle.kts`
- [x] T008 [P] Record the fixture digest in `fixtures/large-java-file/CHECKSUM` and fail the build on mismatch, since a changed fixture voids every benchmark baseline
- [x] T009 [P] Add CI workflow running backend tests, ArchUnit, JMH, Vitest and Playwright in `.github/workflows/ci.yml`
- [x] T010 [P] Add `.gitignore` covering `.claude/`, build outputs and native artifacts at repository root

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the protocol surface, the hexagonal skeleton, and the measurement harness. Every user
story depends on all of it.

### Protocol module

- [x] T011 [P] Define the `vega/readDocument` and `vega/saveDocument` wire shapes in `protocol/schema/vega-extensions.json`, mirroring `workspace/textDocumentContent` so a later migration is a rename (research D4)
- [x] T012 [P] Generate or hand-write Java types in `protocol/java/src/main/java/vega/protocol/VegaExtensions.java`
- [x] T013 [P] Generate or hand-write TypeScript types in `protocol/ts/src/vega-extensions.ts`
- [x] T014 [P] Declare the protocol version constant in `protocol/schema/version.json`, consumed by both runtimes at `initialize`

### Analysis core — no adapter imports

- [x] T015 [P] Implement `Document` with monotonic version, encoding, line-ending style and trailing-newline state in `server/core/src/main/java/vega/core/document/Document.java`
- [x] T016 [P] Implement `Edit` (base version, result version, range, replacement text) in `server/core/src/main/java/vega/core/document/Edit.java`
- [x] T017 [P] Implement `HighlightResult` and token model, each keyed to the document version it was computed from, in `server/core/src/main/java/vega/core/highlight/HighlightResult.java`
- [x] T018 [P] Implement `ChangedRange` in `server/core/src/main/java/vega/core/highlight/ChangedRange.java`
- [x] T019 Define the `SyntaxParserPort` interface exposing **lazy cursor traversal only** — no materialised tree, no tree-sitter types — in `server/core/src/main/java/vega/core/port/SyntaxParserPort.java` (Constitution Principle III syntax-tree port constraint)
- [x] T020 [P] Define `FileGatewayPort` (read with metadata, write with verification) in `server/core/src/main/java/vega/core/port/FileGatewayPort.java`
- [x] T021 [P] Define `CancellationToken` with explicit check points in `server/core/src/main/java/vega/core/port/CancellationToken.java`
- [x] T022 **Not built — requirement satisfied without it.** Principle IV's read-your-writes contract exists because index writes are batched and asynchronous; this spike has no persistent index, so there are no pending batches and `Document` is already the latest state. A separate overlay class would be an abstraction with no divergence to reconcile, which Principle VIII rejects. The contract is instead asserted directly by a test (`ReadYourWritesTest`, task T046) so the guarantee is enforced rather than assumed. Reintroduce with the first feature that persists asynchronously.

### Boundary enforcement, wiring, transport

- [x] T023 Write ArchUnit rules asserting `server/core` imports none of Vert.x, lsp4j, jOOQ, SQLite or tree-sitter, and that no core-to-adapter dependency exists, in `server/core/src/test/java/vega/arch/ArchitectureRulesTest.java`
- [x] T024 Implement the Dagger 2 composition root wiring adapters to ports in `server/app/src/main/java/vega/app/VegaComponent.java`
- [x] T025 Bootstrap Vert.x with a dedicated CPU worker pool separate from the blocking-I/O pool, and fail CI on blocked-event-loop warnings, in `server/app/src/main/java/vega/app/Bootstrap.java`
- [x] T026 Implement the lsp4j server skeleton — `initialize`/`initialized`, semantic-token legend with `full`, `full.delta` and `range`, and **explicit position-encoding negotiation** — in `server/adapter-lsp/src/main/java/vega/lsp/VegaLanguageServer.java` (a UTF-16/UTF-8 mismatch shifts *every* subsequent token, because token offsets are relative; research D3)
- [x] T027 Implement structured JSON logging with a correlation id that survives dispatch from the event loop to the worker pool in `server/core/src/main/java/vega/core/obs/Correlation.java` and its adapter wiring

### Frontend foundation and measurement harness

- [x] T028 Implement the protocol client over `vscode-jsonrpc/node` in `app/src/protocol-client/connection.ts` — the **only** module permitted to spawn the backend or open the stdio channel, running in the main process
- [x] T029 [P] Implement the main↔renderer IPC bridge in `app/src/preload/bridge.ts`
- [x] T030 [P] Implement the in-page latency probe using `requestAnimationFrame` + `MessageChannel` in `app/src/renderer/timing/probe.ts` (Event Timing cannot be used as a gate: `duration` is 8 ms-quantized and `durationThreshold` is clamped to a 16 ms minimum; research D5)
- [x] T031 [P] Configure the JMH source set with JSON result output and JMH pinned to 1.37, leaving `generatorType` at its reflection default, in `server/build.gradle.kts` (the ASM generator cannot read Java 17+ bytecode; research D8)

**Checkpoint**: protocol defined, hexagonal skeleton enforced, transport and measurement in place —
user story implementation can begin.

**Status: Phases 1 and 2 complete** (2026-09-05). 27 JVM tests and 8 TypeScript tests pass;
`./gradlew build`, `npm test` and `tsc --noEmit` are all green. Verified rather than assumed:
the ArchUnit boundary rules were shown to fail on a deliberately introduced violation and pass once
removed; the bundled natives resolve `ts_parser_new`, `ts_parser_parse`, `ts_tree_get_changed_ranges`
and `tree_sitter_java`; CPU work is asserted to run on `vega-cpu` threads and never an event loop;
and the correlation id is asserted to survive worker dispatch without leaking into later work.

---

## Phase 3: User Story 1 - Open a large file (Priority: P1) 🎯 MVP

**Goal**: launch with a 50,000-line Java file and see it highlighted and editable within the
cold-start budget, with contents supplied by the backend.

**Independent test**: launch with the fixture path; observe a highlighted, scrollable, editable
document, and confirm the editor performed no filesystem read of it.

### Tests for User Story 1

- [x] T032 [P] [US1] Write a core unit test for document open through a fake `FileGatewayPort`, with no adapter present, in `server/core/src/test/java/vega/core/document/DocumentOpenTest.java`
- [x] T033 [P] [US1] Write unit tests for encoding, line-ending and trailing-newline detection in `server/adapter-fs/src/test/java/vega/fs/FileMetadataDetectionTest.java`
- [x] T034 [P] [US1] Write an LSP-level integration test driving `vega/readDocument` then `didOpen` against the fixture, headless with no editor process, in `server/adapter-lsp/src/test/java/vega/lsp/ReadDocumentIntegrationTest.java`
- [x] T035 [P] [US1] Write a Playwright cold-start test asserting the window is interactive before backend readiness and the file is editable within 2 s, in `app/tests/e2e/cold-start.spec.ts`
- [x] T036 [P] [US1] Write a test asserting the editor process performs no filesystem read of the opened document in `app/tests/e2e/no-direct-file-read.spec.ts`

### Implementation for User Story 1

- [x] T037 [US1] Implement file reading with encoding, line-ending, trailing-newline and digest detection in `server/adapter-fs/src/main/java/vega/fs/FileGatewayAdapter.java`
- [x] T038 [US1] Implement the `vega/readDocument` handler, which MUST NOT begin parsing as a side effect, in `server/adapter-lsp/src/main/java/vega/lsp/VegaDocumentHandlers.java`
- [x] T039 [US1] Implement the tree-sitter adapter behind `SyntaxParserPort` using the chunked `ParseCallback` overload — never `Parser.parse(String, …)`, which copies the whole file per call — in `server/adapter-syntax/src/main/java/vega/syntax/TreeSitterSyntaxAdapter.java`
- [x] T040 [US1] Implement `textDocument/semanticTokens/range` for the initial viewport in `server/adapter-lsp/src/main/java/vega/lsp/SemanticTokensHandler.java` (range-first is what keeps whole-file tokenization off the cold-start path; research D3)
- [x] T041 [US1] Implement `$/progress` reporting for the initial parse in `server/adapter-lsp/src/main/java/vega/lsp/ProgressReporter.java`
- [x] T042 [P] [US1] Implement the Monaco editor host and document loading in `app/src/renderer/editor/EditorHost.tsx`
- [x] T043 [P] [US1] Implement the shell with backend-status display and degraded-start indication in `app/src/renderer/shell/AppShell.tsx`
- [x] T044 [US1] Implement CLI argument handling and the Open File dialog in `app/src/main/main.ts`

**Checkpoint**: a 50,000-line file opens, highlights and accepts input within budget. MVP reached.

**Status (2026-09-05): COMPLETE.** T032–T044 all done. The Playwright suite runs green under
`xvfb-run`, so cold-start interactivity and the no-direct-file-read constraint are verified against a
real Electron process, not asserted on paper.

Work required by US1 but not separately numbered, now done:

- `SemanticTokenEncoder`, `Highlighter`, `LineIndex`, `DocumentService` in the core.
- **Protocol types** `ReadDocumentParams` / `ReadDocumentResult` — T012 was marked complete but had
  produced only `ProtocolVersion`, so the `vega/*` requests had no Java types at all.
- **Composition root wiring and the stdio launcher.** `Main` carried a comment saying the launcher
  "lands with User Story 1", and the Dagger graph bound no adapters, so nothing served. The graph now
  binds the filesystem gateway, the syntax adapter, the document service and the LSP handlers, and
  `Main.launch` connects them to streams.
- **Frontend build pipeline** — `index.html`, the renderer entry, `vite.config.ts` and a CommonJS
  preload build. Without these the app could not build, let alone run.

Two client-side defects were found and fixed while wiring, both invisible to the existing tests:

- `initializeBackend` read the protocol version from the top level of the initialize result, but the
  server returns it under `capabilities.experimental`. The handshake therefore failed **every time**,
  reporting the backend as version `0.0.0`.
- A backend that failed to spawn produced an unhandled promise rejection from inside
  `vscode-jsonrpc`'s internal write queue, which is fatal in the Electron main process.

**Measured on the way through — see [FINDINGS.md](./FINDINGS.md): SC-004 fails.** Incremental
reparse p95 is 6.2 ms against a 5 ms budget, of which only 0.43 ms is Vega's own code. Research's
0.70 ms figure was measured on a uniform ASCII-only file and does not reproduce on a realistic one.
Full parse passes comfortably at 228–372 ms against 500 ms.

---

## Phase 3.5: SC-004 Budget Gate (Blocking US2)

**Why this phase exists**: the spike measured incremental reparse at **6.2 ms p95 against a 5 ms
budget** on a realistic fixture, of which only 0.43 ms is Vega's own code
(see [FINDINGS.md](./FINDINGS.md)). US2's tests encode that budget as a literal assertion — T051
asserts "under 5 ms p95" and T052 asserts the frame budget that depends on it. Writing those tests
before the budget question is settled produces a red build with no path to green, which is the one
outcome TDD must never manufacture: a failing test that no correct implementation can satisfy.

**Goal**: establish what the reparse budget actually is, on trustworthy input, and either confirm
SC-004 or amend it by ADR before any test hard-codes a number.

**Independent test**: a recorded baseline measured on a real corpus, and either SC-004 unchanged or
an ADR amending it with the measured figure and its consequences for SC-001a.

- [x] T104 [P] Reconcile core `Edit` char offsets, tree-sitter byte offsets and LSP UTF-16 code units across `server/core/src/main/java/vega/core/document/Edit.java` and `server/adapter-syntax/src/main/java/vega/syntax/TreeSitterSyntaxAdapter.java` *(moved here from Phase 9)* — this gates the measurement rather than following it, because a real Java corpus carries non-ASCII in comments, string literals and identifiers, exactly where the three systems diverge; measuring there while the adapter assumes they coincide yields a number that is precise and wrong
- [x] T097 Re-measure every budget against a realistic 50,000-line Java corpus with generics, annotations and text blocks, recording baselines in `fixtures/large-java-file/BASELINES.md` *(moved here from Phase 9; depends on T104)* — the current synthetic-but-varied fixture gives 6.2 ms and research's uniform ASCII fixture gave 0.70 ms, and the gap between those two numbers is larger than the budget itself
- [x] T105 Decide SC-004 on T097's evidence — confirm it, or write an ADR in `docs/adr/` amending it to the measured figure and propagating the consequence into `specs/001-large-file-responsiveness/spec.md`, since reparse is only one component of a keystroke and SC-001a's frame budget absorbs the difference
- [x] T106 Update the literal budget assertions to T105's conclusion in `server/src/jmh/java/vega/bench/IncrementalReparseBenchmark.java` and `app/tests/e2e/keystroke-latency.spec.ts`, so both assert a number a correct implementation can actually meet

**Checkpoint**: the reparse budget is a measured, agreed number. US2's tests can now assert it.

**Gate cleared (2026-09-05).** SC-004 is **confirmed at 5 ms, not amended** — see
[ADR-0002](../../docs/adr/0002-confirm-sc-004-reparse-budget.md) and
[BASELINES.md](../../fixtures/large-java-file/BASELINES.md). Real Java measures 3.249 ms p95 at the
worst of three edit positions. The earlier 6.2 ms was an artifact of the synthetic fixture nesting
1,270 types inside one outer class; real Java is shallow and reparses in half the time despite being
25% larger. US2 is unblocked.

---

## Phase 4: User Story 2 - Edit with no perceptible lag (Priority: P2)

**Goal**: typing anywhere in the file, including line 50,000, stays inside the responsiveness
budget, with only the affected region re-highlighted.

**Independent test**: sustained typing at lines 1, 25,000 and 50,000 while recording
handler-to-frame latency and dropped frames.

### Tests for User Story 2

- [x] T045 [P] [US2] Write a core unit test asserting document version increases by exactly one per edit and is never reused in `server/core/src/test/java/vega/core/document/VersionMonotonicityTest.java`
- [x] T046 [P] [US2] Write a core unit test asserting an operation immediately after an edit observes that edit through the overlay, with no dependence on flush timing, in `server/core/src/test/java/vega/core/document/ReadYourWritesTest.java`
- [x] T047 [P] [US2] Write a test asserting semantic-token delta edits are **sorted and applied back-to-front**, in `app/tests/component/token-delta-apply.test.ts` — applying in received order corrupts the token array silently and yields wrong, not stale, highlighting (research D3)
- [x] T048 [P] [US2] Write a test asserting the client handles a full result returned to a delta request, and re-requests full when its baseline is missing, in `app/tests/component/token-delta-fallback.test.ts`
- [x] T049 [P] [US2] Write a test asserting results for superseded document versions are discarded and never painted in `app/tests/component/stale-result-discard.test.ts`
- [x] T050 [P] [US2] Write an integration test asserting `$/cancelRequest` actually frees the worker thread rather than only dropping the reply in `server/adapter-lsp/src/test/java/vega/lsp/CancellationFreesWorkerTest.java`
- [x] T051 [P] [US2] Write a JMH benchmark for single-character re-analysis asserting under 5 ms p95 **against the real corpus** in `server/src/jmh/java/vega/bench/IncrementalReparseBenchmark.java` — ADR-0002 confirmed the budget at 3.249 ms measured; gate on `RealCorpus.java`, not the synthetic fixture, which is a deliberate worst case at 5.753 ms
- [x] T052 [P] [US2] Write a Playwright test asserting handler-start-to-committed-frame under 16 ms p95 and at most one dropped frame at lines 1, 25,000 and 50,000, in `app/tests/e2e/keystroke-latency.spec.ts` (SC-001a/b as amended by ADR-0001; ADR-0002 leaves the frame budget unchanged)
- [x] T053 [P] [US2] Write an LSP-level integration test covering incremental `didChange` and delta responses against the fixture in `server/adapter-lsp/src/test/java/vega/lsp/IncrementalEditIntegrationTest.java`

### Implementation for User Story 2

- [x] T054 [US2] Implement incremental `textDocument/didChange` handling with strict version-order rejection in `server/adapter-lsp/src/main/java/vega/lsp/DocumentSyncHandler.java`
- [x] T055 [US2] Implement incremental reparse via `Tree.edit` plus old-tree reuse in `server/adapter-syntax/src/main/java/vega/syntax/TreeSitterSyntaxAdapter.java` — **partially built under T039**: edit application, byte splicing, accurate points via `LineIndex` and old-tree reuse are done and measured; what remains is wiring it to `didChange` and honouring T104's offset reconciliation
- [x] T056 [US2] Implement edit-proximity narrowing — intersect reported changed ranges with a bounded window around the edit — in `server/core/src/main/java/vega/core/highlight/HighlightNarrowing.java` (the reported range degenerates to the whole file on any brace edit; a user cannot type outside their viewport, so edit position is a sufficient proxy and needs no viewport message; research D12, D13)
- [x] T057 [US2] Implement token diffing producing semantic-token deltas in `server/core/src/main/java/vega/core/highlight/TokenDiffer.java`
- [x] T058 [US2] Implement `semanticTokens/full/delta` with `resultId` chaining through both response kinds and a bounded server-side result cache in `server/adapter-lsp/src/main/java/vega/lsp/SemanticTokensHandler.java`
- [x] T059 [US2] Implement cooperative cancellation checked at defined points during parse and highlight in `server/core/src/main/java/vega/core/highlight/HighlightService.java`
- [x] T060 [US2] Implement the Monaco document semantic-tokens provider handling both `SemanticTokens` and `SemanticTokensEdits`, and calling `releaseDocumentSemanticTokens`, in `app/src/renderer/editor/SemanticTokensProvider.ts`

**Checkpoint**: typing is within budget at every position; the spike's central hypothesis is
answered.

**Status (2026-09-05).** T045–T051 and T053–T060 done. Core gained `HighlightNarrowing`,
`TokenDiffer`/`TokenDelta` and `HighlightService`; the LSP layer gained incremental `didChange` with
strict version-order rejection, `semanticTokens/full/delta` with a bounded result cache, and
cancellation wired from `$/cancelRequest` through to the tree walk; the client gained delta
application, response-shape handling, version gating and the Monaco provider.

**Measured (T051, JMH, real corpus): the full per-keystroke path is 5.501 ms p95 against a 5 ms
budget** — reparse alone is 3.2–3.4 ms, and the balance is two whole-document copies per keystroke
(`Document.apply` rebuilding the text, plus the adapter's byte splice), roughly 3.6 MB of garbage per
character. Raised as **T107**; ADR-0002 carries an addendum narrowing its claim to reparse.

Defects found and fixed while building this phase, each invisible to the tests that existed before:

- `didOpen` depended on a prior `vega/readDocument` to populate the mirror, so any client that
  re-opened a document left every subsequent edit failing. `didOpen` now registers the text it
  carries, which is what LSP says it is for.
- The Monaco provider checked the document version *before* awaiting the request, comparing a value
  against itself; it could never detect the case it existed for.
- A file named on the command line was handed to the renderer before the backend was ready, so the
  first read failed and the document never appeared. The shell now retries when the backend reports
  ready.

---

## Phase 5: User Story 3 - Scroll smoothly (Priority: P3)

**Goal**: traverse the whole file by wheel, keyboard and scrollbar with no dropped-frame threshold
breach and no unstyled regions.

**Independent test**: scroll first line to last by each method while capturing a browser trace.

### Tests for User Story 3

- [x] T061 [P] [US3] Write a Playwright + CDP test asserting the dropped-frame threshold across full-file traversal by all three input methods, driving scrolling with `gpuBenchmarking.smoothScrollBy` under `--enable-gpu-benchmarking`, in `app/tests/e2e/scroll-smoothness.spec.ts` (SC-002 as amended; `requestAnimationFrame` deltas are an anti-pattern blind to compositor work — research D7)
- [x] T062 [P] [US3] Write a test asserting no blank or unstyled region is visible in never-previously-visited regions in `app/tests/e2e/scroll-no-blank-regions.spec.ts`
- [x] T063 [P] [US3] Write a JMH benchmark asserting full-document tokenization under 800 ms in `server/src/jmh/java/vega/bench/FullTokenizationBenchmark.java` (SC-005b)

### Implementation for User Story 3

- [x] T064 [US3] Implement the background `semanticTokens/full` request issued immediately after the initial range request in `app/src/renderer/editor/SemanticTokensProvider.ts`
- [x] T065 [US3] Implement full-document token array maintenance and baseline retention in `app/src/renderer/editor/TokenStore.ts`
- [x] T066 [P] [US3] Configure Monaco large-file rendering guards — `stopRenderingLineAfter`, `maxTokenizationLineLength` — in `app/src/renderer/editor/editorOptions.ts`
- [x] T067 [US3] Verify the first-token-paint path does not regress cold start, extending `app/tests/e2e/cold-start.spec.ts`
- [x] T068 [P] [US3] Confirm `newCDPSession` works against a Playwright-launched Electron context, recorded in `docs/tech-radar.md` (flagged unverified in research; the scroll measurement depends on it)

**Checkpoint**: scrolling holds its budget with continuous highlighting.

**Status (2026-09-05): COMPLETE.** T061–T068 all done. CDP and `gpuBenchmarking.smoothScrollBy` were
verified against Electron first (T068) because everything else in the phase depended on them; both
work, recorded in [docs/tech-radar.md](../../docs/tech-radar.md). Full-document tokenization measures
137 ms p50 / 183 ms p95 against an 800 ms budget. Sustained scrolling drops no frames at all.

A build defect was found and fixed here: the Monaco worker import used the deep
`monaco-editor/esm/vs/...` path, which Monaco 0.56's `exports` map no longer resolves. The build had
been failing since it was introduced, and a shell chain that discarded build output meant several
E2E runs silently re-used a stale bundle.

---

## Phase 6: User Story 4 - Tolerate broken code (Priority: P4)

**Goal**: syntactically invalid content degrades highlighting locally and recovers, never blanking
or freezing.

**Independent test**: insert an unclosed brace at line 100, observe re-highlight extent, then close
it and observe recovery.

### Tests for User Story 4

- [x] T069 [P] [US4] Write a **damage-locality regression test** asserting that an unclosed brace at line 100 changes no highlight captures beyond the enclosing block, in `server/core/src/test/java/vega/core/highlight/DamageLocalityTest.java` — error recovery is undocumented behaviour that has regressed across a parser minor version before, so this test guards SC-006 against dependency bumps (research D12)
- [x] T070 [P] [US4] Write an LSP-level integration test covering the unclosed-brace scenario against the fixture in `server/adapter-lsp/src/test/java/vega/lsp/BrokenSyntaxIntegrationTest.java`
- [x] T071 [P] [US4] Write a Playwright test asserting responsiveness and recovery while the file is invalid in `app/tests/e2e/broken-syntax.spec.ts`
- [x] T072 [P] [US4] Write a JMH benchmark asserting reparse stays within budget while a long-lived error region is present in `server/src/jmh/java/vega/bench/ErrorStateReparseBenchmark.java`

### Implementation for User Story 4

- [x] T073 [US4] Pin the tree-sitter core and grammar versions explicitly in `server/adapter-syntax/build.gradle.kts` and record the pin rationale in `docs/tech-radar.md`
- [x] T074 [US4] Ensure narrowing bounds visible damage when reported changed ranges degenerate, in `server/core/src/main/java/vega/core/highlight/HighlightNarrowing.java`
- [x] T075 [US4] Instrument how often a background full re-query would disagree with the narrowed result, in `server/core/src/main/java/vega/core/highlight/NarrowingAudit.java` — the spike must measure whether a reconciliation pass is needed rather than building one speculatively (research D13)

**Checkpoint**: broken code degrades locally and recovers.

**Status (2026-09-05): COMPLETE.** T069–T075 all done. Error recovery costs nothing measurable —
reparse with a long-lived unmatched brace is 4.334 ms p95 against 4.202 ms healthy, and typing while
invalid measured 21.8 ms p95 with zero dropped frames, matching the healthy path.

Two deviations from the task text, both deliberate:

- **T069 lives in `server/adapter-syntax`, not `server/core`.** It asserts tree-sitter's error
  recovery, and the core has no parser on its classpath (Principle III). Writing it where the task
  specified would have required breaking the boundary ArchUnit exists to protect.
- **T070 lives in `server/app`.** It needs the protocol layer and the real parser together, and
  neither adapter depends on the other — assembling them is the composition root's job.

Two defects found here:

- **`reparse` consumes the tree it is given.** Reuse shifts the old tree's node positions in place,
  so reading it afterwards returns offsets moved by the edit — silently, and wrong by exactly the
  number of characters typed. Now documented on `SyntaxParserPort.reparse`, with the deliberate
  exception noted in `HighlightService` where the edited tree is what `changedRangesSince` requires.
- **Every real-corpus keystroke measurement was anchored inside a javadoc comment.** Found because
  the error-state benchmark's guard refused to run: garbage inserted into a comment is still valid
  Java. All three measurement sites now anchor on real code; the verdict was unchanged.

---

## Phase 7: User Story 5 - See the timing (Priority: P5)

**Goal**: a shipped timing panel showing per-interaction input-to-render and round-trip figures with
rolling p50/p95.

**Independent test**: open the panel, type and scroll, confirm figures track what the benchmarks
report.

### Tests for User Story 5

- [x] T076 [P] [US5] Write a component test for the bounded rolling window's p50/p95 computation and fixed capacity in `app/tests/component/latency-window.test.ts`
- [x] T077 [P] [US5] Write a component test asserting `roundTripMs` is absent — never inferred or back-filled — for interactions that never reached the backend, in `app/tests/component/timing-sample.test.ts`
- [x] T078 [P] [US5] Write a Playwright test asserting the panel is reachable in a production-configuration build with no developer flag in `app/tests/e2e/timing-panel-shipped.spec.ts`
- [x] T079 [P] [US5] Write a Playwright test asserting keystroke latency still meets budget with the panel open in `app/tests/e2e/timing-panel-overhead.spec.ts`

### Implementation for User Story 5

- [x] T080 [P] [US5] Implement `TimingSample` collection from the latency probe in `app/src/renderer/timing/collector.ts`
- [x] T081 [P] [US5] Implement the bounded rolling `LatencyWindow` with p50/p95 in `app/src/renderer/timing/LatencyWindow.ts`
- [x] T082 [US5] Implement round-trip attribution by correlation id in `app/src/renderer/timing/roundTrip.ts`
- [x] T083 [US5] Implement the timing panel UI in `app/src/renderer/timing/TimingPanel.tsx`
- [x] T084 [US5] Expose the panel in the production build configuration in `app/src/renderer/shell/AppShell.tsx`

**Checkpoint**: budgets are judgeable live, not only in CI.

**Status (2026-09-05): COMPLETE.** T076–T084 all done. The panel ships in the production build with
no developer flag, verified by launching with none set (T078).

The panel's own reported figure tracks the independent Playwright probe — 29.0 ms p95 reported
against 22–31 ms measured externally on the same software-rendered environment. That agreement is
the real validation: the instrument and the external measurement are computed by different code from
different event sources, so matching figures mean the collection path is right rather than merely
self-consistent.

`roundTripMs` is absent, never zero, for interactions that never reached the backend (T077). A zero
would be averaged in and make the backend look faster the less it was consulted. With no backend
connected the panel shows an em dash, which says "nothing reached the backend" rather than "the
backend was instant".

---

## Phase 8: User Story 6 - Save (Priority: P6)

**Goal**: saving writes bytes matching the editor exactly, performed by the backend.

**Independent test**: edit, save, compare disk bytes against the buffer.

### Tests for User Story 6

- [x] T085 [P] [US6] Write unit tests for byte-exact serialisation preserving encoding, line-ending style and trailing-newline state in `server/adapter-fs/src/test/java/vega/fs/SerialisationRoundTripTest.java`
- [x] T086 [P] [US6] Write a test asserting an unmodified document saves byte-identical in `server/adapter-fs/src/test/java/vega/fs/UnmodifiedSaveTest.java`
- [x] T087 [P] [US6] Write an LSP-level integration test covering `written`, `mirror-mismatch`, `disk-changed` and write-failure outcomes in `server/adapter-lsp/src/test/java/vega/lsp/SaveRoundTripIntegrationTest.java`
- [x] T088 [P] [US6] Write a test asserting a failed save preserves the buffer and surfaces the reason in `app/tests/e2e/save-failure.spec.ts`
- [x] T089 [P] [US6] Write tests for CRLF, mixed line endings, non-UTF-8 encoding and missing trailing newline in `server/adapter-fs/src/test/java/vega/fs/EncodingEdgeCasesTest.java`

### Implementation for User Story 6

- [x] T090 [US6] Implement mirror hashing and comparison against `expectedContentHash` in `server/core/src/main/java/vega/core/document/MirrorVerification.java`
- [x] T091 [US6] Implement on-disk comparison against `baseContentHash` producing `disk-changed` in `server/adapter-fs/src/main/java/vega/fs/FileGatewayAdapter.java`
- [x] T092 [US6] Implement the `vega/saveDocument` handler writing from the verified mirror in `server/adapter-lsp/src/main/java/vega/lsp/VegaDocumentHandlers.java`
- [x] T093 [US6] Implement the save action and failure surfacing in `app/src/renderer/shell/AppShell.tsx`

**Checkpoint**: all six stories independently functional.

**Status (2026-09-06): COMPLETE.** T085–T093 all done. Saving writes from the verified mirror, and
an unmodified save is byte-identical for LF, CRLF, mixed endings, non-UTF-8 content and files with
no trailing newline.

Work this phase forced into the open, none of it in the task list:

- **The renderer never spoke to the backend at all.** It fetched text via `vega/readDocument` and
  stopped there — no `didOpen`, no `didChange`, and `VegaSemanticTokensProvider` was never registered
  with Monaco. US1 and US2 had built both ends and no wire between them. Found because a save was
  refused as a mirror mismatch.
- **Every keystroke re-read the file from disk.** `EditorHost`'s load effect depended on `onLoaded`
  and `onError`, which the shell passed as inline arrows; the timing panel re-renders the shell per
  keystroke, so the effect re-ran and `vega/readDocument` reset the backend's mirror mid-edit. The
  callbacks now live in refs and the effect depends only on what it actually reads.
- **Backend stderr was discarded.** The child is spawned with piped stdio that nothing read, so its
  logs and stack traces vanished — and an unread pipe eventually fills and blocks the writer. Now
  forwarded to the main process's stderr.
- **Non-UTF-8 files are decoded as ISO-8859-1**, which is byte-reversible, rather than as UTF-8 with
  replacement characters, which would corrupt such a file on its first save.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [x] T094 Implement the two-tier CI performance gate — blocking relative comparison on pull requests measured back-to-back on one runner, warning near 5% and failing near 10% — in `.github/workflows/ci.yml` (a 2% gate on hosted runners false-positives roughly half the time; research D9)
- [x] T095 Implement non-blocking change-point detection over the main-branch benchmark time series in `.github/workflows/perf-trend.yml`
- [x] T096 [P] Verify Gradle 9 compatibility with the JMH plugin, or pin a working Gradle version, in `gradle/wrapper/gradle-wrapper.properties` (support is merged upstream but unreleased; research D8)
*(T097 moved to Phase 3.5 — it now gates US2 rather than following it, because US2's tests assert the budget it measures.)*
- [x] T098 [P] Verify native packaging and load paths on macOS and Windows, recorded in `docs/tech-radar.md` — all measurements to date are Linux x86_64
- [x] T099 [P] Add an end-to-end test asserting a correlation id appears in both runtimes' logs for one request in `server/adapter-lsp/src/test/java/vega/lsp/CorrelationPropagationTest.java`
- [x] T100 [P] Add edge-case tests for oversized files, single-line files, empty files and large pastes in `server/core/src/test/java/vega/core/document/EdgeCaseTest.java`
- [x] T101 [P] Add a test asserting backend process death is surfaced rather than presenting as silently stalled highlighting in `app/tests/e2e/backend-death.spec.ts`
- [x] T107 **Done (2026-09-06), reversing ADR-0003 — see ADR-0004.** Eliminate the two whole-document copies per keystroke — `Document.apply` rebuilding the text in `server/core/src/main/java/vega/core/document/Document.java` and the byte splice in `server/adapter-syntax/src/main/java/vega/syntax/TreeSitterSyntaxAdapter.java` — which together allocate roughly 3.6 MB per character typed on a 1.8 MB file and put the full keystroke path at 5.501 ms p95 against a 5 ms budget (JMH, T051); this is O(file size) work per keystroke, the class Principle VI forbids
- [x] T103 Remove or properly gate the diagnostic counters (`BYTES_READ`, `SPLICE_NANOS`, `PARSE_NANOS`, `POINT_NANOS`) currently living as public static mutable state in `server/adapter-syntax/src/main/java/vega/syntax/TreeSitterSyntaxAdapter.java` — test-only instrumentation does not belong in a production class, even though it earned its keep diagnosing SC-004
*(T104 moved to Phase 3.5 — a correctness defect in already-shipped code, and it gates the trustworthiness of T097's measurement.)*
- [x] T102 Write the spike's findings — achieved numbers, or an ADR proposing amendment for any missed criterion — in `docs/adr/` and `specs/001-large-file-responsiveness/FINDINGS.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: no dependencies
- **Phase 2 (Foundational)**: depends on Phase 1; blocks every story
- **Phase 3 (US1)**: depends on Phase 2
- **Phase 3.5 (SC-004 gate)**: depends on US1's syntax adapter (T039, done); **blocks US2**
- **Phase 4 (US2)**: depends on US1 — editing requires an open document — and on Phase 3.5, which
  settles the budget its tests assert
- **Phase 5 (US3)**: depends on US1; independent of US2
- **Phase 6 (US4)**: depends on US2 — it exercises the incremental re-highlight path
- **Phase 7 (US5)**: depends on Phase 2's probe; richer once US2 and US3 exist
- **Phase 8 (US6)**: depends on US1; independent of US2–US5
- **Phase 9 (Polish)**: depends on all stories

### User Story Dependencies

Phase 3.5 sits between US1 and US2 because measurement, not planning, put it there: the budget US2
tests against is currently known to be unmet, so US2 cannot be written honestly until it is settled.

US1 is the foundation every other story builds on. US2 and US6 depend only on US1. US3 depends only
on US1. US4 depends on US2 because it tests that story's narrowing path. US5 is independent after
Phase 2 but is most useful last, when there is something to measure.

### Within Each User Story

Tests first, observed failing, then implementation — Constitution Principle VII, non-negotiable.
Within implementation: core before adapters, adapters before frontend.

### Parallel Opportunities

- Setup: T001–T004 and T007–T010 in parallel
- Foundational: protocol (T011–T014), core models (T015–T018) and ports (T020, T021) in parallel;
  T019 and T022 are sequenced because other core code depends on their shape
- Every story's test tasks are marked [P] — different files, no interdependencies
- US3, US6 and US5 can proceed in parallel once US1 is done
- Phase 3.5: T104 must precede T097 (offsets before measurement); T105 and T106 are sequential
- Phase 9: T096, T098–T101 in parallel

---

## Parallel Example: User Story 1

```text
# Write all User Story 1 tests together, then confirm each fails:
T032  core unit test — document open through a fake port
T033  encoding and line-ending detection tests
T034  LSP-level integration test — vega/readDocument then didOpen
T035  Playwright cold-start test
T036  no-direct-file-read test

# Then the frontend pieces, which touch different files:
T042  Monaco editor host
T043  shell with backend-status display
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational — blocks everything
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: open the fixture, confirm cold start and no direct file read
5. This is the smallest thing that proves the two-process file flow works at all

### Incremental Delivery

1. Setup + Foundational → skeleton enforced by ArchUnit
2. US1 → a large file opens and highlights → **MVP**
3. US2 → typing holds budget → **the spike's central question is answered here**
4. US3, US4 → scrolling and broken code
5. US5 → live visibility into the budgets
6. US6 → durable edits

### Solo Sequencing

This is a single-developer project, so the template's parallel-team strategy does not apply. The
[P] markers still matter: they identify tasks that can be batched without fear of conflicting edits,
which is what makes them safe to hand to an agent or to knock out in one sitting.

The highest-information order is Phase 1 → Phase 2 → US1 → **US2**. If the architecture is going to
fail its budgets, T051 and T052 are where it shows, and everything after them is comparatively
routine. Consider stopping to evaluate after US2 rather than after the full six stories.

---

## Notes

- [P] tasks touch different files and have no incomplete dependencies
- Verify every test fails before implementing against it
- Commit after each task or logical group
- Stop at any checkpoint to validate a story independently
- Three tasks exist purely to protect findings that research established but could silently
  regress: T069 (damage locality), T008 (fixture digest), T073 (parser version pin)
- T075 measures whether a reconciliation pass is needed rather than building one — the spike's job
  is to answer that question, not to pre-solve it
