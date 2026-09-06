# Vega Protocol Extensions: Spike 001

Custom LSP extensions declared in the `/protocol` module, namespaced `vega/`, per Constitution
Principle I. Two requests, both file I/O. Shapes are given as TypeScript-style interfaces for
precision; the `/protocol` module owns the authoritative definitions and generates or mirrors them
for both runtimes.

## `vega/readDocument`

**Direction**: client → server, request.
**Purpose**: obtain a document's content and its byte-level characteristics from the backend, so
the editor never reads the file itself (FR-002).

```typescript
interface VegaReadDocumentParams {
  /** Absolute path, or a URI the server can resolve. */
  uri: string;
}

interface VegaReadDocumentResult {
  /** Decoded text, ready to place in the editor buffer. */
  text: string;
  /** Encoding detected on disk; preserved verbatim on save. */
  encoding: string;
  /** Dominant line ending; "mixed" when the file is inconsistent. */
  lineEnding: "LF" | "CRLF" | "mixed";
  /** Whether the file ends with a newline; preserved on save. */
  hasTrailingNewline: boolean;
  /** Digest of the exact bytes read, used to detect on-disk change before save. */
  contentHash: string;
}
```

**Errors**: file not found, permission denied, path is a directory, file exceeds the supported
size. Each MUST return a structured error the shell can display; the window MUST remain usable
(FR-004).

**Constraints**
- Bounded by Budget B3: this request sits inside the 2 s launch-to-editable window, so its
  response must be streamed or delivered promptly enough to leave room for the initial parse.
- The server MUST NOT begin parsing as a side effect of this call. Parsing starts at
  `textDocument/didOpen`, keeping read and analyse separable and independently measurable.

## `vega/saveDocument`

**Direction**: client → server, request.
**Purpose**: have the backend write the document to disk (FR-021).

```typescript
interface VegaSaveDocumentParams {
  uri: string;
  /** Document version the client believes it is saving. */
  version: number;
  /** Digest of the client's buffer at that version. */
  expectedContentHash: string;
  /** Digest returned by vega/readDocument, or by the last successful save. */
  baseContentHash: string;
}

interface VegaSaveDocumentResult {
  status: "written" | "failed" | "mirror-mismatch" | "disk-changed";
  /** Version actually written, on success. */
  version?: number;
  /** Digest of the bytes now on disk, on success. */
  contentHash?: string;
  /** Human-readable reason, on any non-success status. */
  reason?: string;
}
```

**Why the text is not in the payload.** The server already holds a synchronised mirror of the
document, maintained by `textDocument/didChange`. Sending 50,000 lines back across the boundary on
every save would be a large, pointless transfer. The server writes from its mirror.

**Why the hashes are in the payload.** Writing from the mirror is only safe if the mirror is
provably identical to the buffer, so the client states what it expects:

- `expectedContentHash` — the server hashes its mirror at `version` and compares. A mismatch means
  synchronisation has drifted, and the server MUST return `mirror-mismatch` and write nothing. This
  converts a silent corruption into a loud, testable failure.
- `baseContentHash` — the server compares against the file currently on disk. A difference means
  the file changed underneath the editor, and the server MUST return `disk-changed` and write
  nothing, leaving the resolution to the developer (spec edge case).

**Serialisation rules (FR-020, SC-010)**
- Text is encoded using the document's original `encoding`.
- Line endings are written in the document's original `lineEnding` style; a document read as
  `mixed` is written back preserving each line's original ending.
- `hasTrailingNewline` is preserved exactly — never added, never removed.
- Saving an unmodified document MUST produce bytes identical to those read.

**Errors**: permission denied, disk full, path no longer writable. All MUST surface to the
developer with the buffer preserved (FR-022).

## Why not the standard method

LSP 3.18 adds **`workspace/textDocumentContent`** — a genuine client-to-server content fetch,
shaped `{ uri } -> { text }`, with a server capability declaring which URI schemes it serves.
Constitution Principle VIII requires standard behaviour to be preferred, so this was evaluated
seriously as a replacement for `vega/readDocument`. It was rejected on three grounds:

1. **The spec designates the result read-only.** "Clients should treat the content returned from
   this request as readonly." The method exists for non-`file:` schemes — decompiled class files,
   generated sources — not for a document the user is about to edit and save. Using it for an
   editable buffer would be using it against its stated contract.
2. **It carries none of the byte-level metadata this spike needs.** The result is text alone. This
   spike must preserve encoding, line-ending style and trailing-newline state through a save
   (FR-020, SC-010), and those must be reported by whoever read the bytes.
3. **3.18 is not stable.** The specification text describes itself as under development, and
   server support is uneven.

**The shape of `vega/readDocument` deliberately mirrors `workspace/textDocumentContent`** —
`{ uri }` in, text out, with the extra metadata as additional fields. When 3.18 stabilises and the
read-only constraint is no longer an obstacle, migration is a rename plus a metadata decision, not
a redesign. This is recorded here so the decision is revisited rather than inherited.

Prior art for the custom-request approach: `eclipse.jdt.ls` has long shipped
`java/classFileContents` — "request to server that retrieves the contents of a .class file" — for
exactly this shape of need, gated behind an initialization option.

## Rejected alternatives

**A `vega/` notification for backend status.** Rejected: `initialize`/`initialized` already
communicates readiness and `$/progress` already communicates parse progress, so a custom message
would duplicate standard behaviour that Principle VIII requires be preferred. Process liveness is
observed by the editor directly, because a dead process cannot report its own death.

**Sending the full text in `vega/saveDocument`.** Rejected: it moves 50,000 lines across the
boundary to write bytes the server already has. The hash-verified mirror gets the same safety for a
fixed-size payload, and turns mirror drift into an explicit failure status.

**Streaming content in chunks from `vega/readDocument`.** Not adopted for this spike. If the single
response proves too slow to fit Budget B3, chunked delivery is the first optimisation to try, and
that outcome belongs in the spike's findings rather than in speculative protocol surface now.
