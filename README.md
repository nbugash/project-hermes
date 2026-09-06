# Vega — large-file responsiveness spike

A desktop code editor built to answer one question: **can a JVM analysis backend and an Electron
editor hold interactive budgets on a 50,000-line Java file?**

They can. Incremental re-analysis measures **2.2 ms p95** against a 5 ms budget, a cold open takes
**170–240 ms** against 500 ms, and typing drops no frames at line 50,000 or line 1. The evidence,
including what turned out to be false along the way, is in
[`specs/001-large-file-responsiveness/FINDINGS.md`](specs/001-large-file-responsiveness/FINDINGS.md).

This is a spike, not a product. It edits and saves Java files and does nothing else.

---

## Installing on macOS

> **Not yet verified on macOS.** Everything here was built and measured on Linux x86_64. The native
> build and library loading are platform-aware, and CI now has a `macos-latest` job that compiles the
> natives and loads them through the FFM linker — but that job has never run, so this is a
> best-effort recipe rather than a verified one. See [`docs/tech-radar.md`](docs/tech-radar.md) for
> what specifically is unknown. If a step fails, that is a finding worth recording rather than a
> mistake on your part.

### Prerequisites

| What | Why | Install |
|---|---|---|
| **JDK 25** | The backend uses the FFM API to call tree-sitter, which is only final in 22+. The build pins 25. | `brew install openjdk@25` or [SDKMAN](https://sdkman.io): `sdk install java 25-tem` |
| **Node 20+** | Electron 33 and Vite. | `brew install node` |
| **Xcode Command Line Tools** | The tree-sitter core and Java grammar are compiled from source. | `xcode-select --install` |
| **Git** | The native build clones pinned tree-sitter tags. | Included with the CLT above |

Check the JDK is actually 25 — an older default JDK is the most likely first failure:

```bash
java -version   # expect 25.x
```

### Build

```bash
git clone <this-repo> vega && cd vega

# 1. Compile the native libraries (tree-sitter core + Java grammar, pinned versions).
#    Produces .dylib on macOS, .so on Linux. Takes a minute; it clones two repos.
server/adapter-syntax/native/build-native.sh

# 2. Build and test the backend.
./gradlew build

# 3. Build the editor.
cd app && npm install && npm run build
```

### Run

The editor spawns the backend itself, so the backend needs to exist as a runnable distribution:

```bash
./gradlew :server:app:installDist          # from the repo root
cd app
VEGA_BACKEND_COMMAND=../server/app/build/install/app/bin/app \
VEGA_BACKEND_ARGS='' \
  npx electron dist/main/main.js /path/to/SomeFile.java
```

The window appears before the backend is ready — deliberately. It reports "Backend starting" and
becomes fully functional a moment later.

To try it on something large, generate the measurement corpus first:

```bash
fixtures/large-java-file/build-real-corpus.sh    # ~50,000 lines of real Java
```

---

## Tests

```bash
./gradlew build                # 198 backend tests, plus ArchUnit boundary rules
cd app
npm test                       # 95 component tests
npm run e2e:fast               # 16 end-to-end tests, ~2 min (parallel)
npm run e2e:perf               # 7 timing measurements, serial
npm run e2e                    # everything, ~11 min
```

`e2e:fast` and `e2e:perf` are split because the perf tests must run serially — four Electron
instances competing for CPU would inflate every frame figure and the numbers would describe the
machine rather than the editor.

### Timing budgets

Budget assertions are **opt-in**, behind `VEGA_PERF_GATE=1`, and should only be set on
GPU-accelerated hardware:

```bash
VEGA_PERF_GATE=1 npm run e2e:perf
```

Without it the timing tests still run and still assert dropped frames — they just do not judge
wall-clock p95. On a headless or virtualised machine, software compositing inflates frame times and
those numbers would describe the compositor. Setting this flag on a headless CI runner will fail with
numbers that say nothing about the code.

### Benchmarks

```bash
fixtures/large-java-file/build-real-corpus.sh
./gradlew :server:adapter-syntax:jmh
```

Four benchmarks: incremental reparse, reparse while the file is syntactically broken, mirror update,
and full-document tokenization. They refuse to run without the real corpus rather than silently
measuring the synthetic fixture, which gives a materially different number
([ADR-0002](docs/adr/0002-confirm-sc-004-reparse-budget.md)).

---

## Layout

```
server/core           analysis core — no protocol, no parser, no framework (ArchUnit enforces this)
server/adapter-syntax tree-sitter via the FFM API, with bundled native libraries
server/adapter-fs     filesystem gateway: encoding, line endings, byte-exact save
server/adapter-lsp    LSP surface, plus the two vega/* file-I/O requests
server/app            composition root and the stdio launcher
protocol/             wire types shared by both runtimes
app/                  Electron main, preload bridge, React renderer, Monaco host
```

The editor process performs **no filesystem access of its own** — it has no Node APIs and reaches the
backend through an enumerated bridge. That is what would let the backend move to another machine
later without changing the flow, and it is asserted by
`app/tests/e2e/no-direct-file-read.spec.ts`.

## Documents worth reading first

- [`FINDINGS.md`](specs/001-large-file-responsiveness/FINDINGS.md) — what the spike answered, four
  things that turned out false, twelve silent defects, and what is still unknown
- [`BASELINES.md`](fixtures/large-java-file/BASELINES.md) — every measured number and its caveats
- [`docs/adr/`](docs/adr/) — the decisions, including two that were revised by later measurement
- [`docs/tech-radar.md`](docs/tech-radar.md) — what is verified, and what explicitly is not
