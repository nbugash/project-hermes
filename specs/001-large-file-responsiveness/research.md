# Phase 0 Research: Spike 001 — Large-File Editor Responsiveness

Decisions resolving the unknowns in [plan.md](./plan.md) Technical Context. Format: decision,
rationale, alternatives considered.

**Headline finding: Budget B1 is not achievable as literally worded, for reasons of platform
physics rather than implementation quality.** See [D6](#d6-budget-b1-is-infeasible-as-worded--adr-required).
This is the outcome the spike was built to detect, arrived at before any code was written.

---

## Editor and protocol

### D1: Editor widget — Monaco

**Decision**: Monaco Editor for the editor widget.

**Rationale**: not raw performance — both candidates virtualize adequately at 50,000 lines. Monaco's
own guards only engage at 300,000 lines / 20 MB, so the fixture sits roughly 6× under the threshold
and tokenization is never auto-disabled. The deciding factor is that Monaco implements the LSP
semantic-token delta handshake natively:
`provideDocumentSemanticTokens(model, lastResultId, token) → SemanticTokens | SemanticTokensEdits`,
a direct mirror of LSP `full` / `full/delta`, including `releaseDocumentSemanticTokens` for
releasing server-side cached results. The highlighting path this entire spike depends on is
therefore library behaviour rather than code under test.

Monaco's piece-tree buffer keeps memory close to file size, and its `stopRenderingLineAfter`
(default 10,000 chars) and `maxTokenizationLineLength` (default 20,000) directly serve the spec's
pathological-long-line edge case.

**Alternatives considered**:
- *CodeMirror 6* — markedly smaller (~150 KB gzip versus a realistic ~600–700 KB floor for Monaco
  once locales and the bundled TypeScript/CSS/HTML services are dropped). Rejected because it has
  no token-provider concept: server tokens become `Decoration.mark` ranges that we would derive and
  maintain ourselves, and `@codemirror/lsp-client` contains no semantic-token support whatsoever.
  Building and owning the delta-to-decoration pipeline is real risk added to the one path the spike
  is measuring. Its documented weakness with long unwrapped lines also works against the fixture.
- Deferring the choice to the tech radar — rejected: the delta capability materially changes the
  work, so it belongs in the plan.

**Caveat carried forward**: no credible published head-to-head benchmark of the two at 50k lines
exists. The comparison articles that surface in search have no methodology and were not relied on.
Monaco's advantage here is capability, not measured speed.

### D2: Protocol client — thin client over `vscode-jsonrpc/node`, in the main process

**Decision**: hand-written LSP client on `vscode-jsonrpc/node` with `StreamMessageReader` /
`StreamMessageWriter` over the backend's stdio, living in the Electron **main or utility process**
and bridged to the renderer over Electron IPC.

**Rationale**: `vscode-jsonrpc` is explicitly supported standalone — its own documentation describes
establishing a JSON-RPC channel outside VS Code — and needs no shim. A sandboxed renderer has no
Node, so the channel cannot live there regardless; that constraint happens to reinforce
Constitution Principle I, since the stdio channel becomes structurally unreachable from UI code.

**Alternatives considered**:
- *`vscode-languageclient` directly* — rejected: it imports the `vscode` extension API and cannot
  run bare.
- *`monaco-languageclient`* — rejected under Principle VIII. It is not a reimplementation; it aliases
  the `vscode` module to a full VS Code API implementation and runs real `vscode-languageclient` on
  top, pulling a VS Code service layer and a ~33 MB dependency tree to provide completion, hover,
  rename and diagnostics — every one of them out of scope here. Reconsider when those features
  arrive.
- *`vscode-ws-jsonrpc` / a WebSocket transport* — rejected: it exists because browsers have no
  stdio. We own a Node process, so it would add a localhost port and an authentication surface for
  nothing.

### D3: Highlight transport — `/range`, then `/full`, then `/full/delta`

**Decision**: on open, request `textDocument/semanticTokens/range` for the initial viewport; request
`/full` in the background immediately after; use `/full/delta` for every subsequent edit, with a
full-result fallback.

**Rationale**: this is the specification's own recommended pairing, and it resolves a conflict
between two of our requirements. Range-first puts colour on screen without waiting to tokenize all
50,000 lines, which is what makes Budget B3 attainable; the background full request is what
guarantees no unstyled region appears during scrolling (FR-013). Requesting full-only at open, as
originally planned, would have spent the cold-start budget poorly.

**Alternatives considered**: full-only at open (rejected: wastes B3); range-only with per-viewport
refresh on scroll (rejected: `/range` has no delta variant, and on-demand fetching is precisely what
produces the blank regions FR-013 forbids).

**Correctness rules this decision imports** — all now recorded in
[contracts/lsp-surface.md](./contracts/lsp-surface.md), each one a test rather than a note:

1. Delta edits are splices on a flat integer array, **all relative to the same prior state, and
   explicitly unsorted**. They must be sorted and applied back-to-front. Applying in received order
   corrupts the array silently and yields highlighting that is *wrong* rather than stale — the worst
   failure mode, because it reads as a rendering bug.
2. `resultId` chains through delta responses too; the baseline must be updated after every response.
3. A server may legally return a full result to a delta request regardless of advertised capability.
   Shipped servers have done exactly this.
4. `ErrorCodes.ServerCancelled` obliges the client to retrigger, and fast typing will hit it
   routinely — a normal path, not an error path.
5. Both sides retain state per `resultId`; unbounded retention is a memory hazard.
6. Position encoding must be negotiated explicitly. Because token offsets are *relative*, a
   UTF-16/UTF-8 disagreement shifts **every subsequent token in the document**, and one non-BMP
   character in a Java string literal is enough to trigger it.

### D4: File content fetch — custom `vega/readDocument`, shaped to migrate

**Decision**: keep the custom request, and shape it deliberately like the standard method.

**Rationale**: core LSP inverts the flow — after `didOpen`, "the document's content is now managed
by the client and the server must not try to read the document's content using the document's Uri."
LSP 3.18 does add `workspace/textDocumentContent` (`{uri} → {text}`), which was evaluated seriously
under Principle VIII and rejected on three grounds: the spec designates its result **read-only** and
intends it for non-`file:` schemes such as decompiled classes, not documents about to be edited and
saved; it carries no encoding, line-ending or trailing-newline metadata, all of which FR-020's
byte-exact save requires from whoever read the bytes; and 3.18 describes itself as under
development. Mirroring its shape keeps migration to a rename rather than a redesign.

**Alternatives considered**: `workspace/textDocumentContent` (above); `workspace/fileOperations`
(rejected: notifies about file lifecycle and carries no content at all). Prior art for the custom
route is `eclipse.jdt.ls`'s long-shipped `java/classFileContents`.

---

## Measurement and enforcement

### D5: Latency probe — `requestAnimationFrame` + `MessageChannel`

**Decision**: measure input-to-frame in-page by recording the input event timestamp and resolving a
callback scheduled via `requestAnimationFrame` followed by a `MessageChannel` message, which runs
after the frame is produced. Keep a `PerformanceObserver` on `event` entries alongside, used solely
for attribution when a sample misses budget.

**Rationale**: the Event Timing API cannot resolve this budget. `PerformanceEventTiming.duration` is
specified as quantized to **8 ms granularity**, so a genuine 12 ms interaction reports as 8 or 16;
and `durationThreshold` is clamped to a **minimum of 16 ms**, so sub-budget interactions cannot be
observed at all. Its `startTime`, `processingStart` and `processingEnd` remain full-resolution and
are valuable for splitting input delay from handler time from presentation delay — which is exactly
what makes it a good attribution tool and a useless gate.

`requestPostAnimationFrame` is not available: the WICG proposal was archived in 2023 and never
shipped. `setTimeout(0)` is wrong here — Chromium clamps nested timers to 4 ms and does not order
them after the render steps.

Clock resolution is not a concern: Chromium coarsens high-resolution timestamps to 100 µs, or 5 µs
under cross-origin isolation — roughly 160× finer than the budget.

**Electron requirement**: `backgroundThrottling: false`, and the window must stay visible and
unoccluded, or `requestAnimationFrame` stalls and measurement stops.

### D6: Budget B1 is infeasible as worded — ADR required

**Decision**: propose a constitutional amendment to B1 **before** implementing the harness, rather
than building a benchmark that cannot pass.

**Finding**: Chromium dispatches input aligned to `BeginFrame`. Measured from the browser's hardware
input timestamp — which is what `event.timeStamp` gives, and what "from the input event" in the
constitution's B1 most naturally means — the path to a committed frame therefore contains a wait for
the next vsync before the handler even runs. At 60 Hz that wait alone averages roughly half a frame
and reaches 16.7 ms, so **a p95 under 16 ms from hardware timestamp to committed frame is at or
below the physical floor of the platform.** No amount of implementation quality moves it.

The same budget measured from *handler start* to committed frame is comfortably achievable, and is a
real measure of whether our code is fast. The constitution's B1 sentence — "Measured in the renderer
from the input event to the committed frame" — is ambiguous between the two, and the natural reading
is the infeasible one.

**Recommended amendment**, to be written as an ADR in `/docs/adr` and applied to Constitution
Principle V:

- Keep a hard, achievable latency gate: **handler start to committed frame under 16 ms at p95**,
  which measures the work Vega actually controls.
- Add a second gate covering what the user perceives, expressed the way the platform can actually
  report it: **no dropped frames during sustained typing**, allowing at most one.
- State explicitly that end-to-end photon latency is not exposed to page JavaScript and is therefore
  not a CI-gateable quantity.

**Alternatives considered**: measuring on a high-refresh-rate display to shrink the vsync floor
(rejected: makes the budget a property of the test machine, not the software); keeping the budget
and accepting a permanently red gate (rejected outright — Principle V forbids disabling a failing
gate, so an unpassable gate would poison the whole enforcement mechanism); silently reinterpreting
"input event" as handler start (rejected: that is exactly the quiet budget-reinterpretation the
constitution's measurement-point rule exists to prevent).

**This is the spike working as designed**, and cheaply: the architecture question is answered before
implementation rather than after.

### D7: Scroll — assert dropped frames, not frame time

**Decision**: replace "scroll frame time p95 under 16 ms" with a dropped-frame assertion over the
scroll window, driven by synthetic gestures and measured from a Chromium trace.

**Rationale**: "frame time p95" has no clean API behind it. Measuring via `requestAnimationFrame`
deltas is a documented anti-pattern — it blocks the critical path, starves idle callbacks, and is
blind to compositor-only and GPU-thread updates, which is precisely where scroll jank lives. The
Long Animation Frames API has a **fixed, non-configurable 50 ms threshold**, so it cannot assert a
16 ms budget either, though it is an excellent on-failure diagnostic that names the offending
script. Chromium's own smoothness work standardised on percent-dropped-frames, and its benchmarks
drive scrolling through `gpuBenchmarking.smoothScrollBy` (requires `--enable-gpu-benchmarking`)
while capturing a trace — far more faithful than injecting wheel events.

**Alternatives considered**: rAF deltas (anti-pattern, above); LoAF as the gate (threshold too
coarse); Perfetto `EventLatency` analysis, which is the only source covering compositor, GPU and
display swap — deferred as too heavy for a PR gate, but the right tool if a scroll failure needs
diagnosing.

**Consequence**: SC-002 needs restating alongside the B1 amendment. Both belong in the same ADR.

### D8: Backend benchmarks — `me.champeau.jmh` 0.7.3, JMH pinned to 1.37

**Decision**: the `me.champeau.jmh` Gradle plugin at 0.7.3 with `jmhVersion = "1.37"`, JSON result
format, benchmarks in `src/jmh/java`, and `generatorType` left at its default.

**Rationale and traps**, each verified rather than assumed:

- The plugin still defaults to **JMH 1.36**, so 1.37 must be pinned explicitly.
- **Leaving `generatorType` at its default is a correctness requirement on Java 25, not a
  preference.** JMH 1.37 pins ASM 9.0, which understands class files only up to Java 16. The default
  generator is reflection-based and never parses bytecode, so it is unaffected; setting
  `generatorType = "asm"` would fail on Java 17+ bytecode, and on Java 25 certainly.
- **Gradle 9 compatibility is an open risk.** Support is merged on the plugin's main branch but
  unreleased as of 0.7.3, and there is a known incompatibility. The Gradle version must be chosen
  with this in mind, and verified early — it is the kind of thing that blocks a build on day one.

### D9: CI enforcement — two-tier, relative comparison only

**Decision**: gate pull requests on relative comparison with deliberately loose thresholds, and do
sensitive regression detection on a separate non-blocking tier.

**Rationale**: hosted CI runners are too noisy for a tight gate, with measured coefficient of
variation around 2.66% across real benchmark suites. The consequence is arithmetic: **a 2%
regression gate on such a runner produces roughly a 45% false-positive rate** — it cries wolf on
about every other run. Reaching a 1% false-positive rate requires a threshold near 7%, at which
point small regressions are invisible anyway. Private-repo runners are 2 vCPU, a materially worse
measurement environment, and no variability guarantee is published at all.

Constitution Principle V forbids disabling a flaky perf gate, which makes gate design a
correctness concern rather than a convenience: a gate that fails randomly will eventually be
disabled by someone, in violation of the rule. The design must therefore be noise-proof from the
start.

- **PR tier, blocking**: baseline and candidate measured back-to-back in the same job on the same
  runner, compared relatively — this cancels the CPU-model and neighbour variance that dominates
  absolute numbers. Warn near 5%, fail near 10%. Do not attempt to catch small regressions here.
- **Main-branch tier, non-blocking**: append each run to a time series and apply change-point
  detection rather than pairwise comparison. Published results favour it strongly — roughly 94%
  accuracy for change-point classification against roughly 84% for previous-versus-current
  comparison — and tooling exists off the shelf.

**Alternatives considered**: absolute thresholds (rejected: dominated by runner variance);
`benchmark-action/github-action-benchmark` alone with a tight `alert-threshold` (usable for the PR
tier with loose thresholds, insufficient as the only mechanism); dedicated or self-hosted runners
(the single most effective fix, and the right answer if budget allows — reduces variance roughly
fivefold); instruction-counting proxies (noise-immune but stop measuring what users feel).

**Consequence for the frontend budgets**: a 16 ms wall-clock gate on a shared 2 vCPU runner with
software rendering measures the runner, not Vega. Browser latency numbers from hosted CI are
directional only; the real gate belongs on dedicated hardware, or on a noise-immune proxy such as
dropped-frame count under synthetic scroll.

---

## Unverified items carried into implementation

Recorded so they are checked early rather than discovered late.

| Item | Why it matters |
|------|----------------|
| Whether `newCDPSession` works against a Playwright-launched Electron context | D7's entire scroll measurement depends on it; Playwright's Electron support is marked experimental |
| Whether plugin 0.7.3 works on Gradle 9 in practice | Blocks the backend benchmark harness on day one if not |
| Exact CDP tracing category strings and Perfetto table names | Needed to parse dropped frames; confirm against a real trace before wiring assertions |
| No published Monaco-vs-CodeMirror benchmark at 50k lines | D1 rests on capability, not measured speed; revisit if Monaco disappoints |
| No official statement that Playwright wall-clock is too coarse for sub-frame budgets | The reasoning is architectural inference; it does not change D5, which measures in-page regardless |

---

## Syntax layer

All figures below are measured, not cited: a 50,072-line / 1.88 MB synthetic Java file, JDK 25,
tree-sitter core 0.26.x with grammar 0.23.5, Linux x86_64. Third-party blog figures circulating for
tree-sitter incremental parsing (single-digit microseconds per edit) are roughly 100× better than
anything reproducible and have no primary source; they are not relied on here.

### D10: JVM binding — `jtreesitter` (official, FFM-based)

**Decision**: `io.github.tree-sitter:jtreesitter` 0.26.1, the tree-sitter organisation's own
Foreign Function & Memory binding, requiring JDK 23+.

**Rationale**: it is the official binding, actively developed, and FFM rather than JNI — which
matters on Java 25 because native access is being locked down. `--illegal-native-access` currently
defaults to `warn` but is documented to become `deny`, so `--enable-native-access` must be set from
day one. Critically, **JNI bindings get no exemption**: JEP 472 restricts `System.loadLibrary` and
native-method binding on the same schedule, so choosing JNI buys nothing and costs maintenance.

**Consequences for packaging**: jtreesitter ships no native libraries and no grammar artifacts. We
build and ship `libtree-sitter` and `libtree-sitter-java` ourselves, loaded through the
`NativeLibraryLookup` SPI so the deployment stays a single artifact. The jar has no `module-info`,
so `--enable-native-access=ALL-UNNAMED` or an `Enable-Native-Access` manifest entry on our own
executable jar is required.

**Alternatives considered**:
- *`bonede/tree-sitter-ng`* — JNI, ships natives for five platform triples, which is genuinely
  convenient. Rejected on two grounds: an open SIGSEGV in `ts_query_cursor__advance` **with the Java
  grammar specifically**, unresolved since 2024, which is a JVM-crashing bug in a long-lived server
  process; and its shipped native build is the slowest configuration measured (see D11), failing our
  budget out of the box.
- *`seart-group/java-tree-sitter`* — no release in over two years, no Windows support. Rejected.
- *`serenadeai/java-tree-sitter`* — unmaintained since 2023, JitPack only. Rejected.
- *`JetBrains/jsitter`* — dead since 2020, and built on `sun.misc.Unsafe` memory access, which is
  terminally deprecated and will not survive. Rejected.
- *`kotlin-tree-sitter`* — viable, and the right answer on JDK 17–21. Irrelevant at Java 25.

**Pin deliberately**: core is at 0.27.0 while both bindings trail it, and a minor core bump has
already caused an error-recovery regression (see D12). Version pinning is a correctness decision
here, not hygiene.

### D11: Native build flags and parse API are budget-critical

**Decision**: build tree-sitter core and grammar with `-O3 -DNDEBUG`, and parse exclusively through
the chunked `ParseCallback` overload — never `Parser.parse(String, …)`.

**Rationale**: these two choices are the difference between passing and failing SC-004, and both are
easy to get wrong silently.

| Configuration | Full parse | Reparse p95 |
|---|---|---|
| Pre-built native from the rejected JNI binding, `parse(String, …)` | 1629 ms | **8.49 ms** — fails |
| `-O3`, no `NDEBUG`, `parse(String, …)` | 613 ms | 7.73 ms — fails |
| `-O3 -DNDEBUG`, `parse(String, …)` | 301 ms | 3.54 ms — passes |
| **`-O3 -DNDEBUG`, `ParseCallback`** | **330 ms** | **0.70 ms** — passes with 7× headroom |

- Omitting `NDEBUG` leaves tree-sitter's hot-path assertions compiled in, roughly doubling cost.
- `Parser.parse(String, …)` performs a full UTF-8 conversion and native copy on **every call** — an
  O(file size) cost per keystroke, which is precisely the whole-file work Principle VI forbids,
  hidden inside an innocuous-looking API choice.

**Carried risks**: repeatedly building and discarding 50k-line trees produced ~26 ms outliers from
allocator churn — a tail risk for a long-lived server, and the reason SC-004 is specified at p95
rather than p99. All measurements are Linux; macOS and Windows native packaging is unverified. The
test file was synthetic and ASCII-only, so real Java with deep generics, annotations and text blocks
must be re-measured before the numbers are locked as baselines.

### D12: SC-006 is achievable — but `changedRanges` must not drive re-highlighting

**Decision**: treat `ts_tree_get_changed_ranges` as a cheap conservative filter, never as the
re-highlight region.

**Finding**, and it is the sharpest result of this research: an unclosed brace **does** globally
restructure the tree — top-level children collapsed from 453 to 7, with the `MISSING '}'` node
landing at end-of-file some 50,000 lines from the edit. Driving re-highlighting off `changedRanges`
directly would repaint the entire document the first time a user types `{`:

| Edit | Changed lines reported |
|---|---|
| Space inside a method body | 0 |
| Unterminated string literal | 17 |
| Typing `{` | 25,007 (50% of file) |
| Deleting a `}` closing an inner block | 50,052 (100%) |

**But the highlighting damage is genuinely local.** Running the grammar's own highlight query
against valid and damaged files and diffing captures: within 5–40 lines of the edit, 4 of 147
captures changed; at 200+ lines, in mid-file, and in the final 60 lines, **zero captures changed**
in every damage pattern tested. The catastrophic-looking reparent is invisible to the highlighter,
because highlight patterns match local constructs that survive it.

So SC-006 holds — but only because of how we narrow, not automatically. Naive implementation fails
it on the first keystroke.

**Carried risk**: error recovery is explicitly undocumented behaviour with no grammar-author
control, and it is **not stable across versions** — a core minor bump has already regressed it such
that a stray token damaged a valid declaration above it. Mitigation: pin the core version, and add a
regression test that asserts damage locality directly, so a dependency bump cannot quietly break
SC-006.

### D13: Narrow by edit proximity, not by viewport

**Decision**: after reparsing, re-run the highlight query over `changedRanges` intersected with a
bounded window around the edit, then diff the resulting tokens against the previous set and emit
only real differences as the semantic-token delta.

**Rationale**: the highlight query costs **320 ms over the whole 50k-line file** but **0.31 ms for a
60-line window** and 1.26 ms for 200 lines — roughly a thousandfold difference, and cheaper than the
reparse itself. Narrowing is what makes SC-004 achievable; the token diff is what makes SC-006 true,
since D12's data says the diff will be empty beyond ~40 lines.

The obvious narrowing key is the client's viewport — but LSP has no viewport notification, and
adding a `vega/` message for one would expand the custom protocol surface that Principle VIII
tells us to keep minimal. **The edit location is a sufficient proxy: a user cannot type outside
their own viewport.** Narrowing by edit proximity therefore needs no new protocol message at all,
and scrolling requires no re-highlight because the tokens already exist in the full array.

**Open question this spike must answer, deliberately not pre-solved**: bounding the re-query means
tokens outside the window are assumed unchanged. D12's measurements say that assumption holds for
the patterns tested, but "tested" is four damage patterns, not a proof. The spike should instrument
how often a background full re-query would have disagreed with the narrowed result. If the answer is
never, the fast path stands alone; if not, a background reconciliation pass emitting a corrective
delta is the fix. Building that reconciliation now would be speculative complexity — measuring first
is the point of a spike.

### D14: SC-005 does not survive contact with the numbers

**Finding**: SC-005 requires "the first full analysis of the 50,000-line fixture" under 500 ms.
Measured, on the recommended configuration, the two components are **~300 ms to parse** and
**~320 ms to run the highlight query over the whole file** — about **620 ms combined**. Read as
parse-plus-tokenize, SC-005 fails. Read as parse alone, it passes with margin.

**Recommendation**: state which is meant, in the same ADR as D6 and D7. The preferred resolution is
to split it into two criteria — full parse under 500 ms, and full tokenization under 800 ms — for a
reason beyond bookkeeping: under D3's range-first strategy, whole-file tokenization is deliberately
**not on the cold-start critical path**. Only the initial viewport's tokens gate first paint, so
Budget B3 is unaffected by the 620 ms figure. Collapsing both numbers into one criterion hides that
distinction and would fail the spike over a budget that never mattered to the user experience.

---

## Implementation findings (Phases 1–2, 2026-09-05)

Recorded because they resolve risks this document previously carried as unverified, and because two
of them would otherwise be rediscovered painfully.

### F1: Gradle 9 + JMH is fine; Gradle 8 + Java 25 is not

D8 carried "whether plugin 0.7.3 works on Gradle 9 in practice" as an open risk that could block the
backend benchmark harness on day one. **It does work** — the plugin configures and the `jmh` task
runs to completion on Gradle 9.4.1 with Java 25, producing results (a trivial benchmark measured
0.580 ± 0.034 ns/op).

The actual blocker was the opposite of the one predicted: **Gradle 8.14.3 cannot run on Java 25 at
all**, failing immediately with the JVM version as its only message. The wrapper is therefore pinned
to 9.4.1, and the risk D8 raised is closed.

**New CI constraint discovered while proving it**: default JMH parameters took **8m30s for a single
trivial benchmark**. The real budget benchmarks must set explicit fork and iteration counts or the
performance job becomes unusable as a pull-request gate. Noted in `.github/workflows/ci.yml`.

### F2: Bytecode-reading tools must be Java 25 capable — and fail silently when they are not

ArchUnit 1.3.0 **imported zero classes** from a Java 25 codebase. The boundary rules did not report a
violation; they reported "failed to check any classes", because ArchUnit's bundled ASM cannot read
class-file major version 69. Upgrading to 1.5.0 fixed it.

This is the same trap D8 identified for JMH's ASM generator, and it generalises: **any tool that
parses bytecode needs an ASM new enough for the target release, and the failure mode is silence
rather than an error.** ArchUnit only failed loudly here because its default `failOnEmptyShould`
happens to be on — had that been disabled, as projects commonly do, the constitutional boundary
rules would have passed vacuously while checking nothing, which is strictly worse than not having
them.

Consequence adopted: the ArchUnit rules were verified by deliberately introducing a forbidden import
into the core, observing the rule fail, then removing it. A boundary rule that has never been seen
to fail is not evidence of anything.

### F3: The read-your-writes overlay is not needed in this spike

Task T022 called for a `DocumentOverlay` serving reads from edits not yet persisted, per Principle
IV. Building it would have been speculative: that clause exists because index writes are batched and
asynchronous, and **this spike has no persistent index**, so no pending batch can exist and the
document mirror is already the latest state.

The requirement is satisfied without the class, and the guarantee is asserted directly by a test
rather than left implicit. The overlay should be introduced with the first feature that persists
asynchronously — at which point there will be real divergence for it to reconcile, and a test able
to prove it does.
