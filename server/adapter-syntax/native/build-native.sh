#!/usr/bin/env bash
# Builds libtree-sitter and libtree-sitter-java from pinned sources.
#
# The flags here are budget-critical, not stylistic. Research (spec 001, D11) measured a 50k-line
# single-character reparse at 8.49 ms p95 with a stock pre-built native, 7.73 ms with -O3 alone,
# and 0.70 ms with -O3 -DNDEBUG plus the chunked parse path. Omitting NDEBUG leaves tree-sitter's
# hot-path assertions compiled in and roughly doubles cost, which alone fails SC-004.
#
# Versions are pinned deliberately (task T073): tree-sitter error recovery has regressed across a
# minor core bump before, and SC-006 depends on damage staying local.
set -euo pipefail

# Core ABI must match the jtreesitter binding generation (0.26.x).
TREE_SITTER_VERSION="${TREE_SITTER_VERSION:-v0.26.13}"
TREE_SITTER_JAVA_VERSION="${TREE_SITTER_JAVA_VERSION:-v0.23.5}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_DIR="$SCRIPT_DIR/../src/main/resources/native"
# Shared-library conventions differ by platform: macOS wants .dylib built with -dynamiclib, and
# loading a .so there fails at run time rather than at build time — which is why hardcoding the Linux
# names went unnoticed until someone tried to install on a Mac.
case "$(uname -s)" in
  Darwin)
    LIB_EXT="dylib"
    CFLAGS_COMMON="-O3 -DNDEBUG -fPIC -dynamiclib"
    ;;
  *)
    LIB_EXT="so"
    CFLAGS_COMMON="-O3 -DNDEBUG -fPIC -shared"
    ;;
esac

# Reports what this platform would build, without cloning or compiling. Exists so the Darwin branch
# can be exercised from a test on any machine: what is checkable off-macOS is the decision, not that
# the resulting .dylib loads.
if [ "${1:-}" = "--print-target" ]; then
  echo "os=$(uname -s)"
  echo "ext=$LIB_EXT"
  echo "cflags=$CFLAGS_COMMON"
  exit 0
fi

mkdir -p "$BUILD_DIR" "$OUT_DIR"

clone_at() {
  local repo="$1" tag="$2" dest="$3"
  if [ ! -d "$dest" ]; then
    git clone --quiet --depth 1 --branch "$tag" "$repo" "$dest"
  fi
}

echo "==> target: $(uname -s) ($LIB_EXT)"
echo "==> fetching sources (core $TREE_SITTER_VERSION, java grammar $TREE_SITTER_JAVA_VERSION)"
clone_at https://github.com/tree-sitter/tree-sitter.git "$TREE_SITTER_VERSION" "$BUILD_DIR/tree-sitter"
clone_at https://github.com/tree-sitter/tree-sitter-java.git "$TREE_SITTER_JAVA_VERSION" "$BUILD_DIR/tree-sitter-java"

echo "==> building libtree-sitter with: $CFLAGS_COMMON"
# lib.c is tree-sitter's single-translation-unit amalgamation.
cc $CFLAGS_COMMON \
  -I "$BUILD_DIR/tree-sitter/lib/include" \
  -I "$BUILD_DIR/tree-sitter/lib/src" \
  "$BUILD_DIR/tree-sitter/lib/src/lib.c" \
  -o "$OUT_DIR/libtree-sitter.$LIB_EXT"

echo "==> building libtree-sitter-java"
JAVA_SRC=("$BUILD_DIR/tree-sitter-java/src/parser.c")
if [ -f "$BUILD_DIR/tree-sitter-java/src/scanner.c" ]; then
  JAVA_SRC+=("$BUILD_DIR/tree-sitter-java/src/scanner.c")
fi
cc $CFLAGS_COMMON \
  -I "$BUILD_DIR/tree-sitter-java/src" \
  "${JAVA_SRC[@]}" \
  -o "$OUT_DIR/libtree-sitter-java.$LIB_EXT"

echo "==> built:"
ls -la "$OUT_DIR"
