#!/usr/bin/env bash
# Builds the javac Web Image demo into this directory (javac-check.js + javac-check.js.wasm).
#   WEB_GRAALVM=<Oracle GraalVM 25.3 home> ./build.sh      (needs wasm-opt on PATH; run ../run-spike.sh first
#                                                           for build/ni-config, and the JuLC jars built)
set -euo pipefail
cd "$(dirname "$0")/.."
G=${WEB_GRAALVM:?set WEB_GRAALVM}
WI=$(ls ~/.gradle/caches/modules-2/files-2.1/org.graalvm.sdk/webimage-preview/25.3.4.1/*/webimage-preview-25.3.4.1.jar | head -1)
L=../..
mkdir -p build/web-blob build/web-res
"$G/bin/java" tools/MakePlatformBlob.java build/web-blob/platform.bin api list:web/java-base-list.txt \
  jar:$L/julc-core/build/libs/julc-core-0.1.0-pre20.jar jar:$L/julc-ledger-api/build/libs/julc-ledger-api-0.1.0-pre20.jar \
  jar:$L/julc-stdlib/build/libs/julc-stdlib-0.1.0-pre20.jar
gunzip -c build/web-blob/platform.bin > build/web-res/platform.bin   # Web Image has no Inflater
rm -rf build/classes-web && mkdir -p build/classes-web
"$G/bin/javac" -source 25 -target 25 -cp "$WI" -d build/classes-web src/spike/EmbeddedFileManager.java src-web/spike/JavacCheck.java
rm -rf build/web-image && mkdir -p build/web-image
"$G/bin/native-image" --tool:svm-wasm -H:ConfigurationFileDirectories=build/ni-config -H:+ReportExceptionStackTraces \
  -cp build/classes-web:build/web-res -o build/web-image/javac-check spike.JavacCheck
# Same launcher patch as julc-wasm's wasmBundle task: let the host page/worker choose the .wasm URL.
python3 -I - build/web-image/javac-check.js web/javac-check.js <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
anchor = 'const config = new GraalVM.Config();'
assert s.count(anchor) == 1, 'Web Image launcher anchor changed'
s = s.replace(anchor, anchor + '\nif (globalThis.JAVAC_WASM_PATH) config.wasm_path = globalThis.JAVAC_WASM_PATH;')
open(dst, 'w', encoding='utf-8').write(s)
PY
cp build/web-image/javac-check.js.wasm web/javac-check.js.wasm
ls -l web/javac-check.js web/javac-check.js.wasm
