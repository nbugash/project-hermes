# Implementation Plan: Spike 001 — Large-File Editor Responsiveness

**Branch**: `001-large-file-responsiveness` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-large-file-responsiveness/spec.md`

## Summary

Prove that the constitutionally mandated two-process split can hold editor-responsiveness budgets
on a 50,000-line Java file, or produce an ADR saying it cannot.

Technical approach: the editor process owns the in-memory buffer and renders typed characters
without waiting for the backend, so no protocol round trip sits on the 16 ms input-to-render path.
The backend acts as the file I/O gateway — it reads the document at open and writes it at save
through documented custom protocol requests — and supplies highlighting asynchronously as semantic
tokens, computed by an incremental, error-tolerant syntax layer and delivered as deltas keyed to
the document version they were computed from. Superseded highlight work is cancelled rather than
merely discarded. Everything is measured by benchmarks that run in CI and by a shipped timing
panel.

## Technical Context

**Language/Version**: TypeScript 5.x (strict mode) on Node/Electron for `/app`; Java 25 for
`/server` and `/protocol` JVM-side definitions.

**Primary Dependencies**:
- Frontend — React, Electron, Vite; **Monaco Editor** as the editor widget; `vscode-jsonrpc/node`
  for the protocol channel, used standalone with a thin hand-written client. Monaco is chosen
  because it implements the LSP semantic-token delta handshake natively — `lastResultId` in,
  `SemanticTokens | SemanticTokensEdits` out — so the highlighting path this spike depends on is
  library behaviour rather than code we write. `monaco-languageclient` is **not** adopted: it
  supplies feature wiring this spike does not use, at the cost of a VS Code service layer and a
  33 MB dependency tree (Principle VIII). See [research.md](./research.md).
- Backend — Vert.x 5 (transport and worker pools), Dagger 2 (composition root), lsp4j (protocol
  layer), tree-sitter via **`io.github.tree-sitter:jtreesitter`** (the official FFM binding, JDK 23+).
  Native `libtree-sitter` and `libtree-sitter-java` are **built by us with `-O3 -DNDEBUG`** and
  loaded through the `NativeLibraryLookup` SPI; both the build flags and the chunked `ParseCallback`
  parse path are budget-critical rather than stylistic (see [research.md](./research.md) D11).
- Deliberately **not** wired in this spike: jOOQ, SQLite, Flyway. The persistent index is out of
  scope, so its dependencies stay out of the build rather than sitting unused.

**Storage**: N/A for this spike — no persistent index. The only durable state is the user's source
file, read and written by the backend.

**Testing**: JUnit 5 (unit and LSP-level integration), ArchUnit (hexagonal dependency rules), JMH
(backend budgets) for `/server`; Vitest (component) and Playwright (e2e smoke and shell budgets)
for `/app`.

**Target Platform**: Desktop — Linux, macOS, Windows via Electron, with a lazily started JVM child
process. Budgets are enforced on one fixed CI runner class.

**Project Type**: Two-process desktop application (editor shell + language backend) in a monorepo.

**Performance Goals**: input-to-render p95 < 16 ms at lines 1 / 25,000 / 50,000; scroll frame time
p95 < 16 ms; launch to displayed-and-editable < 2 s; backend single-character re-analysis p95
< 5 ms; full initial parse of the fixture < 500 ms.

**Constraints**: the JVM runs with `--enable-native-access` set from day one, since
`--illegal-native-access` is documented to default to `deny` in a future release and JNI bindings
get no exemption; no protocol round trip on the input-to-render path; no filesystem read of the
opened document by the editor process; no whole-file re-analysis per keystroke; no CPU-bound work
on a Vert.x event loop; measurement overhead must not push a measured interaction out of budget.

**Scale/Scope**: one open document of up to 50,000 lines, Java only, single user, local backend.
No project index, no multi-file state.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against Constitution v2.0.0.

| # | Principle | Verdict | Basis |
|---|-----------|---------|-------|
| I | Two-runtime architecture with a protocol boundary | **PASS (with declared extensions)** | Editor owns rendering, backend owns parsing. Serving file content from the server and writing on save are not expressible in standard LSP, so both are declared as `vega/` custom requests in `/protocol`. No side channel: the editor's only contact with the backend is the one stdio channel, opened in exactly one module. |
| II | Backend independence | **PASS** | Every backend-side acceptance scenario is driven by a headless test client with no editor running (FR-023). The `vega/` extensions are documented in `/protocol`, so a third-party client can implement them; standard behaviour remains usable by a stock LSP client. |
| III | Hexagonal architecture | **PASS** | `/server/core` holds the document model, edit application and highlight orchestration, importing none of Vert.x, lsp4j, jOOQ, SQLite or tree-sitter. tree-sitter sits behind a syntax port exposing lazy cursor traversal, per the syntax-tree port constraint. ArchUnit enforces the import rules in CI. |
| IV | Threading discipline | **PASS** | Parsing and re-highlighting run on a dedicated CPU worker pool, never an event loop. Superseded highlight requests are cancelled cooperatively via `$/cancelRequest` and a checked cancellation token, satisfying FR-010. The batched-async-index-write clause is N/A — no index in this spike. |
| V | Performance budgets | **PASS (partial coverage, declared)** | Resolved by [ADR-0001](../../docs/adr/0001-editor-responsiveness-budgets.md), which splits B1 into B1a/B1b and restates SC-002 and SC-005. B3 unaffected. **B2 is never exercised** — completion is out of scope — so this spike cannot conclude that all constitutional budgets hold; the spec Overview declares this rather than leaving it implicit. |
| VI | Incremental everything | **PASS** | Incremental document deltas in, incremental reparse, changed-range-driven re-highlight out. Full-file analysis happens once at open, within its own 500 ms budget. No semantic layer is added, so the JDT gate is not triggered. |
| VII | Test-first for the backend | **PASS** | Unit tests exercise `/server/core` through ports with in-memory fakes and no adapter present; each backend behaviour also gets an LSP-level integration test against the fixture. Frontend gets component tests for editor-adjacent widgets plus an e2e smoke test. |
| VIII | Simplicity and YAGNI | **PASS** | Both violations resolved by deletion — the persistent-index port and the stubbed remote transport are removed from the spec's scope entirely. No exception is claimed and no ADR is required. See Complexity Tracking. |
| IX | Observability | **PASS** | Structured JSON logs both sides; correlation id per protocol request, propagated across the event-loop-to-worker dispatch boundary; the timing panel of User Story 5 is the constitutionally required first-class timing view, shipped rather than flag-gated. |

**Gate result**: all principles pass as of the post-design re-check. History retained below.

**Gate result (pre-research)**: Principle VIII fails. Both violations are removable rather than
justifiable, and the recommended resolution is deletion, not an ADR — see Complexity Tracking.
Phase 0 proceeds because neither violation blocks research, but the spike should not enter
implementation while either remains in scope.

### Post-Design Re-Check

Re-evaluated after Phase 0 research and Phase 1 design. Two principles changed status.

**Principle V — now blocked on an ADR.** Research produced measured evidence that three of this
spike's acceptance criteria cannot be met as written, for reasons unrelated to implementation
quality:

| Criterion | Finding | Proposed resolution |
|-----------|---------|---------------------|
| Budget B1 / SC-001 — 16 ms p95 keystroke to render | Chromium aligns input dispatch to `BeginFrame`, so measured from the hardware input timestamp the path contains a vsync wait before the handler runs. At 60 Hz the budget sits at or below the platform floor (D6). | Split into a gateable metric — handler start to committed frame under 16 ms p95 — plus "no dropped frames during sustained typing". Record that photon latency is not observable from page JavaScript. |
| SC-002 — scroll frame time 16 ms p95 | No API exposes per-frame time at this resolution; rAF-delta measurement is a documented anti-pattern blind to compositor and GPU work, and LoAF's threshold is fixed at 50 ms (D7). | Restate as a dropped-frame assertion over the scroll window, measured from a Chromium trace under synthetic gestures. |
| SC-005 — full analysis under 500 ms | Measured ~300 ms to parse plus ~320 ms to run the highlight query over the whole file, ~620 ms combined (D14). | Split: full parse under 500 ms, full tokenization under 800 ms. Whole-file tokenization is off the cold-start critical path under the range-first strategy, so B3 is unaffected. |

**Resolved.** [ADR-0001](../../docs/adr/0001-editor-responsiveness-budgets.md) was accepted and
Constitution Principle V amended to 2.1.0 before implementation, because Principle V forbids
disabling a failing gate — a benchmark that provably cannot pass would have poisoned the
enforcement mechanism the constitution depends on. SC-001a/b, SC-002 and SC-005a/b in the spec now
match what the platform can measure.

**Principle VI — confirmed, with a caveat that became a requirement.** Incremental reparse is
measured at 0.70 ms p95 on the fixture, comfortably inside SC-004. But `changedRanges` degenerates
to the whole document on any brace open or close (D12), so re-highlighting MUST be narrowed by edit
proximity and token-diffed (D13). Naive use of `changedRanges` would repaint the whole file on the
first `{` typed — satisfying the letter of "incremental" while violating its purpose.

**Principles I, II, III, IV, VII, IX** — unchanged by research; Phase 1 artifacts introduced no new
violations. Custom protocol surface remains two requests, and D13 removed the need for a third by
using edit proximity instead of a viewport notification.

## Project Structure

### Documentation (this feature)

```text
specs/001-large-file-responsiveness/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
├── architecture.md      # Phase 2 output — BLOCKED, template unresolved
├── design.md            # Phase 2 output — BLOCKED, template unresolved
└── tasks.md             # Phase 3 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/                                  # Electron + React + TypeScript editor shell
├── src/
│   ├── main/                         # Electron main process; spawns the JVM child process
│   ├── preload/
│   ├── renderer/
│   │   ├── editor/                   # editor widget host, buffer, token application
│   │   ├── timing/                   # timing panel (User Story 5)
│   │   └── shell/                    # window, status, degraded-start indication
│   └── protocol-client/              # THE ONLY module that opens the stdio channel.
│                                     # Lives in the main/utility process, never the renderer:
│                                     # a sandboxed renderer has no Node, and the channel is
│                                     # bridged to the renderer over Electron IPC.
└── tests/
    ├── component/                    # Vitest
    └── e2e/                          # Playwright: smoke + shell budget benchmarks

server/                               # Java 25 language backend
├── core/                             # analysis core - no Vert.x/lsp4j/tree-sitter imports
│   └── src/main/java/                # document model, edit application, highlight
│                                     # orchestration, ports as plain interfaces
├── adapter-lsp/                      # inbound: lsp4j over Vert.x transport
├── adapter-syntax/                   # outbound: tree-sitter binding behind the syntax port
├── adapter-fs/                       # outbound: file read/write for the vega/ extensions
├── app/                              # composition root; Dagger 2 wiring; main()
└── src/test/java/                    # JUnit 5 unit + LSP-level integration, ArchUnit rules
                                      # jmh/ source set for backend budget benchmarks

protocol/                             # shared custom LSP extension definitions (vega/*)
├── schema/                           # wire-format definitions, versioned
├── ts/                               # generated or hand-written TS types for /app
└── java/                             # Java types for /server

fixtures/
└── large-java-file/                  # deterministic 50,000-line Java fixture + generator

docs/
├── adr/                              # required for this spike's failure path
└── tech-radar.md                     # plan-level library choices recorded here
```

**Structure Decision**: The monorepo layout is fixed by the constitution (`/app`, `/server` with a
dependency-free `/server/core`, `/protocol`, `/fixtures`, `/docs`). Within `/server`, the module
split mirrors the hexagonal mapping the constitution fixes — one Gradle module per adapter plus a
core module and a composition-root module — so that ArchUnit rules and Gradle's own dependency
graph enforce the same boundary, and a forbidden import fails at compile time rather than only in
a test. `/app/src/protocol-client` is isolated as the single module permitted to open the stdio
channel, which is the mechanical form of the no-side-channels rule.

## Complexity Tracking

> **Resolved — no outstanding violations.** Both Principle VIII violations were removed by deleting
> the abstractions rather than justifying them, so no exception is claimed and no ADR is needed.

| Violation | Resolution |
|-----------|------------|
| Persistent index port defined with no implementation behind it | **Deleted from scope.** Nothing in this spike reads or writes an index, so the port had zero implementations and zero callers. A port designed before its first consumer exists will be wrong in ways no test can detect. It is introduced with the first feature that actually indexes. |
| Remote backend transport interface, stubbed | **Deleted from scope.** The property enabling a future remote backend is behavioural, not structural: FR-002 keeps all file access on the backend, enforced by tests and by the single-module channel rule. An interface with one implementation proved nothing further. |

Neither deletion weakens the remote-backend story: `vega/readDocument` and `vega/saveDocument` are
precisely the operations that must follow the backend when it moves, and both are already defined in
`/protocol`.
