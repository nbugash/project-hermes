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

OUT="${1:-fixtures/large-java-file/RealCorpus.java}"
TARGET_LINES="${2:-50000}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for pat in aws-java-sdk-core jakarta.persistence-api kryo5 opentest4j cache-api microprofile-jwt lz4-java; do
  jar=$(find "$HOME/.gradle/caches" -name "*${pat}*-sources.jar" 2>/dev/null | head -1)
  [ -n "$jar" ] && unzip -qo "$jar" -d "$WORK" '*.java' 2>/dev/null || true
done

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
