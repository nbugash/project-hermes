<!--
SYNC IMPACT REPORT
Version change: 2.0.0 → 2.1.0

Bump rationale (MINOR): Principle V's Budget B1 is materially expanded — one gate becomes two, and
the measurement point is stated unambiguously. Classed MINOR rather than MAJOR because no principle
is removed or renumbered, and because the change corrects a requirement that was unachievable as
worded rather than reversing a workable one: measured from the hardware input timestamp, B1 sat at
or below Chromium's vsync floor at 60 Hz. Nothing compliant under 2.0.0 becomes non-compliant, since
no implementation exists. The stricter reading — that redefining a budget's measurement point is
backward-incompatible — was considered and rejected on those grounds; it is recorded here so the
call can be revisited.

Modified principles:
- V. Performance Budgets — B1 split into B1a (handler start to committed frame, under 16 ms p95)
  and B1b (at most one dropped frame during sustained typing); explicit statement that end-to-end
  photon latency is not observable from page JavaScript and is therefore not a budget; benchmark
  clause extended to require frame counts from a browser trace rather than requestAnimationFrame
  deltas.
- I, II, III, IV, VI, VII, VIII, IX — unchanged.

Added sections: none. Removed sections: none.

Governing ADR: docs/adr/0001-editor-responsiveness-budgets.md

Follow-up TODOs from 2.0.0 — now resolved:
- /docs/adr created.
- /docs/tech-radar.md created.
Remaining:
- The 50k-line completion fixture named in Budget B2 does not exist yet; B2 is unenforceable in CI
  until it lands under /fixtures. B2 is also untouched by Spike 001, which excludes completion.
Templates under .specify are unmodified by this command; they read this file at runtime.
-->

# Vega Constitution

Vega is a desktop IDE built from a React/TypeScript frontend and a Java language-intelligence
backend that communicate over the Language Server Protocol. It is developed by a solo
maintainer, is explicitly learning-oriented, and is intended to become a tool its author uses
daily. These principles exist so that a specification or plan can be checked against them and
rejected; vague aspiration is not a principle.

## Core Principles

### I. Two-Runtime Architecture with a Protocol Boundary

Vega is exactly two runtimes with one seam between them, and the seam is the protocol.

- The frontend MUST be React + TypeScript inside an Electron shell. It owns rendering, editor
  widgets, docking, trees, and popups.
- The backend MUST be a Java 25+ JVM process. It owns parsing, indexing, symbol resolution,
  completion, diagnostics, and refactoring.
- The two MUST communicate exclusively via LSP (JSON-RPC over stdio). Ad-hoc side channels are
  forbidden: no additional sockets, no HTTP endpoints, no shared temp files, no direct
  filesystem handshakes between the app and the server.
- Any capability not expressible in standard LSP MUST be added as a documented custom extension
  declared in the single `/protocol` module, namespaced `vega/`, and versioned there. An
  undeclared custom message is a defect, not a shortcut.
- Language-intelligence logic MUST NOT live in TypeScript. Rendering logic MUST NOT live in
  Java.

Narrow carve-out, required so this principle does not contradict Principle V: the frontend MAY
implement purely lexical, non-semantic editor behaviour on the client when a protocol round trip
cannot meet Budget B1 — local echo, bracket matching, indentation guides, and regex/grammar-based
syntax tokenization. Any behaviour that requires symbol resolution, type information, or the
project-wide index MUST live in the Java backend, without exception.

Rationale: the boundary is what makes the backend reusable and the frontend replaceable. A
single side channel, once added, silently becomes load-bearing and the boundary stops being
real. The carve-out is drawn at "needs the index" precisely because that is the line a reviewer
can check without argument.

### II. Backend Independence

The Java backend MUST be runnable and testable headless by any LSP-compliant client. The Vega
frontend is one client, not the only one.

- No backend feature may depend on the Electron shell, on Vega-specific UI state, or on any
  frontend-supplied behaviour outside the LSP session.
- Every backend feature MUST be demonstrable through a generic LSP client driving the server
  over stdio, with no Vega frontend running.
- If a feature cannot be exercised headless, it is misplaced and MUST move to the frontend or
  be expressed as a documented `/protocol` extension.

Rationale: independence is what forces the protocol boundary to stay honest. It also means the
backend can be tested without booting Electron, which is the difference between a fast test
suite and one nobody runs.

### III. Hexagonal Architecture (Backend)

`/server` MUST be organised as ports and adapters, with the analysis core isolated by
compile-time dependency rules rather than by convention.

- **Analysis core (`/server/core`)** — parsing orchestration, indexing, symbol resolution,
  completion, diagnostics, refactoring. It MUST have no compile-time dependency on Vert.x,
  lsp4j, jOOQ, SQLite, or tree-sitter. It exposes its ports as plain Java interfaces.
- **Inbound adapter** — LSP, built on lsp4j over a Vert.x transport. It translates protocol
  messages into calls on core ports and MUST contain no analysis logic.
- **Outbound adapters** — the persistent index (jOOQ/SQLite), syntax parsing (tree-sitter), and
  the file system watcher. Each sits behind a port owned by the core.
- Dependencies MUST point inward. Dependency rules MUST be enforced by ArchUnit tests that run
  in CI, and a violation MUST fail the build.
- Dagger 2 wires adapters to ports at the composition root only. Injection MUST NOT be used to
  reach across the boundary in defiance of the rule above.

Syntax-tree port constraint, required so this principle does not collide with Budget B2: the
incremental parse state is owned by the tree-sitter adapter, and the core MUST query it through
a lazy cursor or visitor port. The port MUST NOT require materialising a translated copy of the
whole syntax tree per request, because a full translation on each query reintroduces the
whole-file cost that Principle VI exists to eliminate.

Rationale: hexagonal architecture is what makes Principle II mechanical rather than aspirational
— if the core never imports lsp4j, headless usability is structural rather than something to
remember. ArchUnit is named because an architecture rule that no build can fail on decays into
folder naming within a month.

### IV. Threading Discipline

- Parsing, indexing, and symbol resolution MUST NOT run on a Vert.x event loop. CPU-bound work
  runs on a dedicated worker pool, separate from the pool serving blocking I/O.
- Blocking the event loop is a defect. Vert.x blocked-thread warnings MUST fail CI rather than
  be tuned away by raising the threshold.
- All CPU-bound work MUST be cancellable via LSP `$/cancelRequest`, and cancellation MUST be
  cooperative: long-running analysis takes a cancellation token and checks it at defined points.
  Java cannot safely stop a thread from outside, so "cancellable" means the work opts out, not
  that the caller kills it. Cancellation MUST actually stop in-flight work; discarding the
  result while the worker keeps running is a defect against Budget B2.
- Index writes MUST be batched and asynchronous. Nothing writes to the persistent index
  synchronously in a request path.

Read-your-writes contract, required because asynchronous batching otherwise makes stale results
legal: request handling MUST NOT depend on a pending batch having been flushed. The core MUST
serve requests from an in-memory overlay of edits not yet persisted, so that a completion issued
immediately after an edit reflects that edit. A design in which correctness depends on flush
timing MUST be rejected.

Rationale: the point of cancellation in an IDE is reclaiming the worker thread, because
completion requests are superseded by the next keystroke constantly. The overlay rule is stated
because "writes are async" and "results are fresh" are only compatible if something explicitly
bridges the gap, and leaving that unstated produces staleness bugs that reproduce only under
fast typing.

### V. Performance Budgets (Measured, Not Aspirational)

Vega ships with numeric budgets. Each budget names an owner, a measurement point, and the
conditions under which it is measured, because a budget without those is not checkable.

- **B1 — Editor responsiveness.** Owner: frontend. Two gates, both required:
  - **B1a — Handler start to committed frame: under 16 ms at p95.** Measured in the renderer. No
    LSP round trip may sit on this path.
  - **B1b — At most one dropped frame during sustained typing.**

  End-to-end photon latency is **not** a budget, because it is not observable from page
  JavaScript. Chromium aligns input dispatch to the frame boundary, so latency measured from the
  hardware input timestamp includes a vsync wait before any of our code runs; at 60 Hz that alone
  approaches the whole 16 ms. B1a measures the work Vega controls, B1b measures what the developer
  perceives, and neither pretends to be the other. See ADR-0001.
- **B2 — Completion popup: under 100 ms p95.** Owner: end-to-end, client-observed, from the
  triggering keystroke to the popup being painted. Measured against the designated 50k-line
  fixture project under `/fixtures`, with a warm JVM and a built index.
- **B3 — Cold start to editable window: under 2 s.** Owner: frontend shell. Measured from
  process launch to the editor accepting input. The JVM MUST start lazily and MUST NOT block
  the UI shell.

**A budget without a benchmark is not a budget.** Backend budgets MUST be measured by JMH
harnesses; shell budgets MUST be measured by Playwright benchmarks. Both MUST run in CI on every
pull request. Timing for B1a MUST come from in-page performance marks rather than from Playwright
wall-clock, because harness overhead is the same order of magnitude as the budget itself. Frame
counts for B1b MUST come from a browser trace, not from `requestAnimationFrame` deltas, which are
blind to compositor and GPU-thread work.

Each benchmark MUST declare its warmup, run count, and comparison baseline, and budgets are
enforced against a fixed CI runner class. When enforcement proves flaky, the harness MUST be
fixed or its baseline restated by ADR; disabling the check is not an available response.

Degraded-state contract, implied by B3 and therefore mandatory: between the editable window and
backend readiness, the editor MUST accept input and MUST surface backend status to the user.
Language features degrade visibly; they never block typing, and they never fail silently.

Any change that regresses a budget requires a spec-level justification recorded in the relevant
specification, and an ADR if the budget itself is being changed.

Rationale: IDE quality is latency, and latency regressions arrive one plausible 5 ms at a time.
The flakiness clause exists because a noisy perf gate is disabled within weeks, and a disabled
gate is indistinguishable from never having had one.

### VI. Incremental Everything

Parsing, indexing, and highlighting MUST be incremental. Whole-file re-analysis on every
keystroke is a rejected design and MUST NOT appear in a plan.

- Document changes MUST be consumed as incremental LSP text-document deltas; a design that
  requires full-document synchronization on edit MUST be rejected.
- Re-analysis work MUST be proportional to the size of the edit, not the size of the file or
  the project.
- The syntax layer MUST be tree-sitter, for incremental and error-tolerant parsing.
- A semantic layer (Eclipse JDT or equivalent) MAY be added only via a specification that
  demonstrates the syntax layer is insufficient for a specific shipped feature. Anticipated need
  does not qualify.
- Index persistence MUST be incremental as well: an edit updates the affected rows. Rewriting a
  table or re-serialising a whole-project snapshot per edit is a rejected design.
- Any design that cannot be made incremental MUST justify itself against Budget B2 with
  measurements before it is accepted.

Rationale: incrementality is not an optimization to add later. It determines the data
structures, so retrofitting it means rewriting them. Error tolerance is why tree-sitter is named
specifically: source is syntactically invalid most of the time while it is being typed, which is
exactly when completion must still work.

### VII. Test-First for the Backend (NON-NEGOTIABLE)

- Every backend feature MUST ship with unit tests and at least one LSP-level integration test
  that drives the running server against a fixture project under `/fixtures`.
- Backend tests MUST be written before the implementation they cover, and MUST be observed
  failing before that implementation is written.
- Unit tests for the analysis core MUST NOT require the LSP adapter, the SQLite adapter, the
  tree-sitter adapter, or a Vert.x runtime. They exercise the core through its ports, using
  in-memory fakes. A core test that needs an adapter to run indicates a leak across the
  hexagonal boundary and MUST be treated as a Principle III violation.
- Frontend: editor-adjacent widgets MUST have component tests. The Electron shell MUST have an
  end-to-end smoke test that launches the app and reaches an editable window.
- A backend feature with no LSP-level integration test is incomplete and MUST NOT be marked
  done, regardless of unit-test coverage.

Rationale: the LSP-level test is the one that proves the protocol boundary works, which unit
tests structurally cannot. The core-isolation rule gives Principle III a second enforcement
mechanism beyond ArchUnit, catching runtime coupling that static import rules miss.

### VIII. Simplicity and YAGNI

- No plugin or extension system MAY be built until at least three concrete extension points
  have been genuinely needed by shipped features. Anticipated need does not count.
- LSP-standard behaviour MUST be preferred over custom UI or custom protocol messages until the
  standard behaviour is demonstrated to be insufficient, with the demonstration recorded in the
  relevant specification.
- No web framework beyond Vert.x, and no container runtime. Adding either REQUIRES an ADR.
- Abstractions with a single implementation and configuration with a single value MUST NOT be
  introduced speculatively.
- A new runtime dependency beyond the ratified stack MUST be justified in the plan that
  introduces it, against what it replaces.

Rationale: a solo project dies of surface area long before it dies of missing features. The
"three concrete extension points" rule exists because plugin systems designed before their
consumers exist are always wrong, and always expensive to unwind. The framework ceiling is
stated numerically for the same reason: "keep it simple" never rejected anything.

### IX. Observability

- Both runtimes MUST emit structured JSON logs.
- Every LSP request MUST carry a correlation id, propagated across the boundary, and that id
  MUST appear on every log line emitted while handling the request on either side.
- The correlation id MUST survive dispatch from the event loop to the worker pool. Context bound
  to a thread and lost at a dispatch boundary does not satisfy this requirement.
- A built-in timing view for LSP round trips is a first-class shipped feature. It MUST be
  reachable in production builds and MUST NOT be gated behind a developer-only flag or a debug
  build.

Rationale: the timing view is how Principle V's budgets get defended in daily use rather than
only in CI. The propagation clause is called out because thread-bound logging context is the
usual way correlation ids die exactly where the expensive work happens.

## Technology and Structure Constraints

### Frontend stack

TypeScript in strict mode, React, Electron, Vite, Vitest for unit and component tests,
Playwright for end-to-end tests and shell benchmarks. TypeScript `strict` MUST remain enabled;
suppressions (`any`, `@ts-expect-error`, `@ts-ignore`) MUST carry an inline justification.

Protocol client: `vscode-jsonrpc` / `vscode-languageclient`. The stdio channel to the backend
MUST be opened in exactly one module; no other frontend code may spawn the backend process or
open that channel. This is the mechanical form of Principle I's no-side-channels rule.

### Backend stack

Java 25+, Gradle with the Kotlin DSL, Vert.x 5 for asynchronous execution and transport,
Dagger 2 for compile-time dependency injection, jOOQ over SQLite in WAL mode for the persistent
index, Flyway for index schema migrations, tree-sitter for incremental syntax parsing, lsp4j for
the protocol layer, JUnit 5 for tests, ArchUnit for dependency rules, and JMH for backend
benchmarks.

### Persistent index

- The SQLite index is derived data, never a source of truth. It MUST be fully rebuildable from
  the project sources.
- A corrupt, unreadable, or unmigratable index MUST be recoverable by deleting it and
  rebuilding. Vega MUST NOT fail to start because the index is bad, and a failed Flyway
  migration MUST degrade to a rebuild rather than to a dead editor.
- Schema changes MUST go through Flyway migrations. Ad-hoc DDL at runtime is forbidden.
- Under WAL, SQLite permits many concurrent readers but only one writer. Index writes MUST
  therefore be funnelled through a single writer path, which is also what makes the batched
  asynchronous writes of Principle IV coherent. Concurrent writers from the worker pool are a
  rejected design. Readers may run concurrently.

### Monorepo layout

- `/app` — the React/TypeScript/Electron frontend.
- `/server` — the Java backend, with `/server/core` as the dependency-free analysis core.
- `/protocol` — shared definitions for Vega's custom LSP extensions, and the only place they
  may be declared.
- `/fixtures` — test projects consumed by backend integration tests and performance benchmarks.
- `/docs` — ADRs under `/docs/adr`, and the tech radar at `/docs/tech-radar.md`.

### Non-constitutional choices

Library choices not named in this document — editor widget, state management, file watching,
terminal, packaging, profiling — are plan-level decisions. They MUST be recorded in
`/docs/tech-radar.md` with the date and the reason, and they MUST NOT be treated as
constitutional constraints or as grounds for rejecting a plan.

Rationale: naming every library here would mean amending the constitution to change a file
watcher. The radar keeps those decisions written down and reversible without an ADR.

### Continuous integration

CI MUST run, on every pull request: the frontend test suite, the backend test suite, the
ArchUnit dependency rules, and the JMH and Playwright performance benchmarks. All MUST pass for
a change to be merged.

## Development Workflow and Quality Gates

- Work proceeds specification-first: `/speckit-specify` (what and why), then `/speckit-plan`
  (stack and architecture), then `/speckit-tasks`, then `/speckit-implement`.
- Every plan MUST contain a "Constitution Check" section that either confirms compliance with
  each principle or names the exception together with the ADR that authorizes it. A plan without
  this section MUST NOT proceed to tasks.
- A change is complete only when: backend tests (unit and LSP-level integration) pass, frontend
  component and smoke tests pass, ArchUnit rules pass, and the performance benchmarks are within
  budget. Completion is claimed from observed command output, never from expectation.
- A budget regression, a new custom protocol message, a new runtime dependency, or a new index
  migration MUST be surfaced explicitly in review rather than arriving inside an unrelated
  change.

## Governance

This constitution supersedes other practices for the Vega project. Where a convention and this
document disagree, this document wins until amended.

The stack recorded under Technology and Structure Constraints is the ratified baseline. Principle
VIII governs complexity added beyond that baseline; it is not grounds for rejecting the ratified
stack itself. Removing or replacing a ratified dependency requires an ADR, exactly as adding one
outside the baseline does. Choices delegated to `/docs/tech-radar.md` are outside this rule and
need no ADR.

Amendment procedure: every amendment REQUIRES a short ADR in `/docs/adr` that states what
changed, why, and what it supersedes. ADRs are numbered sequentially and are immutable once
merged; a reversal is a new ADR, not an edit. The amendment and its ADR land together.

Versioning policy — semantic:

- **MAJOR**: a principle is removed, renumbered, or redefined in a backward-incompatible way, or
  governance changes such that previously compliant work becomes non-compliant.
- **MINOR**: a principle or section is added, or existing guidance is materially expanded.
- **PATCH**: clarification, wording, or typo fixes that do not change what is permitted.

Compliance review: every plan carries the Constitution Check described above. Exceptions are
permitted only when named explicitly and backed by an ADR; an undocumented exception is a
defect. Complexity that is not justified against Principle VIII MUST be removed rather than
explained.

**Version**: 2.1.0 | **Ratified**: 2026-09-05 | **Last Amended**: 2026-09-05
