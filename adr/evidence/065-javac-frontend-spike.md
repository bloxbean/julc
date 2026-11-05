# ADR-065 OQ1 spike: javac in a native image and a Web Image

> Copied from `spike/javac-native/RESULTS.md` on branch `spike/065-javac-frontend` at `4d697480` (never merged,
> ADR-065 D1). Relative paths below are relative to `spike/javac-native/` on that branch.

A time-boxed spike for [ADR-065](https://github.com/bloxbean/julc/blob/design/adr-065-javac-frontend-spike/adr/065-javac-frontend-spike.md)
(branch `design/adr-065-javac-frontend-spike`), open question 1 and the platform part of D3/M1: can javac's
compiler API (`JavacTool`, `JavacTask.parse()`/`analyze()`, `Trees`) run inside a GraalVM native image, and inside
the GraalVM Web Image that `julc-wasm` uses?

**Short answer: yes, both, at probe level.** javac parses and attributes the probe class with identical
output on the JVM, in a host native image and in a Web Image under Node, once javac is given an embedded
`java.base` through a custom `JavaFileManager` instead of the JDK's `jrt:/` image. The main costs are size
(+10.1 MB raw / +2.6 MB brotli on the `julc-wasm` full image) and one Web Image gap (no `java.util.zip.Inflater`).
Details, commands and numbers follow. Everything here was run on the machine below. A number that is derived
rather than measured says so.

Scope: this is one small class (the ADR's probe), not a JuLC validator compiled through the JuLC pipeline. M1's
other rows (the annotation-processor route, Elci's native-image shape, a stdlib-using validator inside the real
`julc-wasm` engine) were not part of this task and are not answered here.

## Contents

- [Environment](#environment)
- [Layout and reproduction](#layout-and-reproduction)
- [Step 1: JVM baseline](#step-1-jvm-baseline)
- [Step 2: platform classes without the JDK image](#step-2-platform-classes-without-the-jdk-image)
- [Step 3: host native image](#step-3-host-native-image)
- [Step 4: Web Image (Wasm)](#step-4-web-image-wasm)
- [Step 5: extraction inside the images](#step-5-extraction-inside-the-images)
- [Measurements](#measurements)
- [Blockers and exact errors](#blockers-and-exact-errors)
- [Conclusion](#conclusion)
- [Recommendation for ADR-065 OQ1](#recommendation-for-adr-065-oq1)

## Environment

| Item | Value |
|---|---|
| Machine | Apple M4 Max, 128 GB, macOS 26.0.1 (25A362), arm64 |
| Repository | worktree `julc-javac-spike`, branch `spike/javac-native-wasm`, from `origin/main` at `b5ff39a8` |
| JVM baseline and host native image | Oracle GraalVM 25.0.2+10.1 (`java version "25.0.2" 2026-01-20 LTS`; `native-image 25.0.2 2026-01-20`), `~/.sdkman/candidates/java/25.0.2-graal` |
| Web Image | Oracle GraalVM 25.3.4.1-dev+1.1 (JDK `25.0.4.1.1`, `GRAALVM_VERSION="25.3.4.1-dev"`), EA build `jdk-25i3-25.0.4.1.1-ea.05` from `graalvm/oracle-graalvm-ea-builds`, `macos-aarch64` |
| Binaryen | `wasm-opt version 132` (`/opt/homebrew/bin/wasm-opt`) |
| Node | `v22.12.0` (`/usr/local/bin/node`), run with `--experimental-wasm-exnref` as `julc-wasm`'s smoke test does |

The Oracle GraalVM 25.3 download:

```
gh release download jdk-25i3-25.0.4.1.1-ea.05 -R graalvm/oracle-graalvm-ea-builds \
   -p 'graalvm-jdk-25i3-*_macos-aarch64_bin.tar.gz*'
```

It ships `graalvm-jdk-25i3-25.0.4.1.1-ea.05_macos-aarch64_bin.tar.gz.sha256`, which contains
`f6e04bc87bb2299dc443d3f98d8fb70f2935d55e82e2c11e3e16e94d41c95320`; `shasum -a 256` of the 364,606,663-byte archive
printed the same value. It was extracted into the session scratchpad (`.../scratchpad/graalvm/x/`), not installed.

## Layout and reproduction

`spike/javac-native/` is standalone: plain `javac`/`native-image` commands, not in `settings.gradle`, and no
existing module was modified.

| File | Role |
|---|---|
| `src/spike/JavacProbe.java` | The probe. `JavacProbe [jrt\|embedded] [javactool\|toolprovider] [--extract]` |
| `src/spike/EmbeddedFileManager.java` | A `JavaFileManager` with no disk and no `jrt:/`: its only system module is `java.base`, read from the resource `/platform.bin` |
| `tools/MakePlatformBlob.java` | Build-time tool (runs on a JDK): writes `platform.bin` from the running JDK's `jrt:/modules/java.base`, optionally stripping class files |
| `src-hello/spike/Hello.java` | Floor image (prints a `BigInteger` sum) for size deltas |
| `src-combined/spike/CombinedMain.java` | `julc-wasm` full engine plus the probe, both reachable: the size-delta image for the browser |
| `src-combined-cli/spike/CombinedCliMain.java` | The `julc` CLI plus the probe, both reachable: the size-delta image for the CLI |
| `compare.sh` | Compares two probe outputs, ignoring `TIMING`, banner and `OPENED` lines |
| `sizes.mjs`, `load.mjs` | Raw/gzip-9/brotli-11 sizes and `WebAssembly.compile` time, through Node's `zlib` and V8 |
| `run-spike.sh` | Reruns steps 1 to 4 end to end (see [Reproduction check](#reproduction-check)) |
| `out/` | The outputs and build logs quoted below |

Probe output lines starting with `TIMING` are measurements. Every other line is deterministic and is what
`compare.sh` compares across runtimes. Each run analyzes the OK source and then the error source twice
(round 1 is cold, round 2 reuses the file manager), and prints from round 1.

## Step 1: JVM baseline

```
J=~/.sdkman/candidates/java/25.0.2-graal
$J/bin/javac -source 25 -target 25 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    -d build/classes src/spike/*.java
$J/bin/java --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -cp build/classes \
    spike.JavacProbe jrt javactool > out/jvm-jrt-javactool.txt
$J/bin/java --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -cp build/classes \
    spike.JavacProbe jrt toolprovider > out/jvm-jrt-toolprovider.txt
```

(`--release 25` cannot be combined with `--add-exports` of a system module: `error: exporting a package from
system module jdk.compiler is not allowed with --release`. So the probe is compiled with `-source/-target 25`.)

The source comes from a `SimpleJavaFileObject` (`string:///Probe.java`). Nothing is read from or written to disk.
Options: `-proc:none -Xlint:none`. In `jrt` mode the file manager is javac's standard one, so platform classes
come from the JDK's `jrt:/` image.

Output (`out/jvm-jrt-javactool.txt`):

```
ToolProvider.getSystemJavaCompiler() = com.sun.tools.javac.api.JavacTool
JavacTool.create() = com.sun.tools.javac.api.JavacTool
using javactool, mode jrt, latest source version RELEASE_25
== case ok ==
no diagnostics
call `<no position>` -> CONSTRUCTOR java.lang.Object.<init>() : void ; type void
call `<no position>` -> CONSTRUCTOR java.lang.Record.<init>() : void ; type void
call `<no position>` -> CONSTRUCTOR java.lang.Record.<init>() : void ; type void
switch expression type: java.math.BigInteger (selector type Probe.S)
call `a.v().compareTo(k)` -> METHOD java.math.BigInteger.compareTo(java.math.BigInteger) : int ; type int
call `a.v()` -> METHOD Probe.A.v() : java.math.BigInteger ; type java.math.BigInteger
call `a.v().add(k)` -> METHOD java.math.BigInteger.add(java.math.BigInteger) : java.math.BigInteger ; type java.math.BigInteger
call `a.v()` -> METHOD Probe.A.v() : java.math.BigInteger ; type java.math.BigInteger
call `a.v()` -> METHOD Probe.A.v() : java.math.BigInteger ; type java.math.BigInteger
call `b.v().negate()` -> METHOD java.math.BigInteger.negate() : java.math.BigInteger ; type java.math.BigInteger
call `b.v()` -> METHOD Probe.B.v() : java.math.BigInteger ; type java.math.BigInteger
== case error ==
diagnostic ERROR 7:16 compiler.err.prob.found.req: incompatible types: java.lang.String cannot be converted to java.math.BigInteger
call `<no position>` -> CONSTRUCTOR java.lang.Object.<init>() : void ; type void
call `<no position>` -> CONSTRUCTOR java.lang.Record.<init>() : void ; type void
call `<no position>` -> CONSTRUCTOR java.lang.Record.<init>() : void ; type void
TIMING round 1: ok-case 153.3 ms, error-case 19.6 ms
TIMING round 2: ok-case 18.9 ms, error-case 11.4 ms
TIMING platform load 17.8 ms; main total 230.9 ms
```

- `a.v().add(k)` resolves to `java.math.BigInteger.add(java.math.BigInteger)`, and the switch expression has type
  `java.math.BigInteger`.
- The `<no position>` calls are the implicit `super()` calls of the class and record constructors.
- The error case (`return "x";`) gives `compiler.err.prob.found.req` at 7:16.

**Both creation routes return the same class**, and `compare.sh out/jvm-jrt-javactool.txt
out/jvm-jrt-toolprovider.txt` prints `SAME`. They differ in API exposure:

- `ToolProvider.getSystemJavaCompiler()` is public API. Together with a cast of the task to
  `com.sun.source.util.JavacTask`, which is exported, it needs no `--add-exports`.
- `JavacTool.create()` lives in a package that `jdk.compiler` does not export. Without `--add-exports` it fails at
  run time:

  ```
  JavacTool.create() threw java.lang.IllegalAccessError: class spike.JavacProbe (in unnamed module @0x1f32e575) cannot access class com.sun.tools.javac.api.JavacTool (in module jdk.compiler) because module jdk.compiler does not export com.sun.tools.javac.api to unnamed module @0x1f32e575
  ```

  That is the only non-exported API the probe uses. Everything else is `javax.tools`, `javax.lang.model` and
  `com.sun.source.*`.

## Step 2: platform classes without the JDK image

### Approach

`EmbeddedFileManager` implements `JavaFileManager` directly, not as a forwarding wrapper around the standard
manager.

- `hasLocation(SYSTEM_MODULES)` is true, and `listLocationsForModules(SYSTEM_MODULES)` returns one module location,
  `java.base`.
- `list`, `getJavaFileForInput` and `inferBinaryName` serve class files from the resource blob, including
  `java.base/module-info.class`. That file is needed, because javac compiles the probe in the unnamed module and
  looks `java.base` up as a system module.
- Every other location is empty, and the manager never touches the file system or `jrt:/`.

This is what `--system <image>` configures in the standard file manager. `--system none` itself is not passed:
a custom manager answers `isSupportedOption` with `-1`, so file-manager options do not apply to it.

`tools/MakePlatformBlob.java` writes the blob: gzip of (count, then path, length and bytes per class), sorted by
path. It has three strip levels:

- `none`;
- `code`: method bodies dropped;
- `api`: bodies and private fields and methods dropped, as `ct.sym` does.

Stripping uses `java.lang.classfile` with `ConstantPoolSharingOption.NEW_POOL`. Without a fresh pool, dropping
code kept the old constant pool, and `code` only went from 28.5 MB to 18.6 MB on the whole module.

### Finding the minimal set

The minimal set was found by measurement, not by iteration:

1. Embed all of `java.base` (7,332 class files, `api`-stripped).
2. Run the probe, logging every class file javac actually opens (`-Dprobe.listOpened`).
3. Rebuild the blob from exactly that list, and run again.

```
$J/bin/java tools/MakePlatformBlob.java build/blobs/all-api.bin api all
$J/bin/java --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -Dprobe.listOpened=all-api \
    -cp build/classes:build/res spike.JavacProbe embedded javactool      # build/res/platform.bin = all-api.bin
grep '^OPENED java' ... > build/minimal-list.txt
$J/bin/java tools/MakePlatformBlob.java build/blobs/minimal-api.bin api list:build/minimal-list.txt
```

**The minimal set for this probe is 22 classes plus `module-info.class`** (`out/minimal-list.txt`):

```
java.base/module-info.class
java/io/Serializable            java/lang/AutoCloseable         java/lang/Boolean
java/lang/Byte                  java/lang/Character             java/lang/Comparable
java/lang/Deprecated            java/lang/Double                java/lang/Float
java/lang/Integer               java/lang/Long                  java/lang/Number
java/lang/Object                java/lang/Record                java/lang/Short
java/lang/String                java/lang/Void                  java/lang/annotation/ElementType
java/lang/annotation/Retention  java/lang/annotation/RetentionPolicy
java/lang/annotation/Target     java/math/BigInteger
```

Some classes are absent from that list:

- `parse()` and `analyze()` open none of `java.lang.runtime.*`, `MatchException` or `Enum`, even though the probe
  has a pattern `switch` with a guard over a sealed interface.
- Those classes are what lowering and code generation reference: `TransPatterns` and `SwitchBootstraps` for pattern
  switches, `MatchException` for exhaustive switches. The spike did not run `generate()`, so that part is
  inference.
- javac completes classes lazily, so a class that is listed but never needed is never opened.

### Checks

- **Minimal set works.** With only these 23 entries, the probe's output is the same as the `jrt` baseline, for
  both strip levels `api` and `none`: `compare.sh` prints `SAME` for `out/jvm-embedded-minimal-api.txt` and
  `out/jvm-embedded-minimal-none.txt`.
- **javac reads only the blob.** As a negative control, the same blob without `BigInteger.class`
  (`out/jvm-embedded-minus-biginteger.txt`) gives `compiler.err.doesnt.exist: package java.math does not exist`
  and `cannot find symbol ... class BigInteger`. So javac does not fall back to the JDK's `jrt:/`. Step 3 confirms
  this a second way: a native image has no `jrt:/` at all.

### Sizes

From `MakePlatformBlob`'s output. "Original" is the class-file bytes in the JDK image.

| Set | Classes | Original | `code`-stripped | `api`-stripped | `api` blob, gzip |
|---|---:|---:|---:|---:|---:|
| Probe minimal set | 23 | 212,549 B | 58,431 B | 50,609 B | 17,296 B |
| JuLC on-chain stdlib minimal set (below) | 29 | 222,113 B | — | 54,277 B | 18,117 B |
| `java.base` exported packages, plus `module-info` | 3,660 | 13,632,428 B | 4,489,311 B | 3,859,946 B | 673,431 B |
| All of `java.base` | 7,332 | 28,536,300 B | 8,613,326 B | 7,081,802 B | 1,225,144 B |

### A realistic JuLC set

To see what real JuLC code needs, the 16 `@OnchainLibrary` sources of `julc-stdlib` (files matching
`^@OnchainLibrary`) were analyzed on the JVM with javac's `-verbose` class loading:

```
javac -verbose -proc:none -implicit:none -XDshould-stop.ifNoError=FLOW -d <tmp> \
  -cp julc-stdlib-0.1.0-pre20.jar:julc-ledger-api-0.1.0-pre20.jar:julc-core-0.1.0-pre20.jar @onchain-sources.txt
```

- They analyze with 0 errors.
- javac opened 28 `java.base` classes plus `module-info`: 54,277 bytes `api`-stripped. That set adds `List`,
  `Optional`, `Arrays`, `Objects`, `Collections`, `HashMap`, `TreeSet`, `Stream`, `function.*`, `Enum`,
  `Iterable` and others. The list is in `out/stdlib-javabase-list.txt`, and the javac log in
  `out/stdlib-javac-verbose.log`.
- It also opened 56 JuLC class files: 19 from `julc-core`, 35 from `julc-ledger-api` and 2 from `julc-stdlib`,
  180,256 bytes unstripped.

That is the platform a JuLC javac frontend would need for the bundled libraries. A user program needs whatever
it references beyond that.

### `ct.sym` considered, and why it was not used

`$JAVA_HOME/lib/ct.sym` (11,460,237 bytes in GraalVM 25.0.2) is a zip of signature-only `.sig` class files for
every `--release` from 8 to 25: the top-level directories run to `...NOP`, and `P` is 25. javac reaches it through
`JDKPlatformProvider`:

- `findCtSym()` resolves `System.getProperty("java.home")` + `lib/ct.sym`;
- it opens that file with `FileSystems.newFileSystem(ctSymFile, (ClassLoader) null)`, that is, zipfs on a disk
  file.

In a Web Image there is no `java.home`, no disk file and no `Inflater` (step 4), so `--release` cannot work there
as shipped.

The spike therefore uses **`ct.sym`-style content without `ct.sym`'s container**: signature-only class files
(the `api` strip), for one release and one module, chosen per need, and served by a custom file manager from an
embedded resource. That gives the `ct.sym` size benefit (the `api` strip is 24% of original for the minimal set,
28% for exported `java.base`) without zipfs, disk access or `java.home`. The same blob works unchanged on the
JVM, in the native image and in the Web Image. Packing it inside the image also guarantees that it matches the
javac it was built for.

## Step 3: host native image

Metadata collection: the tracing agent was run on the JVM over both routes, in embedded mode with `--extract`.

```
for t in javactool toolprovider; do
  $J/bin/java -agentlib:native-image-agent=config-merge-dir=build/ni-config \
      --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
      -cp build/classes:build/res-minimal-api spike.JavacProbe embedded $t --extract > /dev/null
done
```

It produced one small `reachability-metadata.json` (`out/agent-reachability-metadata.json`):

- **4 reflection entries:**
  - `com.sun.tools.javac.api.JavacTrees.instance(JavaCompiler$CompilationTask)`, because `Trees.instance` finds
    `JavacTrees` reflectively;
  - `JavaCompiler$CompilationTask`;
  - `sun.text.resources.cldr.FormatData` and `FormatData_en`.
- **12 resource entries:**
  - the resource bundles `com.sun.tools.javac.resources.compiler` and `...javac`;
  - their `_en` and `_en_SG` properties, with and without `module: jdk.compiler`. `en_SG` is this machine's
    locale;
  - `META-INF/services/com.sun.source.util.Plugin`;
  - `platform.bin`.

Nothing else was configured. `ServiceLoader` needed no entry: `ToolProvider.getSystemJavaCompiler()` returned
`JavacTool` in the image without one.

Two builds without parts of the metadata showed which parts are needed:

- **Only `platform.bin` registered** (`build/ni-config-min`): the build succeeds, but the run fails inside
  `JavacTool.getTask`:

  ```
  Exception in thread "main" java.lang.InternalError: Cannot find requested resource bundle for locale en_SG
  Caused by: java.util.MissingResourceException: Can't find bundle for base name com.sun.tools.javac.resources.compiler, locale en_SG
  ```

  So javac's resource bundles are required.
- **Resources and bundles, but no reflection entries** (`build/ni-config-noreflect`): the build succeeds, and the
  run fails at `Trees.instance`:

  ```
  java.lang.AssertionError: java.lang.NoSuchMethodException: com.sun.tools.javac.api.JavacTrees.instance(javax.tools.JavaCompiler$CompilationTask)
  ```

  So the `JavacTrees.instance` reflection entry is required.
- **With no configuration at all**, the run fails at `missing resource /platform.bin`.

Whether the two CLDR `FormatData` entries are needed was not isolated.

Build:

```
/usr/bin/time -l $J/bin/native-image --no-fallback \
    --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    -H:ConfigurationFileDirectories=build/ni-config \
    -cp build/classes:build/res-minimal-api -o build/native/javac-probe spike.JavacProbe
```

`--add-exports` is needed only because the probe also calls `JavacTool.create()`. The `toolprovider` route uses
public API only.

- **It builds.** `Finished generating 'javac-probe' in 40.5s` (41.18 s wall), with builder peak RSS 3,304,734,720
  bytes. The first build, of an earlier revision whose file manager read gzip only, took 34.4 s.
- **Binary size:** 37,922,856 bytes. The image report breaks the code area down by origin: `java.base` 8.04 MB,
  `jdk.compiler` 7.93 MB, `svm.jar` 2.87 MB. The image heap is 17.04 MB. A hello-world image (`Hello`) is
  6.33 MB.
- **Same output as step 1.** `compare.sh` prints `SAME` against the JVM `jrt` baseline for both `javactool` and
  `toolprovider`, and against the JVM embedded run with `--extract` (`out/native-embedded-*.txt`).
- **Startup and analyze time, and RSS:** 5 runs of `/usr/bin/time -l build/native/javac-probe embedded javactool`.

  | Measure | Native image (5 runs) | JVM, embedded (3 runs) | JVM, `jrt` (3 runs) |
  |---|---|---|---|
  | wall | 0.01 s | 0.27–0.29 s | 0.39–0.40 s |
  | main total | 5.8–6.8 ms | 225–246 ms | 336–350 ms |
  | cold `ok` parse+analyze | 3.0–3.3 ms | 172–187 ms | 216–230 ms |
  | warm `ok` | 1.0–1.3 ms | 12.6–15.2 ms | 31–33 ms |
  | error case | 0.7–0.9 ms | 12.5–15.1 ms | 27.9–28.5 ms |
  | peak RSS | 30,359,552–30,375,936 B | 210–220 MB | 380 MB |

  The very first execution of the first native binary took 1.19 s wall, and every later run 0.01 s. That looks
  like macOS's first-launch check of a new executable; it was not investigated.

**javac's standard file manager does not work in a native image**, so the embedded platform is required, not
optional. These are the probe's `jrt` mode in the native binary (`out/native-jrt-javactool.txt`):

- Without `java.home`, the standard file manager fails in a static initializer:

  ```
  Exception in thread "main" java.lang.ExceptionInInitializerError
      at jdk.compiler@25.0.2/com.sun.tools.javac.file.BaseFileManager.createLocations(BaseFileManager.java:126)
      ...
  Caused by: java.lang.NullPointerException
      at java.base@25.0.2/java.util.Objects.requireNonNull(Objects.java:220)
      at java.base@25.0.2/sun.nio.fs.UnixFileSystem.getPath(UnixFileSystem.java:257)
      at jdk.compiler@25.0.2/com.sun.tools.javac.file.Locations.<clinit>(Locations.java:139)
  ```

- With `-Djava.home=<a real JDK>`, the image has no `jrt:/` file-system provider:

  ```
  error listing module locations for SYSTEM_MODULES: java.nio.file.NotDirectoryException: ~/.sdkman/candidates/java/25.0.2-graal/lib/modules
  Exception in thread "main" java.lang.IllegalStateException: com.sun.tools.javac.util.Abort
  ```

### The julc CLI with javac

This measures the CLI native image's size delta. The baseline is the CLI's shadow jar built with the
`graalvmNative` arguments from `julc-cli/build.gradle` (`--no-fallback -H:+ReportExceptionStackTraces`). The
combined image is the same jar plus the probe, with `CombinedCliMain` as entry point:

```
./gradlew -q :julc-cli:shadowJar
$J/bin/native-image --no-fallback -H:+ReportExceptionStackTraces -cp julc-cli/build/libs/julc.jar \
    -o build/cli-base/julc org.julclang.cli.JulcMain
$J/bin/native-image --no-fallback -H:+ReportExceptionStackTraces --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    -H:ConfigurationFileDirectories=build/ni-config \
    -cp build/classes-combined-cli:build/classes:build/res-minimal-api:julc-cli/build/libs/julc.jar \
    -o build/cli-combined/julc spike.CombinedCliMain
```

- **Size:** 90,091,112 bytes without the probe and 106,375,240 with it, so javac adds **16,284,128 bytes (+18.1%)**.
- **Build time:** 61.6 s without and 83.3 s with.
- **It works there.** `build/cli-combined/julc javac embedded javactool --extract` gives output identical to the
  JVM (`compare.sh`: `SAME`), and `build/cli-combined/julc --version` still prints `julc 0.1.0-pre20`.

## Step 4: Web Image (Wasm)

`julc-wasm/build.gradle` (`wasmImageFull`) invokes `native-image --tool:svm-wasm -cp <jars> -o <dir>/julc
-H:+ReportExceptionStackTraces -H:+PrintAnalysisCallTree -H:PrintAnalysisCallTreeType=CSV
org.julclang.wasm.JulcWasm`. The probe build mirrors that, without the call-tree report, which only writes
reports. The probe classes and the blob were rebuilt with GraalVM 25.3's own `javac` and `java.base`, so that the
embedded platform matches the image's javac. The probe's output from that JDK on the JVM is identical
(`out/jvm253-embedded-*.txt`).

```
G=<scratchpad>/graalvm/x/graalvm-25.3.4.1-dev+1.1/Contents/Home
PATH=/opt/homebrew/bin:$PATH $G/bin/native-image --tool:svm-wasm \
    --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    -H:ConfigurationFileDirectories=build/ni-config -H:+ReportExceptionStackTraces \
    -cp build/classes-253:build/res-253-raw -o build/wasm/javac-probe spike.JavacProbe
node --experimental-wasm-exnref build/wasm/javac-probe.js embedded javactool
```

**First attempt: blocker.** With the gzip blob, the image built but failed at run time:

```
Exception in thread "main" java.lang.UnsatisfiedLinkError: Can't load library: zip | java.library.path = [., /usr/lib]
```

`java.util.zip.Inflater` is native (zlib), and Web Image does not provide it. The fix was to embed the blob
uncompressed (`gunzip -c`; 51,567 bytes for the minimal set). `EmbeddedFileManager` detects the gzip magic, so the
same code reads either form. Network transfer is still compressed, because the server brotli- or gzip-encodes the
`.wasm`.

**Second attempt: results.**

- **It builds.** `Finished generating 'javac-probe' in 41.0s` (41.83 s wall), with builder peak RSS 3,463,823,360
  bytes. `wasm-opt` ran as part of the build.
- **It runs, with the same output.** Under Node, `compare.sh` prints `SAME` against the JVM `jrt` baseline for both
  `javactool` and `toolprovider` (`out/wasm-embedded-*.txt`), and against the JVM embedded `--extract` run
  (`out/wasm-embedded-extract.txt`).
- **Sizes**, measured with `node sizes.mjs`, which uses Node's `zlib` with gzip level 9 and brotli quality 11.
  (The `gzip -9` command line gave 7,195,100 bytes for the same `.wasm`. Every compressed number below is from
  `sizes.mjs`, for consistency.)

  | Artifact | raw | gzip-9 | brotli-11 |
  |---|---:|---:|---:|
  | `javac-probe.js.wasm` | 19,271,365 | 7,306,320 | 5,307,956 |
  | `javac-probe.js` | 110,547 | 22,364 | 18,079 |
  | `hello.js.wasm` (floor) | 1,316,113 | 487,236 | 372,244 |

- **Load time.**
  - `node load.mjs` timed `WebAssembly.compile` on the `.wasm`: 41.4–46.4 ms over 5 runs, against 2.8–3.2 ms
    for the hello image.
  - The whole `node javac-probe.js embedded javactool` takes 0.33–0.34 s wall over 5 runs. `main` itself takes
    83–86 ms, and an empty `node -e ""` takes 0.03 s.
  - So instantiating the module and starting the image takes **about 0.22 s**. That figure is derived:
    0.33 − 0.084 − 0.03.
- **Analyze time** (5 runs):
  - cold `ok` parse and analyze: 62.8–64.7 ms;
  - warm `ok`: 5.8–6.3 ms;
  - error case: 5.1–5.4 ms;
  - blob load: 1.4–1.6 ms.
- **Node peak RSS:** 294.6–295.2 MB, against 68.6 MB for `hello.js` and 40.8 MB for an empty `node`.
- **Features, unsupported or used:**
  - `Inflater`: unsupported, see the first attempt.
  - `ServiceLoader` worked: `ToolProvider.getSystemJavaCompiler()` returned `JavacTool` in Wasm.
  - Reflection worked: `Trees.instance`, with the agent metadata.
  - Resources and resource bundles worked.
  - javac's `parse()`/`analyze()` path, as used here, needed no threads, `System.exit` or file I/O. The probe
    never calls `System.exit`.
  - Of the 4 build warnings in the combined image below, 3 come from `json-schema-validator` options that are
    already on `julc-wasm`'s class path. The fourth is `Registered down- and upcall stubs will be ignored because
    calling foreign functions is currently not supported on platform: darwin-aarch64`. None mentions javac. The
    standalone probe build printed no warning line.

### Added size on the real `julc-wasm` full image

This measures what javac would add to the browser bundle. `julc-wasm`'s full image was built at this worktree's
commit, then rebuilt with the probe also reachable through `CombinedMain`, which runs the probe on the argument
`javac` and is otherwise `JulcWasm.main`.

```
./gradlew -q --init-script <scratchpad>/print-cp.gradle :julc-wasm:printWasmCp :julc-wasm:wasmImageFull -PgraalvmHome=$G
# print-cp.gradle only prints julc-wasm's runtime class path; it is not part of the build
$G/bin/native-image --tool:svm-wasm -cp $CP -o build/wasm-base/julc -H:+ReportExceptionStackTraces org.julclang.wasm.JulcWasm
$G/bin/native-image --tool:svm-wasm --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    -H:ConfigurationFileDirectories=build/ni-config -cp build/classes-combined:build/classes-253:build/res-253-raw:$CP \
    -o build/wasm-combined/julc -H:+ReportExceptionStackTraces spike.CombinedMain
```

| `.wasm` | raw | gzip-9 | brotli-11 | `WebAssembly.compile` |
|---|---:|---:|---:|---|
| `julc-wasm` `wasmImageFull` (Gradle task, as shipped) | 24,479,330 | 8,542,048 | 6,022,295 | — |
| baseline: same class path, built by hand | 24,484,979 | 8,547,153 | 6,042,278 | 51.8–57.5 ms |
| baseline plus the javac probe (minimal platform) | 34,577,551 | 12,168,857 | 8,628,514 | 75.5–87.1 ms |
| **javac's increment** | **+10,092,572 (+41%)** | **+3,621,704 (+42%)** | **+2,586,236 (+43%)** | +~25 ms |

- The launcher `.js` is the same size in both: 111,490 bytes.
- The combined image builds in 55.5 s, against 45.4 s for the baseline.
- `node build/wasm-combined/julc.js javac embedded javactool --extract` gives output identical to the JVM. The
  probe's wall time in the combined image is 0.47–0.51 s.

**A larger platform for user programs.** Embedding the whole exported `java.base` API instead of the minimal set
(3,659 class files with GraalVM 25.3's JDK, 4,074,519 bytes uncompressed) gives a standalone `.wasm` of 23,254,939
bytes raw, 7,947,227 gzip and 5,731,817 brotli. Against the minimal-platform probe, that adds:

- 3,983,574 bytes raw, 640,907 gzip and 423,861 brotli;
- 15.2–15.8 ms of blob load at startup;
- the same output (`compare.sh`: `SAME`).

## Step 5: extraction inside the images

`--extract` prints, for every expression tree:

- the tree kind;
- the source text, from `Trees.getSourcePositions()`;
- the type, from `Trees.getTypeMirror`;
- the element, from `Trees.getElement`.

Examples:

```
expr METHOD_INVOCATION `a.v().add(k)` : java.math.BigInteger ; element METHOD java.math.BigInteger.add(java.math.BigInteger) : java.math.BigInteger
expr IDENTIFIER `a` : Probe.A ; element BINDING_VARIABLE a
expr GREATER_THAN `a.v().compareTo(k) > 0` : boolean
expr SWITCH_EXPRESSION `switch (s) { ... }` : java.math.BigInteger
```

The full `--extract` output, which covers the elements of method calls, the types of expressions, binding variables
and source ranges, is identical in all of these:

- the JVM with GraalVM 25.0.2;
- the JVM with GraalVM 25.3;
- the host native image;
- the combined CLI native image;
- the Web Image;
- the combined `julc-wasm` image.

`compare.sh` prints `SAME` for each. So the tree API JuLC would lower from (`TreePathScanner`, `Trees`, `Element`,
`TypeMirror`, `SourcePositions`) works inside both kinds of image.

## Measurements

| | JVM (25.0.2) | Host native image (25.0.2) | Web Image (25.3, Node 22) |
|---|---|---|---|
| Builds | n/a | yes, 40.5 s | yes, 41.0 s |
| Builder peak RSS | n/a | 3.30 GB | 3.46 GB |
| Artifact | class files | 37,922,856 B | `.wasm` 19,271,365 B (gzip 7,306,320; brotli 5,307,956) plus `.js` 110,547 B |
| Floor (hello) | n/a | 6.33 MB | 1,316,113 B |
| Delta on the real product | n/a | `julc` CLI +16,284,128 B (+18.1%) | `julc-wasm` full +10,092,572 B raw (+41%), +3,621,704 gzip, +2,586,236 brotli |
| Same output as step 1 | baseline | yes (both routes, `--extract`) | yes (both routes, `--extract`) |
| Startup or load | 0.27–0.29 s wall (embedded) | 0.01 s wall | compile 41–46 ms; about 0.22 s to instantiate and start (derived) |
| Cold parse+analyze (`ok`) | 172–187 ms | 3.0–3.3 ms | 62.8–64.7 ms |
| Warm parse+analyze (`ok`) | 12.6–15.2 ms | 1.0–1.3 ms | 5.8–6.3 ms |
| Peak RSS of the run | 210–220 MB | 30.4 MB | 295 MB (Node process) |
| Platform data needed | `jrt:/` or embedded | embedded (standard manager fails) | embedded, **uncompressed** (no `Inflater`) |
| Metadata | n/a | 4 reflection and 12 resource entries (agent) | same file |

## Blockers and exact errors

No blocker stopped a step. These are the failures met on the way, and how each was resolved or worked around:

| Where | Error | Resolution |
|---|---|---|
| JVM, `JavacTool.create()` without `--add-exports` | `IllegalAccessError: ... module jdk.compiler does not export com.sun.tools.javac.api to unnamed module` | `--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` (`native-image` accepts the same flag), or use `ToolProvider.getSystemJavaCompiler()`, which needs no non-exported API |
| Compiling the probe with `--release 25` | `error: exporting a package from system module jdk.compiler is not allowed with --release` | `-source 25 -target 25` |
| Native image, standard file manager | `ExceptionInInitializerError` / `NullPointerException at ... Locations.<clinit>(Locations.java:139)` (no `java.home`); with `-Djava.home`: `NotDirectoryException: .../lib/modules` then `Abort` | Custom file manager with an embedded `java.base` (step 2) |
| Web Image, gzip resource | `java.lang.UnsatisfiedLinkError: Can't load library: zip \| java.library.path = [., /usr/lib]` | Embed the blob uncompressed. Rely on HTTP compression |
| GraalVM Community | `--tool:svm-wasm` is "Unknown name in option specification" (known; ADR-054/064) | Oracle GraalVM 25.3 EA, as `julc-wasm` uses |

## Conclusion

**javac in a host native image: yes, with caveats.** `JavacTask.parse()`/`analyze()` and `Trees` run in a
`--no-fallback` native image and give output identical to the JVM. Cold analysis of the probe takes 3 ms, and
RSS is 30 MB. The caveats:

- javac's standard file manager cannot run there. JuLC must ship a custom `JavaFileManager` and an embedded,
  signature-only platform: about 54 KB `api`-stripped for what the stdlib needs, or 3.9 MB for the whole exported
  `java.base` API.
- The binary grows: +16.3 MB on the `julc` CLI (+18%).
- About 16 entries of reachability metadata are needed. The agent collects them, and they must be kept up to
  date.
- `ToolProvider` avoids non-exported API. `JavacTool.create()` needs `--add-exports`.

**javac in a Web Image: yes, with caveats.** The same probe runs under Node in a GraalVM 25.3 Web Image with
identical output. Cold analysis takes 63 ms, warm 6 ms. The caveats:

- no `java.util.zip.Inflater`, so the platform blob must be stored uncompressed;
- Web Image is preview technology, and javac inside it is an untested configuration upstream, as far as this
  spike knows (GraalVM's documentation was not consulted);
- the size cost below;
- Node RSS of about 295 MB for the probe, against 69 MB for hello. The probe's share of that inside the
  combined image was not measured.

**Estimated added size to the `julc-wasm` full bundle**, measured on the real image:

- **+10.1 MB raw, +3.6 MB gzip, +2.6 MB brotli (+41–43%)**, from 24.5 MB / 8.5 MB / 6.0 MB;
- plus up to +4.0 MB raw / +0.64 MB gzip / +0.42 MB brotli if the whole exported `java.base` API is embedded,
  instead of a JuLC-sized platform of about 54 KB;
- plus JuLC's own ledger, core and stdlib class files, about 180 KB unstripped. These could perhaps be replaced by
  the library *sources* `julc-wasm` already bundles, given to javac on its source path. That was not tested.

The `julc-wasm` VM-only image would be unaffected: it does not contain the compiler.

These figures assume JavaParser stays. If the browser frontend moved fully to javac, JavaParser's share of the
image would come off; that share was not measured.

**Risks:**

1. **Web Image maturity.** Web Image is a preview, and a GraalVM update could break javac in it. An image-level
   smoke test like this probe would be needed in CI.
2. **Bundle size.** +2.6 MB brotli is a large step for a learning playground.
3. **Platform drift.** The embedded `java.base` must come from the same JDK as the image's javac, and the blob is
   a new build artifact. The spike built it per JDK and found identical output across 25.0.2 and 25.0.4.
4. **What users can reference.** Under a minimal platform, a user's `Math.max` becomes `cannot find symbol`. That
   arguably matches JuLC's subset, but it is a policy decision.
5. **Coverage.** This is one small class. The spike did not exercise:
   - multi-unit compiles with libraries;
   - annotation processing (`-proc:none` was used);
   - `generate()`;
   - locales other than `en`/`en_SG` (bundles come from the agent's run);
   - concurrency.
6. **Memory.** The Node RSS above.
7. **Build cost.** Each image needs 3.3–3.8 GB of builder RSS and 35–85 s.

## Recommendation for ADR-065 OQ1

OQ1 asks what the playground should do *if javac cannot run in a Web Image*. **At probe level, javac can run
there.** So OQ1's lean, (a) "keep `JavaSourceParser` for the browser only", should not be adopted on feasibility
grounds. Recommendation:

1. **Record OQ1's feasibility question as answered "feasible, with listed costs"** for both the native images and
   the Web Image. The M1 table rows for the CLI-like native image and the `julc-wasm` full Web Image become
   "works, with costs":
   - a custom `JavaFileManager` and an embedded signature-only `java.base`;
   - no `Inflater` in Wasm;
   - about 16 metadata entries;
   - +16 MB on the CLI;
   - +2.6 MB brotli (+43%) on the full Web Image.

   The M1 stop gate ("javac runs nowhere but the JVM") is not triggered.
2. **Turn OQ1 into a size and policy decision, not a feasibility one.** Is +2.6 MB brotli (plus up to 0.42 MB for
   the full `java.base` API) acceptable for the full browser engine? `julc-wasm` already ships a separate VM-only
   image, so the cost falls only on the compile-capable variant. If the budget is not acceptable, (a) remains the
   fallback, and it should then be justified by size, not by feasibility.
3. **Before M2 relies on this, finish the M1 rows this spike did not cover:**
   - inside the real combined `julc-wasm` image, analyze one stdlib-using validator, giving javac the bundled
     ledger and stdlib sources (or class files), and measure the cold compile;
   - Elci's native-image shape, which takes sources from JARs;
   - the annotation-processor route.

   Also prefer `ToolProvider.getSystemJavaCompiler()` with `com.sun.source` API, to keep non-exported API at zero.
4. **Keep the embedded-platform design in the ADR**:
   - a signature-only, single-module blob from the build JDK, `ct.sym`-style but without zipfs or `java.home`;
   - a custom `JavaFileManager` serving `SYSTEM_MODULES`;
   - a negative-control test showing javac reads nothing else.

   It is what made every target work, and it gives JuLC an explicit allow-list of the JDK API visible to
   on-chain code.

## Reproduction check

`run-spike.sh` reruns steps 1 to 4 from clean `build/` subdirectories into `out-rerun/`:

```
WEB_GRAALVM=<.../graalvm-25.3.4.1-dev+1.1/Contents/Home> PATH=/opt/homebrew/bin:/usr/local/bin:$PATH ./run-spike.sh out-rerun
```

It exited 0. Every `compare.sh` check printed `SAME` (JVM `jrt` = embedded minimal = native, both routes, =
Wasm, both routes; `--extract` output equal on JVM, native and Wasm), and the negative control failed as
required.

Blob sizes were byte-identical to the first run. The native binary was again 37,922,856 bytes (build 41.5 s) and
the Web Image build took 42.2 s.

**Web Image output is not byte-reproducible.** Three builds of the same probe with the same inputs gave `.wasm`
files of 19,271,365, 19,224,749 and 19,197,301 bytes. `cmp` of the last two differs from byte 10. Brotli sizes for
the first two: 5,307,956 and 5,310,144. The `julc-wasm` baseline varies the same way: 24,479,330 bytes from Gradle
and 24,484,979 by hand. So the Wasm size deltas above carry noise of about ±0.1 MB raw. Each was measured from one
build per side. The two native builds from the final sources (the rebuild and the rerun) were both 37,922,856 bytes. They were
not compared with `cmp`. Making the images byte-reproducible was not investigated; this matters for ADR-065's
I4 (determinism) if javac goes into the images.

## Follow-up: browser demo (`web/`)

This is a small page that runs javac in the Web Image on whatever source is typed into it.

- **Engine.** `src-web/spike/JavacCheck.java` uses the `ToolProvider` route only, with no `--add-exports`. It
  installs `globalThis.javacCheck(source)`, which returns JSON:
  - each diagnostic, with kind, code, line, column, start, end and message;
  - on success, method signatures, resolved call elements and switch-expression types.

  The install uses Web Image's `@JS` interop, as `julc-wasm`'s `JsApi` does.
- **Platform blob.** 297 class files, 354,738 bytes uncompressed:
  - the 30-entry `java.base` union in `web/java-base-list.txt` (probe, on-chain stdlib and CIP-68 sets);
  - all class files of `julc-core`, `julc-ledger-api` and `julc-stdlib`, `api`-stripped and served on javac's
    `CLASS_PATH`. `EmbeddedFileManager` now serves `classpath/` entries there.
- **Browser setup.** It mirrors `julc-wasm`:
  - a classic worker (`web/worker.js`) sets the `.wasm` URL and calls `importScripts` on the launcher;
  - `web/build.sh` applies the same launcher-anchor patch as `wasmBundle`
    (`config.wasm_path = globalThis.JAVAC_WASM_PATH`).
- **Image size.** `javac-check.js.wasm` is 19,504,669 bytes raw, 7,342,516 gzip and 5,346,565 brotli. The
  launcher is 110,950 bytes. Both are build outputs, ignored by git; `web/build.sh` regenerates them.

**Verification:**

- **JVM.** `web/check-jvm.mjs` passed all 8 examples.
- **Node, through the same JS function the page calls.** `web/check-node.mjs` (`out/web-check-node.txt`) gives
  the expected error codes for all 8 examples:
  - `cant.resolve.location.args`;
  - `prob.found.req`;
  - `not.exhaustive`;
  - `pattern.dominated`;
  - `cant.ref.non.effectively.final.var`;
  - `operator.cant.be.applied.1`, for JuLC's `BigInteger >` syntax, which javac rejects;
  - two clean compiles, the probe and a small `@SpendingValidator` using `ScriptContext`, `TxInfo`, `JulcList`
    and `ContextsLib`.
- **A real validator.** With `EXTRA_SOURCE`, `julc-examples`' `Cip68Nft.java` (197 lines, a `@MultiValidator` with
  lambdas, for-each and sealed switches) analyzes with 0 diagnostics: 9 methods, 76 resolved calls and 5 switch
  types.
- **Timings in Node.** The engine is ready after 395 ms. The first analysis takes 96 ms, later ones 5–30 ms.
- **The page's worker.** `web/check-worker-node.mjs` (`out/web-check-worker-node.txt`) runs `worker.js` unchanged
  in a Node worker thread with browser-like shims (`self`, `location`, `importScripts`, `postMessage`). It was
  ready after 320 ms and returned the expected diagnostics.
- **Not verified:** the page in a real browser, which needs Wasm exception-handling (`exnref`) support, as
  `julc-wasm` does.
