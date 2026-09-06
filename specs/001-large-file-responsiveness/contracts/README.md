# Protocol Contracts: Spike 001

The protocol boundary is this feature's only external interface. Everything crossing it is either
standard LSP or one of a deliberately tiny set of `vega/` extensions.

- [lsp-surface.md](./lsp-surface.md) — the standard LSP methods used, and the constraints this
  spike places on each
- [vega-extensions.md](./vega-extensions.md) — the custom requests, which exist only where standard
  LSP cannot express the requirement

## Why only two custom requests

Constitution Principle VIII requires standard behaviour to be preferred until it is shown
insufficient. Applying that to the spike's requirements narrowed the custom surface to two
requests:

| Requirement | Resolution |
|-------------|------------|
| Editor must not read the file from disk (FR-002) | **Custom, with a standard near-miss.** Core LSP inverts this: the client owns the document and pushes text via `textDocument/didOpen`. LSP 3.18's `workspace/textDocumentContent` does fetch content from the server, but designates it read-only and carries no encoding metadata — see [vega-extensions.md](./vega-extensions.md#why-not-the-standard-method). → `vega/readDocument`, shaped to migrate |
| Backend must perform the write on save (FR-021) | **Custom.** `textDocument/didSave` notifies the server that a save happened; it does not ask the server to perform one. → `vega/saveDocument` |
| Report that language features are still starting (FR-004) | **Standard.** Server readiness is the `initialize`/`initialized` handshake; first-parse progress is `$/progress`. No custom message needed. |
| Backend unreachable or dead (FR-004, edge cases) | **No protocol at all.** The editor observes its own child process; a dead process cannot report its own death. |
| Cancel superseded highlighting (FR-010) | **Standard.** `$/cancelRequest`. |
| Deliver highlighting incrementally (FR-008, FR-009) | **Standard.** `textDocument/semanticTokens/full` and `/full/delta`. |

Two custom requests, both concerned solely with file I/O — which is exactly the part that must
follow the backend when it later runs remotely. Everything else is stock LSP.

## Versioning

The `vega/` methods are defined in the `/protocol` module and versioned there, per Constitution
Principle I. A breaking change to either shape is a `/protocol` version bump, and the editor and
backend declare a compatible protocol version during `initialize` so a mismatch fails loudly at
startup rather than as a malformed response later.
