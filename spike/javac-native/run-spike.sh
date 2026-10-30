#!/usr/bin/env bash
# Reproduces the ADR-065 OQ1 javac native/Wasm spike. Standalone: not part of the JuLC Gradle build.
#
#   HOST_GRAALVM=<Oracle GraalVM 25.0.2 home> WEB_GRAALVM=<Oracle GraalVM 25.3 home> ./run-spike.sh [OUT_DIR]
#
# Needs wasm-opt (Binaryen) on PATH for the Web Image and node 22+. Writes build/ and OUT_DIR (default out-rerun).
set -euo pipefail
cd "$(dirname "$0")"
HOST_GRAALVM=${HOST_GRAALVM:-$HOME/.sdkman/candidates/java/25.0.2-graal}
WEB_GRAALVM=${WEB_GRAALVM:?set WEB_GRAALVM to an Oracle GraalVM 25.3 home (Web Image)}
OUT=${1:-out-rerun}
NODE=${NODE:-node}
EXPORTS=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
mkdir -p "$OUT" build/blobs build/blobs-253

# --- Step 1: JVM baseline (javac reads the JDK's jrt:/ image) ---
rm -rf build/classes && "$HOST_GRAALVM/bin/javac" -source 25 -target 25 --add-exports $EXPORTS -d build/classes src/spike/*.java
for t in javactool toolprovider; do
  "$HOST_GRAALVM/bin/java" --add-exports $EXPORTS -cp build/classes spike.JavacProbe jrt $t > "$OUT/jvm-jrt-$t.txt"
done

# --- Step 2: embedded platform, no jrt:/ ---
# Discover the minimal set: embed all of java.base (signatures only) and log what javac opens.
"$HOST_GRAALVM/bin/java" tools/MakePlatformBlob.java build/blobs/all-api.bin api all
rm -rf build/res-all && mkdir -p build/res-all && cp build/blobs/all-api.bin build/res-all/platform.bin
"$HOST_GRAALVM/bin/java" --add-exports $EXPORTS -Dprobe.listOpened=all -cp build/classes:build/res-all \
  spike.JavacProbe embedded javactool > "$OUT/jvm-embedded-all.txt"
grep '^OPENED java' "$OUT/jvm-embedded-all.txt" | sed 's/^OPENED //' > build/minimal-list.txt
for s in none code api; do
  "$HOST_GRAALVM/bin/java" tools/MakePlatformBlob.java build/blobs/minimal-$s.bin $s list:build/minimal-list.txt
done
"$HOST_GRAALVM/bin/java" tools/MakePlatformBlob.java build/blobs/exported-api.bin api exported
rm -rf build/res-minimal-api && mkdir -p build/res-minimal-api && cp build/blobs/minimal-api.bin build/res-minimal-api/platform.bin
"$HOST_GRAALVM/bin/java" --add-exports $EXPORTS -cp build/classes:build/res-minimal-api spike.JavacProbe embedded javactool > "$OUT/jvm-embedded-minimal.txt"
"$HOST_GRAALVM/bin/java" --add-exports $EXPORTS -cp build/classes:build/res-minimal-api spike.JavacProbe embedded javactool --extract > "$OUT/jvm-embedded-extract.txt"
# Negative control: without BigInteger.class the probe must fail to resolve BigInteger.
grep -v BigInteger build/minimal-list.txt > build/minus-biginteger.txt
"$HOST_GRAALVM/bin/java" tools/MakePlatformBlob.java build/blobs/minus-biginteger.bin api list:build/minus-biginteger.txt
rm -rf build/res-minus && mkdir -p build/res-minus && cp build/blobs/minus-biginteger.bin build/res-minus/platform.bin
"$HOST_GRAALVM/bin/java" --add-exports $EXPORTS -cp build/classes:build/res-minus spike.JavacProbe embedded javactool > "$OUT/jvm-embedded-minus-biginteger.txt"
./compare.sh "$OUT/jvm-jrt-javactool.txt" "$OUT/jvm-embedded-minimal.txt"
if ./compare.sh "$OUT/jvm-jrt-javactool.txt" "$OUT/jvm-embedded-minus-biginteger.txt" > /dev/null; then
  echo "negative control unexpectedly passed"; exit 1
fi

# --- Step 3: host native image ---
rm -rf build/ni-config
for t in javactool toolprovider; do
  "$HOST_GRAALVM/bin/java" -agentlib:native-image-agent=config-merge-dir=build/ni-config --add-exports $EXPORTS \
    -cp build/classes:build/res-minimal-api spike.JavacProbe embedded $t --extract > /dev/null
done
rm -rf build/native && mkdir -p build/native
/usr/bin/time -l "$HOST_GRAALVM/bin/native-image" --no-fallback --add-exports=$EXPORTS \
  -H:ConfigurationFileDirectories=build/ni-config -cp build/classes:build/res-minimal-api \
  -o build/native/javac-probe spike.JavacProbe > "$OUT/native-build.log" 2>&1
for t in javactool toolprovider; do
  /usr/bin/time -l build/native/javac-probe embedded $t > "$OUT/native-embedded-$t.txt" 2> "$OUT/native-embedded-$t.time"
  ./compare.sh "$OUT/jvm-jrt-javactool.txt" "$OUT/native-embedded-$t.txt"
done
build/native/javac-probe embedded javactool --extract > "$OUT/native-embedded-extract.txt"
./compare.sh "$OUT/jvm-embedded-extract.txt" "$OUT/native-embedded-extract.txt"

# --- Step 4: Web Image (no java.util.zip.Inflater there, so the blob is stored uncompressed) ---
rm -rf build/classes-253 && "$WEB_GRAALVM/bin/javac" -source 25 -target 25 --add-exports $EXPORTS -d build/classes-253 src/spike/*.java
"$WEB_GRAALVM/bin/java" tools/MakePlatformBlob.java build/blobs-253/minimal-api.bin api list:build/minimal-list.txt
rm -rf build/res-253-raw && mkdir -p build/res-253-raw && gunzip -c build/blobs-253/minimal-api.bin > build/res-253-raw/platform.bin
rm -rf build/wasm && mkdir -p build/wasm
/usr/bin/time -l "$WEB_GRAALVM/bin/native-image" --tool:svm-wasm --add-exports=$EXPORTS \
  -H:ConfigurationFileDirectories=build/ni-config -H:+ReportExceptionStackTraces \
  -cp build/classes-253:build/res-253-raw -o build/wasm/javac-probe spike.JavacProbe > "$OUT/wasm-build.log" 2>&1
rm -f build/wasm/*.wat
for t in javactool toolprovider; do
  "$NODE" --experimental-wasm-exnref build/wasm/javac-probe.js embedded $t > "$OUT/wasm-embedded-$t.txt" 2>&1
  ./compare.sh "$OUT/jvm-jrt-javactool.txt" "$OUT/wasm-embedded-$t.txt"
done
"$NODE" --experimental-wasm-exnref build/wasm/javac-probe.js embedded javactool --extract > "$OUT/wasm-embedded-extract.txt" 2>&1
./compare.sh "$OUT/jvm-embedded-extract.txt" "$OUT/wasm-embedded-extract.txt"
"$NODE" sizes.mjs build/wasm/javac-probe.js build/wasm/javac-probe.js.wasm | tee "$OUT/wasm-sizes.txt"
"$NODE" --experimental-wasm-exnref load.mjs build/wasm/javac-probe.js.wasm | tee "$OUT/wasm-compile.txt"
echo "spike reproduced into $OUT"
