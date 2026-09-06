# Feature Specification: Spike 001 — Large-File Editor Responsiveness

**Feature Branch**: `001-large-file-responsiveness`

**Created**: 2026-09-05

**Status**: Draft

**Input**: User description: "Spike 001 — Large-file editor responsiveness. Validate Vega's
two-process architecture meets its performance budgets before further features are built.
Local-only spike covering open, edit, scroll, broken-code tolerance, a timing panel and save,
against a reference 50,000-line Java fixture."

## Overview

This is a risk-reduction spike, not a product increment. Its purpose is to answer one question
with measurements: **can the two-process architecture mandated by the constitution hold the
editor-responsiveness budgets on a 50,000-line file?**

If the answer is no, the deliverable is an ADR proposing a constitutional amendment. Working
around a missed budget — by moving analysis into the editor process, by dropping the protocol
boundary, or by relaxing a budget silently — is explicitly not an acceptable outcome.

Scope note carried forward for planning: this spike exercises Budget B1 (B1a handler-to-frame and
B1b dropped frames, as amended by ADR-0001) and Budget B3 (cold start), plus three spike-local
budgets. It does **not** exercise Budget B2
(completion popup under 100 ms p95), because completion, diagnostics and the persistent index
are all out of scope. B2 remains unvalidated after this spike completes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open a large file (Priority: P1)

A developer launches Vega with the path to a single Java source file of up to 50,000 lines,
supplying it either as a command-line argument or through an Open File dialog. The file appears
with Java syntax highlighting, and the window is ready to edit within the cold-start budget. The
file's contents are supplied by the language backend across the protocol boundary; the editor
process never reads the file from disk itself, so the identical flow will work when the backend
later runs on a remote machine.

**Why this priority**: Nothing else in the spike can be measured until a large file can be
opened and displayed. It also establishes the backend-as-file-gateway flow that every other
story depends on.

**Independent Test**: Launch with the fixture path and observe a highlighted, scrollable,
editable document. Delivers the ability to view a large file — the minimum viable slice.

**Acceptance Scenarios**:

1. **Given** Vega is not running, **When** the developer launches it with the path to the
   50,000-line fixture, **Then** the window becomes visible and interactive before the backend
   has finished initialising, and the file is displayed with Java highlighting and accepting
   input within 2 seconds of launch.
2. **Given** Vega is running with no file open, **When** the developer chooses the fixture
   through the Open File dialog, **Then** the file is displayed with Java highlighting.
3. **Given** a file is being opened, **When** the editor process needs the file's contents,
   **Then** it obtains them from the backend across the protocol boundary and performs no direct
   filesystem read of that file.
4. **Given** the backend has not yet finished initialising, **When** the developer interacts with
   the window, **Then** the shell responds and communicates that language features are still
   starting, rather than appearing frozen or empty without explanation.

---

### User Story 2 - Edit with no perceptible lag (Priority: P2)

A developer types, deletes, cuts, pastes, undoes and redoes anywhere in the file, including near
the very end, and uses standard keyboard navigation. Characters appear as fast as they are typed
regardless of position in the file. Highlighting updates incrementally; only the region affected
by the edit is re-highlighted.

**Why this priority**: This is the spike's central hypothesis. Keystroke latency at line 50,000
is the single riskiest unknown in the architecture, because it is where a whole-file design would
first become visible to the user.

**Independent Test**: With the fixture open, type sustained bursts at the top, middle and end of
the file while recording input-to-render latency. Delivers a defensible answer on Budget B1.

**Acceptance Scenarios**:

1. **Given** the fixture is open, **When** the developer types continuously at line 1, at line
   25,000 and at line 50,000, **Then** the editor's own work per keystroke — input handler start to
   committed frame — stays under 16 ms at p95 in all three positions, and sustained typing drops at
   most one frame.
2. **Given** the developer has typed a character, **When** highlighting updates, **Then** only
   the region affected by that edit is re-highlighted; unaffected regions are not repainted.
3. **Given** the developer performs cut, paste, undo and redo, **When** each operation completes,
   **Then** the document content is correct and the same latency budget holds.
4. **Given** the developer types faster than highlighting can be recomputed, **When** results for
   superseded keystrokes would arrive, **Then** those results are discarded or cancelled and never
   overwrite the current view with stale highlighting.
5. **Given** an edit has been made, **When** the developer immediately continues editing, **Then**
   every subsequent operation observes the preceding edit — no operation may depend on background
   persistence or analysis having completed first.

---

### User Story 3 - Scroll smoothly (Priority: P3)

A developer scrolls through the entire file using the mouse wheel, keyboard paging and by dragging
the scrollbar, and sees continuous, correctly highlighted content throughout.

**Why this priority**: Scrolling exposes a different failure mode than typing — per-frame render
cost and on-demand content fetching — and a large file is exactly where blank or unhighlighted
regions appear. It ranks below editing because a laggy scroll is less fatal to the spike's
conclusion than laggy typing.

**Independent Test**: Scroll from the first line to the last by each of the three input methods
while recording frame times. Delivers a defensible answer on rendering cost at scale.

**Acceptance Scenarios**:

1. **Given** the fixture is open, **When** the developer scrolls the full length of the file by
   wheel, by keyboard and by scrollbar drag, **Then** dropped frames over the scroll window stay
   within the defined threshold for each method.
2. **Given** the developer scrolls rapidly to a region not previously visited, **When** that
   region is displayed, **Then** it appears fully highlighted with no blank, placeholder or
   unstyled interval visible to the user.
3. **Given** the backend is still performing its initial parse, **When** the developer scrolls,
   **Then** scrolling remains responsive and text remains readable, with highlighting applied as
   it becomes available.

---

### User Story 4 - Tolerate broken code (Priority: P4)

A developer's file is syntactically invalid part-way through an edit — an unclosed brace, a
half-typed statement. Highlighting degrades gracefully in the neighbourhood of the error and
recovers as the error is fixed. The editor never blanks, freezes or loses highlighting across the
whole file.

**Why this priority**: Source code is syntactically invalid most of the time it is being typed,
so this is the normal case rather than an edge case. It ranks below scrolling only because it is
a correctness property of the highlighting path that Story 2 already establishes.

**Independent Test**: Insert an unclosed brace at line 100 of the fixture, observe the extent of
re-highlighting, then close the brace and observe recovery. Delivers a defensible answer on error
tolerance.

**Acceptance Scenarios**:

1. **Given** the fixture is open and valid, **When** the developer introduces an unclosed brace at
   line 100, **Then** no re-highlighting is visible beyond the block enclosing line 100.
2. **Given** the file is syntactically invalid, **When** the developer continues typing, **Then**
   the editor remains responsive within the same latency budget and never blanks or freezes.
3. **Given** the file is syntactically invalid, **When** the developer corrects the error, **Then**
   highlighting returns to the state it had before the error was introduced.

---

### User Story 5 - See the timing (Priority: P5)

A developer opens a built-in timing panel showing, per keystroke and per scroll event, the time
from input to render and the backend round-trip time, together with rolling p50 and p95 figures.

**Why this priority**: The panel is how the budgets get judged during daily use rather than only
in the benchmark suite, and the constitution makes it a first-class shipped feature rather than a
debug aid. It ranks below the behaviours it measures because those must exist first.

**Independent Test**: Open the panel, type and scroll, and confirm the displayed figures track
what the benchmark suite reports for the same actions. Delivers live visibility into the budgets.

**Acceptance Scenarios**:

1. **Given** the fixture is open, **When** the developer opens the timing panel and types, **Then**
   the panel shows input-to-render time per keystroke and a rolling p50 and p95.
2. **Given** the timing panel is open, **When** an interaction crosses the protocol boundary,
   **Then** the panel shows the backend round-trip time for that interaction separately from the
   input-to-render time.
3. **Given** the timing panel is open, **When** the developer types continuously, **Then** the
   panel's own presence does not push input-to-render time over its budget.
4. **Given** a build of Vega intended for daily use, **When** the developer looks for the timing
   panel, **Then** it is available without a developer-only flag or a special build.

---

### User Story 6 - Save (Priority: P6)

A developer saves the file. The content written to disk matches the editor's content exactly. The
write is performed by the backend in response to a protocol request, not by the editor process.

**Why this priority**: Without save the spike is a viewer rather than an editor, but save is the
least uncertain behaviour here and validates no budget. It does complete the
backend-as-file-gateway story that Story 1 begins.

**Independent Test**: Edit the fixture, save, and compare the file on disk byte-for-byte against
the editor's buffer. Delivers durable edits.

**Acceptance Scenarios**:

1. **Given** the developer has edited the file, **When** they save, **Then** the bytes on disk
   correspond exactly to the editor's content, preserving the file's original character encoding
   and line-ending style.
2. **Given** an unmodified file, **When** the developer saves it, **Then** the file on disk is
   byte-identical to what it was before the save.
3. **Given** a save is requested, **When** the write occurs, **Then** it is performed by the
   backend in response to a protocol request and the editor process performs no direct write.
4. **Given** a save fails, for example because the file is read-only, **When** the failure is
   detected, **Then** the developer is told the save failed and the editor's content is preserved
   unchanged.

---

### Edge Cases

- **File exceeds the supported size.** A file materially larger than 50,000 lines is opened. The
  system must either handle it within budget or state plainly that it exceeds the spike's
  supported size, rather than degrading silently into an unresponsive window.
- **Pathological line shapes.** A file whose content is one 50,000-token line, or which contains
  extremely long individual lines, stresses a different dimension than line count.
- **Non-UTF-8 encoding, and mixed or CRLF line endings.** Save must round-trip these unchanged.
- **File without a trailing newline.** Save must not add or remove one.
- **File changed on disk while open.** The editor's buffer and the file have diverged; the spike
  must define whether it detects this and must not silently discard either side.
- **File unreadable, missing, or permission-denied at open.** The window must still start and must
  explain the failure.
- **Empty or one-line file.** The large-file paths must not assume a minimum size.
- **Backend process dies mid-session.** Editing must not lose content, and the failure must be
  visible rather than presenting as highlighting that quietly stopped updating.
- **Large paste.** Pasting thousands of lines in one operation is a single edit far larger than a
  keystroke and must not violate the responsiveness contract or trigger whole-file re-analysis.
- **Interaction during initial parse.** Typing and scrolling before the first parse completes must
  behave, not queue up into a stall.
- **Undo across a highlighting update in flight.** Undo must not resurrect highlighting belonging
  to a superseded document state.

## Requirements *(mandatory)*

### Functional Requirements

**Opening and file access**

- **FR-001**: Vega MUST accept a single file path at launch as a command-line argument, and MUST
  also allow the developer to choose a file through an Open File dialog.
- **FR-002**: The editor process MUST obtain the contents of an opened file from the backend
  across the protocol boundary. It MUST NOT read that file directly from the filesystem.
- **FR-003**: Because obtaining file contents from the backend and writing them on save are not
  expressible in the standard protocol, they MUST be defined as documented custom protocol
  extensions in the shared `/protocol` module, per Constitution Principle I. No side channel
  between the two processes is permitted.
- **FR-004**: The window MUST become visible and interactive before backend initialisation
  completes, and MUST indicate that language features are still starting.
- **FR-005**: The displayed file MUST carry Java syntax highlighting.

**Editing**

- **FR-006**: The developer MUST be able to insert, delete, cut, paste, undo, redo and navigate by
  keyboard at any position in the file.
- **FR-007**: The editor process MUST own the in-memory buffer and MUST render typed characters
  without waiting for any backend response, so that no protocol round trip sits on the
  input-to-render path (Constitution Principle I and Budget B1).
- **FR-008**: Highlighting MUST be applied asynchronously: text appears immediately, and updated
  highlighting is applied when the backend result arrives.
- **FR-009**: Re-highlighting after an edit MUST be incremental. Only the affected region may be
  recomputed and repainted; whole-file re-analysis per keystroke is rejected (Constitution
  Principle VI).
- **FR-010**: Highlighting results belonging to a superseded document state MUST be discarded and
  MUST NOT be painted. In-flight work for superseded states MUST be cancelled rather than merely
  having its result ignored (Constitution Principle IV).
- **FR-011**: Every operation MUST observe all preceding edits. No behaviour may depend on
  background persistence or analysis having completed.

**Scrolling and rendering**

- **FR-012**: The developer MUST be able to scroll the full file by mouse wheel, by keyboard and
  by scrollbar drag.
- **FR-013**: No blank, placeholder or unstyled region may be visible to the developer during or
  after scrolling, including in regions not previously visited.

**Error tolerance**

- **FR-014**: Highlighting MUST tolerate syntactically invalid content, degrading locally around
  the error rather than failing across the file.
- **FR-015**: Introducing a syntax error MUST NOT cause visible re-highlighting beyond the block
  enclosing the error.
- **FR-016**: The editor MUST NOT blank, freeze or stop accepting input while the file is
  syntactically invalid, and highlighting MUST recover when the error is corrected.

**Timing panel**

- **FR-017**: Vega MUST provide a built-in timing panel showing, per keystroke and per scroll
  event, input-to-render time and backend round-trip time, plus rolling p50 and p95.
- **FR-018**: The timing panel MUST be reachable in builds intended for daily use, without a
  developer-only flag or debug build (Constitution Principle IX).
- **FR-019**: Measurement overhead MUST NOT push any measured interaction outside its budget.

**Saving**

- **FR-020**: The developer MUST be able to save, and the resulting bytes on disk MUST correspond
  exactly to the editor's content, preserving the file's original encoding and line-ending style.
- **FR-021**: The write MUST be performed by the backend in response to a protocol request; the
  editor process MUST NOT write the file directly.
- **FR-022**: A failed save MUST be reported to the developer, with the editor's content preserved.

**Verification**

- **FR-023**: The backend MUST satisfy every backend-side acceptance scenario when driven headless
  by a test protocol client with no editor process running (Constitution Principle II).
- **FR-024**: Every budget in Success Criteria MUST be enforced by an automated benchmark that
  runs in CI, and a regression MUST fail the build (Constitution Principle V).
- **FR-025**: The reference fixture MUST be a deterministic 50,000-line Java file checked into
  `/fixtures`, so that measurements are comparable between runs and between machines of the same
  class.

### Key Entities

- **Document**: The file being edited. Has a path, an original encoding and line-ending style, and
  a content buffer owned by the editor process. Its authoritative on-disk form is read and written
  only by the backend.
- **Edit**: A single change to the document — insertion, deletion, paste, or an undo/redo step —
  identified so that analysis results can be matched to the document state that produced them.
- **Highlight Region**: A styled span of the document produced by the syntax layer, tied to the
  document state it was computed from, and replaceable incrementally.
- **Timing Sample**: One measured interaction — a keystroke or scroll event — carrying
  input-to-render time and, when the interaction crossed the boundary, backend round-trip time.
  Aggregated into rolling p50 and p95.
- **Reference Fixture**: The checked-in 50,000-line Java file that all budgets are measured
  against.

## Success Criteria *(mandatory)*

All criteria are measured against the reference fixture on a fixed machine class.

### Measurable Outcomes

- **SC-001a**: Typing anywhere in a 50,000-line file completes the editor's own work — from input
  handler start to the committed frame — in under 16 ms at p95, verified independently at line 1,
  line 25,000 and line 50,000.
- **SC-001b**: Sustained typing at each of those positions drops at most one frame. Together with
  SC-001a this replaces the original single keystroke-to-render criterion; end-to-end photon latency
  is not observable from page JavaScript and is not asserted. See ADR-0001.
- **SC-002**: Scrolling the full length of the file by wheel, keyboard and scrollbar drag drops no
  more than a defined threshold of frames over the scroll window, measured from a browser trace
  under synthetic gestures. Per-frame time at 16 ms resolution has no API behind it and is not
  asserted. See ADR-0001.
- **SC-003**: From launch to a displayed, editable file is under 2 seconds, and the window is
  visible and interactive before backend initialisation completes.
- **SC-004**: Re-analysis after a single-character edit completes in the backend in under 5 ms at
  p95.
- **SC-005a**: The first full parse of the 50,000-line fixture completes in under 500 ms.
- **SC-005b**: Full-document tokenization of the fixture completes in under 800 ms. This is
  deliberately off the cold-start critical path — only the initial viewport's tokens gate first
  paint — so SC-003 does not depend on it. See ADR-0001.
- **SC-006**: Introducing an unclosed brace at line 100 produces no visible re-highlighting beyond
  the block enclosing line 100.
- **SC-007**: Every backend-side criterion above holds when the backend is driven headless with no
  editor process running.
- **SC-008**: A regression against any criterion above fails the build in CI.
- **SC-009**: A developer can read current p50 and p95 input-to-render and round-trip figures from
  the timing panel while working, without launching a special build.
- **SC-010**: Saving an edited file produces on-disk bytes matching the editor's content exactly,
  and saving an unmodified file leaves the file byte-identical.

## Out of Scope

Excluded deliberately, so that the spike answers one question rather than several:

- Project tree, multiple open files, tabs, split editors
- Completion, diagnostics, go-to-definition, refactoring
- The persistent index, entirely — no implementation and **no port**. A port with zero
  implementations and zero callers is a speculative abstraction no test can exercise; it is
  introduced with the first feature that actually indexes
- Remote backend transport, entirely — **no stub interface**. Remote-readiness is guaranteed
  behaviourally by FR-002, which keeps all file access on the backend side and is enforced by tests;
  an interface with one implementation would prove nothing further
- Debugging (DAP)
- Any language other than Java
- Themes, settings UI, plugins

## Assumptions

Recorded because the feature description did not settle them and the spike proceeds on these
readings. Each is a decision a reviewer can overturn.

- **Cold start covers the whole flow.** The 2-second budget is measured from launch to a
  displayed, editable file — not merely to a visible window. The window appearing early is a
  separate, stricter requirement, not a way of satisfying the 2-second figure. This is the
  strictest reading of the two statements in the description, and it is the one that makes
  SC-003 a real constraint.
- **The editor owns the buffer; the backend owns the file.** The backend reads the file at open
  and writes it at save, but the editor process holds the authoritative in-memory buffer while
  editing. Any other division would put a protocol round trip on the keystroke path, which
  Constitution Principle I forbids. The remote-backend story is preserved because file I/O — the
  part that must follow the file — is what crosses the boundary.
- **Highlighting is asynchronous and always eventually correct.** Because of the above, freshly
  typed text may be briefly unhighlighted or highlighted from the previous state. The spike treats
  a visible lag in *colour* as acceptable and a visible lag in *characters* as a budget failure.
- **Whole-file highlighting is computed up front, but not before first paint.** Initial viewport
  tokens gate first paint; whole-document tokenization follows in the background within SC-005b, so
  scrolling never requires a fresh round trip and SC-002 and FR-013 hold together without charging
  tokenization to the cold-start budget.
- **Undo and redo are editor-local.** They act on the buffer the editor owns and require no
  backend participation.
- **"Visible re-highlighting" in SC-006 means repainted styling that a developer could observe**
  in the viewport — not internal recomputation. Internal re-analysis beyond the enclosing block is
  permitted if nothing outside it changes appearance.
- **The fixture is synthetic but realistic Java**, checked in and unchanging, since a 50,000-line
  single Java file is unusual in real projects and must be reproducible to be a baseline.
- **Timing panel data is live only.** No persistence, export or cross-run comparison in this
  spike; CI benchmarks own historical comparison.
- **A single missed criterion fails the spike.** The response is an ADR proposing an amendment,
  per the feature description — never a workaround that weakens the architecture.

## Dependencies

- The reference 50,000-line Java fixture must exist in `/fixtures` before any budget in Success
  Criteria can be measured. It does not exist yet.
- `/docs/adr` exists. ADR-0001 already amends three of this spike's criteria, before implementation.
- `/docs/tech-radar.md` exists and records the plan-level library choices (editor widget, protocol
  client, syntax binding, benchmark harnesses, packaging).
