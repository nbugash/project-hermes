# Data Model: Spike 001 — Large-File Editor Responsiveness

Derived from [spec.md](./spec.md) Key Entities and Functional Requirements. Entities are described
at the domain level; wire encodings live in [contracts/](./contracts/), and class ownership belongs
to `design.md`.

The organising idea: **every analysis result carries the document version it was computed from.**
That single field is what makes asynchronous highlighting safe under fast typing, and most
validation rules below exist to protect it.

## Document

The file being edited. Exactly one is open at a time in this spike.

| Field | Type | Notes |
|-------|------|-------|
| `uri` | document URI | Identifies the document across the protocol boundary |
| `path` | absolute filesystem path | Resolved by the backend, never by the editor |
| `version` | monotonic integer | Starts at 1 on open; incremented by every edit |
| `text` | character buffer | Authoritative copy lives in the editor process |
| `encoding` | character encoding | Detected by the backend at open; preserved on save |
| `lineEnding` | `LF` \| `CRLF` \| `mixed` | Detected at open; preserved on save |
| `hasTrailingNewline` | boolean | Preserved on save |
| `isModified` | boolean | Buffer differs from last saved state |

**Ownership split (FR-002, FR-007, FR-021)**: the editor process owns `text` while the document is
open and may mutate it without consulting the backend. The backend owns all filesystem access —
it produces the initial `text`, `encoding`, `lineEnding` and `hasTrailingNewline` at open, and it
is the only party that writes bytes at save. The backend keeps a mirror of `text`, synchronised by
incremental change notifications, purely so it can parse.

**Validation rules**
- `version` MUST increase by exactly one per applied edit and MUST never be reused or decrease.
- The backend MUST reject a change notification whose base version is not the version it currently
  holds, rather than applying it out of order.
- `encoding`, `lineEnding` and `hasTrailingNewline` are read at open and MUST NOT be altered by
  editing; they are inputs to save (FR-020).

**State transitions**

```
Closed --open requested--> Opening --content received--> Open
Open --edit--> Open (version + 1)
Open --save requested--> Saving --write ok--> Open (isModified = false)
Saving --write failed--> Open (isModified unchanged, error surfaced)  [FR-022]
Open --close--> Closed
```

## Edit

A single change to the document: an insertion, a deletion, a paste, or one undo/redo step.

| Field | Type | Notes |
|-------|------|-------|
| `baseVersion` | integer | Document version this edit applies to |
| `resultVersion` | integer | `baseVersion + 1` |
| `range` | start/end position | The replaced span |
| `newText` | string | Replacement text; empty for a pure deletion |

**Validation rules**
- Edits MUST be transmitted as incremental ranges. Whole-document replacement per keystroke is a
  rejected design (FR-009, Constitution Principle VI).
- A single paste is one Edit with a large `newText`, not many Edits — it must not be expanded into
  per-character changes.
- Undo and redo produce ordinary Edits with new increasing versions; the editor does not rewind
  `version` (spec Assumptions).

## Highlight Result

Styling for some portion of the document, computed by the syntax layer.

| Field | Type | Notes |
|-------|------|-------|
| `documentVersion` | integer | The version this result was computed from |
| `resultId` | opaque identifier | Lets the next result be expressed as a delta against this one |
| `tokens` | ordered token list | Position, length and style classification |
| `isFull` | boolean | Full document result, or a delta against `resultId` |

**Validation rules (FR-008, FR-010)**
- A result whose `documentVersion` is older than the editor's current version MUST be discarded and
  MUST NOT be painted.
- A delta MUST only be applied on top of the exact `resultId` it was computed against. If the
  editor no longer holds that baseline, it MUST request a full result rather than guess.
- Tokens MUST be ordered and non-overlapping, so application is a single pass.
- The first result after open is full; subsequent results are deltas whenever a baseline exists.

## Changed Range

The span the syntax layer reports as affected by an edit — the mechanism that keeps re-highlighting
proportional to the edit (FR-009) and bounds the visible damage from a syntax error (FR-015).

| Field | Type | Notes |
|-------|------|-------|
| `documentVersion` | integer | Version the range refers to |
| `start`, `end` | positions | Affected span in the new document state |

**Validation rules**
- A single-character edit in valid code SHOULD produce a changed range local to the edit; a changed
  range spanning the whole document indicates the incremental path has failed and MUST fail the
  corresponding benchmark rather than silently degrade.

## Backend Status

Drives the degraded-start contract (FR-004) — the editor must be able to say what is happening
before the backend is ready.

| Value | Meaning |
|-------|---------|
| `Starting` | Process launched, not yet accepting requests |
| `Indexing` | Accepting requests; first full parse in progress |
| `Ready` | First full parse complete |
| `Failed` | Backend unavailable; reason retained for display |

**Validation rules**
- The shell MUST render and accept input in every state, including `Starting` and `Failed`
  (FR-004, Constitution Budget B3 degraded-state contract).
- `Failed` MUST be visible to the developer rather than presenting as highlighting that quietly
  stopped updating (spec Edge Cases).

## Timing Sample

One measured interaction, feeding both the timing panel (User Story 5) and the CI benchmarks.

| Field | Type | Notes |
|-------|------|-------|
| `id` | integer | Sequence number |
| `kind` | `keystroke` \| `scroll` | Which budget the sample belongs to |
| `inputToRenderMs` | number | Input event to committed frame |
| `roundTripMs` | number \| absent | Present only when the interaction crossed the boundary |
| `correlationId` | string \| absent | Links the sample to backend log lines (Principle IX) |
| `documentVersion` | integer | Document state the sample was taken against |

**Validation rules**
- `inputToRenderMs` MUST be derived from in-page performance marks around the committed frame, not
  from an external harness clock (Constitution Principle V).
- `roundTripMs` is absent for interactions that never reached the backend; it MUST NOT be inferred
  or back-filled, since most keystrokes are expected to have no round trip at all.
- Recording a sample MUST NOT push the measured interaction out of budget (FR-019).

## Latency Window

Rolling aggregate the timing panel displays.

| Field | Type | Notes |
|-------|------|-------|
| `kind` | `keystroke` \| `scroll` | One window per interaction kind |
| `capacity` | integer | Fixed sample count; oldest evicted |
| `p50`, `p95` | number | Recomputed as samples arrive |

**Validation rules**
- Bounded capacity — the window MUST NOT grow with session length.
- Live only; no persistence or cross-run comparison in this spike (spec Assumptions). CI benchmarks
  own historical comparison.

## Save Outcome

| Field | Type | Notes |
|-------|------|-------|
| `documentVersion` | integer | Version whose bytes were written |
| `status` | `written` \| `failed` | |
| `failureReason` | string \| absent | Surfaced to the developer on failure |

**Validation rules**
- On `written`, on-disk bytes MUST equal the buffer serialised with the document's original
  `encoding`, `lineEnding` and `hasTrailingNewline` (FR-020).
- Saving an unmodified document MUST leave the file byte-identical (SC-010).
- On `failed`, the buffer MUST be preserved unchanged (FR-022).

## Reference Fixture

| Field | Type | Notes |
|-------|------|-------|
| `path` | repo-relative path | Under `/fixtures` |
| `lineCount` | integer | 50,000 |
| `contentHash` | digest | Pins the exact bytes benchmarks were measured against |

**Validation rules**
- Deterministic and checked in. If `contentHash` changes, prior benchmark baselines are void and
  MUST be re-established rather than compared across (FR-025).

## Entity Relationships

```
Document 1 --- * Edit                  (each Edit advances Document.version)
Document 1 --- * HighlightResult       (each keyed to a documentVersion)
HighlightResult 1 --- * ChangedRange   (what the edit affected)
Document 1 --- * TimingSample          (each sample records the version it observed)
TimingSample * --- 1 LatencyWindow     (aggregated by kind)
Document 1 --- * SaveOutcome
ReferenceFixture 1 --- 1 Document      (in benchmark runs)
```

## Not Modelled Here

- **Persistent index entities.** Out of scope, and `plan.md` Complexity Tracking recommends
  removing the port entirely rather than modelling storage nothing writes to.
- **Project, workspace, multi-file state.** One document at a time in this spike.
- **Completion, diagnostics, symbols.** Out of scope; they carry the semantic model this spike
  deliberately avoids.
