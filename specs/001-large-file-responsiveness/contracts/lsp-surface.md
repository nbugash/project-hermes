# Standard LSP Surface: Spike 001

Only these standard methods are used. Anything not listed is out of scope for the spike.

Spec baseline: **LSP 3.17** (last stable). 3.18 is published but its own text says it is under
development; where this spike depends on a 3.18-only method, that is called out explicitly.

## Lifecycle

### `initialize` / `initialized`

Client → server, once at startup.

- Client declares its supported `vega/` protocol version in `initializationOptions`. A mismatch
  MUST fail startup with a clear message rather than degrade silently.
- Server declares `semanticTokensProvider` with its legend, `full: true` and `full.delta: true`,
  and `range: true`.
- **Position encoding MUST be negotiated explicitly.** Semantic token offsets are relative, so a
  client/server disagreement on UTF-16 versus UTF-8 does not corrupt one token — it shifts *every
  subsequent token in the document*. Java string literals containing non-BMP characters are enough
  to trigger it. The negotiated encoding is asserted in an integration test rather than assumed.
- The editor MUST NOT block window presentation on this handshake completing (FR-004, Budget B3).

## Document synchronisation

### `textDocument/didOpen`

Client → server, after the client has obtained content via `vega/readDocument`.

- Carries the text the client received from the server moments earlier. This round trip is
  redundant on its face and is accepted deliberately: the spec states that after `didOpen` "the
  document's content is now managed by the client and the server must not try to read the
  document's content using the document's Uri." Re-sending the text is what transfers ownership
  and guarantees both sides agree on the exact starting bytes and version.
- Sets `version = 1`.

### `textDocument/didChange`

Client → server, once per edit.

- MUST use incremental content changes (`TextDocumentSyncKind.Incremental`). Full-document sync is
  forbidden by Constitution Principle VI.
- One notification per Edit; a large paste is one change, not many.
- `version` MUST increase by exactly one and MUST match the client's document version.
- **Not on the input-to-render path.** The client renders the character first and notifies
  afterwards (FR-007).

### `textDocument/didClose`

Client → server, on close. Server releases the mirror and any parse state.

## Highlighting

Three requests, used in a deliberate sequence.

### `textDocument/semanticTokens/range`

Client → server, once, immediately after `didOpen`, for the initial viewport only.

- Exists to get colour on screen inside Budget B3 without waiting for the whole 50,000-line file to
  be tokenized. This is the spec's own recommended use of `/range`.
- Returns `SemanticTokens | null`. **No delta variant exists for `/range`.**
- A server may compute a broader range than requested; if it does, the tokens for that broader
  range must be complete and correct.

### `textDocument/semanticTokens/full`

Client → server, in the background right after the range request, and again whenever a delta
cannot be applied.

- Response carries a `resultId` and token data for the whole document.
- Requesting full tokens in the background is what keeps scrolling free of unstyled regions
  (FR-013) — the spec recommends exactly this pairing for flicker-free scrolling.
- Bound by the 800 ms full-tokenization budget (SC-005b); the underlying parse is bound
  separately at 500 ms (SC-005a). Deliberately off the cold-start critical path — `/range` gates
  first paint, not this request.

### `textDocument/semanticTokens/full/delta`

Client → server, after every edit for which the client holds a baseline `resultId`.

- Request carries `previousResultId`. Response is `SemanticTokens | SemanticTokensDelta | null`.
- Bound by the 5 ms p95 re-analysis budget (SC-004).

**Delta handling rules.** Each of these is a correctness requirement, not an optimisation, and each
gets a test:

1. **Edits are splices on the flat integer array, and they are not sorted.** All edits in one
   response are relative to the *same* prior array, not applied sequentially. The client MUST sort
   them and apply from back to front. Applying in received order silently corrupts the token array
   and produces highlighting that is wrong rather than merely stale.
2. **`resultId` chains through both response kinds.** A delta response carries its own `resultId`;
   the stored baseline MUST be updated after *every* response, full or delta, not only after full
   ones.
3. **A server may legally answer a delta request with a full result**, regardless of the capability
   it advertised. The client MUST handle both shapes. This is not theoretical — shipped servers
   have advertised `full.delta` and always returned full data.
4. **Version skew.** A result MUST be applied only if it corresponds to the client's current
   document version; anything older is discarded and never painted (FR-010). Responses may arrive
   out of order under fast typing, so request order MUST NOT be assumed to equal response order.
5. **Missing baseline.** If a response references a baseline the client no longer holds, the client
   MUST request a full result rather than attempt reconstruction.
6. **Server-side cancellation.** With `serverCancelSupport`, the server may return
   `ErrorCodes.ServerCancelled`, which obliges the client to retrigger. Fast typing will hit this
   routinely, so it is a normal path, not an error path.
7. **Retained state on both sides is an unbounded-memory hazard.** The client holds the previous
   integer array; the server holds per-`resultId` state to diff against. Both MUST bound what they
   retain — one baseline client-side, and a server-side cache the client can release.
8. **Token flags.** `overlappingTokenSupport` and `multilineTokenSupport` default to false. The
   client declares what it supports and the server MUST respect it; a multiline Java comment or
   text block is the case that exposes a mismatch.

### `$/cancelRequest`

Client → server, when a highlight request is superseded by a newer edit.

- The server MUST actually stop the in-flight work, not merely drop the reply (Constitution
  Principle IV). A cancelled request MUST free its worker thread promptly.

## Progress and messages

### `$/progress`

Server → client, for the initial full parse. Drives the `Indexing → Ready` transition the shell
shows during degraded start.

### `window/showMessage`

Server → client, for failures the developer must see, such as an unreadable file.

## Not used in this spike

`textDocument/didSave` (superseded by `vega/saveDocument`, which performs the write rather than
announcing it), `workspace/semanticTokens/refresh` (a global recompute this spike has no cause to
trigger), completion, diagnostics, definition, references, formatting, workspace symbols, file
watching, and configuration.
