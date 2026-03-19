# julc-benchmark

JMH benchmarks for JuLC VM backends — compares the pure Java CEK interpreter against the GraalVM Truffle JIT CEK machine.

## Benchmark Classes

| Class | VM Backend | Description |
|-------|-----------|-------------|
| `CekJavaBenchmark` | `JavaVmProvider` | Pure Java iterative CEK interpreter (priority 100) |
| `CekTruffleBenchmark` | `TruffleVmProvider` | GraalVM Truffle AST-walking interpreter with optional JIT (priority 200) |

Both benchmarks directly instantiate their respective providers — no ServiceLoader auto-selection.

## Running Benchmarks

### Run a specific VM backend

```bash
# Java VM only
./gradlew :julc-benchmark:jmh -PjmhInclude=CekJavaBenchmark

# Truffle VM with JIT enabled (default on GraalVM JDK)
./gradlew :julc-benchmark:jmh -PjmhInclude=CekTruffleBenchmark

# Both VMs side-by-side
./gradlew :julc-benchmark:jmh
```

### Truffle JIT control

On **GraalVM JDK**, Truffle JIT is enabled by default. The JIT compiles hot call targets to native code after ~1000 invocations, providing significant speedup for repeated evaluation of the same script.

On **standard JDK** (OpenJDK, Corretto, etc.), Truffle always runs in interpreter mode — no JIT compilation. Performance is comparable to the Java VM in this case.

```bash
# Truffle with JIT enabled (default on GraalVM)
./gradlew :julc-benchmark:jmh -PjmhInclude=CekTruffleBenchmark

# Truffle WITHOUT JIT (interpreter only — isolates Truffle AST overhead)
./gradlew :julc-benchmark:jmh -PjmhInclude=CekTruffleBenchmark -PnoJit
```

The `-PnoJit` flag passes `-Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime` to the JMH forked JVM, forcing interpreter-only mode even on GraalVM.

## Test Data

Benchmark scripts are `.flat` encoded UPLC programs. The `@Param` annotation uses an empty default — file names are provided externally.

### File loading priority

1. **Filesystem**: `data/{filename}` (used by Docker/external harness)
2. **Classpath**: `src/jmh/resources/data/{filename}` (local development)

### Providing files

**Via JMH `-p` option** (external harness / command line):
```bash
java -jar julc-benchmark-jmh.jar -p file=escrow-redeem_1-1.flat CekTruffleBenchmark
```

**Via the `BenchmarkFiles.ALL` constant** (programmatic use):
The `BenchmarkFiles` class contains all 89 bundled file names for reference.

## Integration with cardano-plutus-vm-benchmark

This module is designed for integration with [cardano-plutus-vm-benchmark](https://github.com/SAIB-Inc/cardano-plutus-vm-benchmark), which benchmarks UPLC VM implementations across languages (Rust, Zig, C#, Go, TypeScript, Python, Java).

The external harness:
1. Provides `.flat` files in `data/` directory on the filesystem
2. Invokes JMH via a runner script with `-p file=xxx.flat` for each test case
3. Parses JMH CSV output into a unified comparison format

**Runner script integration points:**
- JMH JAR: `julc-benchmark/build/libs/julc-benchmark-*-jmh.jar`
- File param: `-p file=<filename>.flat`
- JIT control: add `-jvmArgs -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime` to disable JIT
- Results: CSV output from JMH (`-rf CSV -rff results.csv`)

**Example runner invocation:**
```bash
# Truffle JIT enabled
java -jar julc-benchmark-jmh.jar \
  -p file=escrow-redeem_1-1.flat \
  -i 1 -wi 1 -f 1 -r 5s -w 5s \
  -rf CSV -rff results.csv \
  CekTruffleBenchmark

# Truffle JIT disabled (interpreter only)
java -jar julc-benchmark-jmh.jar \
  -jvmArgs -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime \
  -p file=escrow-redeem_1-1.flat \
  -i 1 -wi 1 -f 1 -r 5s -w 5s \
  -rf CSV -rff results.csv \
  CekTruffleBenchmark
```

## JMH Configuration

| Setting | Value | Override |
|---------|-------|---------|
| Warmup iterations | 1 | `-wi N` |
| Measurement iterations | 1 | `-i N` |
| Warmup time | 5s | `-w Ns` |
| Measurement time | 5s | `-r Ns` |
| Forks | 1 | `-f N` |
| Threads | 1 | `-t N` |
| Result format | CSV | `-rf CSV` |

## Results

CSV results are written to `julc-benchmark/build/results/jmh/results.csv`.
