#!/usr/bin/env bash
#
# Builds whatever is missing, then runs Vega.
#
# Not an installer. It leaves everything inside the repository and launches the editor from there —
# no bundle, no signing, no JVM shipped with it. That is the honest shape of this project today; see
# the "Is there an installer?" section of README.md for what a real one would involve.
#
#   ./run.sh                       open the editor with no document
#   ./run.sh path/to/File.java     open a file
#   ./run.sh --rebuild             force every build step to run again
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

REBUILD=0
FILE_ARG=""
for arg in "$@"; do
  case "$arg" in
    --rebuild) REBUILD=1 ;;
    -h|--help) sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) FILE_ARG="$arg" ;;
  esac
done

step()  { printf '\033[1m==> %s\033[0m\n' "$1"; }
fail()  { printf '\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }

# --- prerequisites ------------------------------------------------------------------------------
# Checked up front and all together: discovering a missing toolchain three minutes into a native
# build is a worse experience than being told immediately.

step "checking prerequisites"

# macOS installs JDKs where PATH does not see them, so java_home is consulted before giving up.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "${JAVA_BIN}" >/dev/null 2>&1 || JAVA_BIN="java"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "no Java found. Install JDK 25: brew install openjdk@25"

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ -n "$JAVA_MAJOR" ] || fail "could not read the Java version from '$JAVA_BIN -version'"
# The backend calls tree-sitter through the FFM API, which is only final in 22+. The build pins 25.
[ "$JAVA_MAJOR" -ge 25 ] || fail "Java $JAVA_MAJOR found, need 25 or newer. Try: brew install openjdk@25"

command -v node >/dev/null 2>&1 || fail "no Node found. Install it: brew install node"
NODE_MAJOR="$(node -v | sed 's/v\([0-9]*\).*/\1/')"
[ "$NODE_MAJOR" -ge 20 ] || fail "Node $NODE_MAJOR found, need 20 or newer (Electron 33)."

# The tree-sitter core and grammar are compiled from source; on macOS cc is clang from the Command
# Line Tools, which is not present on a fresh machine.
command -v cc >/dev/null 2>&1 || fail "no C compiler found. On macOS run: xcode-select --install"

printf '    java %s (%s)\n    node %s\n    cc   %s\n' \
  "$JAVA_MAJOR" "$JAVA_BIN" "$(node -v)" "$(command -v cc)"

# --- native libraries ---------------------------------------------------------------------------
# Named per platform: libtree-sitter.dylib on macOS, .so on Linux — the same convention the build
# script and the Java loader use, kept in one `case` here rather than guessed three ways.
NATIVE_DIR="server/adapter-syntax/src/main/resources/native"
case "$(uname -s)" in
  Darwin) CORE_LIB="libtree-sitter.dylib" ;;
  *)      CORE_LIB="libtree-sitter.so" ;;
esac

if [ "$REBUILD" = 1 ] || [ ! -f "$NATIVE_DIR/$CORE_LIB" ]; then
  step "building native tree-sitter libraries (clones two repos, ~1 min)"
  server/adapter-syntax/native/build-native.sh
else
  step "native libraries present ($CORE_LIB)"
fi

# --- backend ------------------------------------------------------------------------------------
BACKEND_BIN="server/app/build/install/app/bin/app"
if [ "$REBUILD" = 1 ] || [ ! -x "$BACKEND_BIN" ]; then
  step "building the backend"
  JAVA_HOME="${JAVA_HOME:-}" ./gradlew --quiet :server:app:installDist
else
  step "backend present"
fi
[ -x "$BACKEND_BIN" ] || fail "the backend did not build; run ./gradlew :server:app:installDist to see why"

# --- editor -------------------------------------------------------------------------------------
if [ "$REBUILD" = 1 ] || [ ! -d app/node_modules ]; then
  step "installing editor dependencies"
  npm --prefix app install --silent
fi

if [ "$REBUILD" = 1 ] || [ ! -f app/dist/main/main.js ]; then
  step "building the editor"
  npm --prefix app run build --silent
else
  step "editor present"
fi

# --- run ----------------------------------------------------------------------------------------
step "starting Vega"
[ -n "$FILE_ARG" ] && printf '    opening %s\n' "$FILE_ARG"

# The editor spawns the backend itself; these tell it where to find the one just built. The window
# appears before the backend is ready, by design — it reports "Backend starting" for a moment.
export VEGA_BACKEND_COMMAND="$REPO_ROOT/$BACKEND_BIN"
export VEGA_BACKEND_ARGS=""

ELECTRON_ARGS=()
# Chromium's sandbox needs a root-owned setuid helper, which containers and freshly unpacked
# node_modules often lack. Scoped to Linux and to the case where the helper is genuinely not setuid:
# disabling the sandbox is a real reduction in isolation, and on macOS - where it works - it is
# never touched.
if [ "$(uname -s)" = "Linux" ] && [ ! -u "app/node_modules/electron/dist/chrome-sandbox" ]; then
  printf '    note: Chromium sandbox helper is not setuid-root; starting with --no-sandbox.\n'
  printf '          To keep the sandbox, once:\n'
  printf '            sudo chown root app/node_modules/electron/dist/chrome-sandbox\n'
  printf '            sudo chmod 4755 app/node_modules/electron/dist/chrome-sandbox\n'
  ELECTRON_ARGS+=(--no-sandbox)
fi

cd app
if [ -n "$FILE_ARG" ]; then
  # Resolved before the change of directory, so a relative path means what the user typed.
  case "$FILE_ARG" in
    /*) TARGET="$FILE_ARG" ;;
    *)  TARGET="$REPO_ROOT/$FILE_ARG" ;;
  esac
  exec npx electron dist/main/main.js ${ELECTRON_ARGS[@]+"${ELECTRON_ARGS[@]}"} "$TARGET"
else
  exec npx electron dist/main/main.js ${ELECTRON_ARGS[@]+"${ELECTRON_ARGS[@]}"}
fi
