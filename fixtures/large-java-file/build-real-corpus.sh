#!/usr/bin/env bash
# Assembles a ~50,000-line corpus of real production Java for budget measurement (T097).
#
# The generated fixture used elsewhere is synthetic: uniform shape, one outer class holding
# hundreds of nested classes. Real Java is not shaped that way, and the spike's SC-004 measurement
# turned out to be sensitive to exactly that difference. This script assembles real source from
# whatever sources jars the local Gradle cache holds, stripping only `package` and `import` lines
# so each file's own structure survives intact.
#
# The result parses but does not compile: names collide across projects and imports are gone. That
# is deliberate and sufficient — the measurement is of parsing, and tree-sitter resolves no names.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-fixtures/large-java-file/RealCorpus.java}"
TARGET_LINES="${2:-50000}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Pinned Maven coordinates, fetched if they are not already in the local Gradle cache.
#
# The first version of this scavenged whatever sources jars happened to be in ~/.gradle. That worked
# on a developer machine and failed on the first CI run it ever got: a fresh runner has no cache, and
# `find` on a missing directory exits non-zero, which under `set -e` killed the script before it
# printed anything. Naming the artifacts makes the corpus reproducible on any machine and identical
# between them, which is what a benchmark input has to be.
# kryo5 was here and publishes no sources jar for 5.5.0; it 404'd on every run while the corpus
# reached its target line count without it. Removed rather than left to print an error each time.
ARTIFACTS="
com/amazonaws/aws-java-sdk-core/1.12.792/aws-java-sdk-core-1.12.792-sources.jar
jakarta/persistence/jakarta.persistence-api/3.2.0/jakarta.persistence-api-3.2.0-sources.jar
org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0-sources.jar
javax/cache/cache-api/1.1.1/cache-api-1.1.1-sources.jar
"

CACHE_DIR="${VEGA_CORPUS_CACHE:-$SCRIPT_DIR/.sources}"
mkdir -p "$CACHE_DIR"

for path in $ARTIFACTS; do
  jar_name="$(basename "$path")"
  local_jar="$CACHE_DIR/$jar_name"

  if [ ! -f "$local_jar" ]; then
    # Prefer a copy already on the machine; only reach the network when there is not one.
    cached="$(find "$HOME/.gradle/caches" -name "$jar_name" 2>/dev/null | head -1 || true)"
    if [ -n "$cached" ]; then
      cp "$cached" "$local_jar"
    else
      echo "==> fetching $jar_name"
      curl -fsSL --retry 3 -o "$local_jar" "https://repo1.maven.org/maven2/$path" || {
        echo "could not fetch $jar_name from Maven Central" >&2
        rm -f "$local_jar"
        continue
      }
    fi
  fi

  unzip -qo "$local_jar" -d "$WORK" '*.java' 2>/dev/null || true
done

if [ -z "$(find "$WORK" -name '*.java' -print -quit)" ]; then
  echo "no Java sources were collected; cannot assemble the corpus" >&2
  exit 1
fi

: > "$OUT"
lines=0
while IFS= read -r f; do
  [ "$(basename "$f")" = "module-info.java" ] && continue
  grep -vE '^\s*(package|import)\s' "$f" >> "$OUT" || true
  printf '\n' >> "$OUT"
  lines=$(wc -l < "$OUT")
  [ "$lines" -ge "$TARGET_LINES" ] && break
done < <(find "$WORK" -name "*.java" | sort)

echo "wrote $OUT: $(wc -l < "$OUT") lines, $(wc -c < "$OUT") bytes"
