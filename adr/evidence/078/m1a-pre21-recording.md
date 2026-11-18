# ADR-078 M1a: how the pre21 expectations were recorded

The D5 tests compare M1a with `0.1.0-pre21` (`08b021ce`) without loading pre21 at test time. Their pre21 side is a
pair of committed recordings, both produced by the test sources themselves:

| Recording | Producer | What it holds |
|---|---|---|
| `julc-compiler/src/test/resources/adr078/pre21-fixtures.txt` | `Adr078Fixtures.main` | For every fixed fixture, variant and mode (4 levels, with and without source maps): the compilation (SHA-256 of the FLAT program and its size, or `REJECTED` and the whole rejection); the sorted multiset of method binders (I3); with source maps, the complete map. For every compiled `compileMethod` variant, the evaluation of each argument vector at `REPLAY_LIMIT` and at `HIGH_LIMIT`: the result as a FLAT term, the whole failure reason or exhaustion, and the traces as a JSON array. The first vector is empty, so its result is the function itself, which is then applied to the next vector, at both limits. Budgets are on separate lines, and C12 also has its tight-limit outcome. For each rejected variant, the exact diagnostic with `julc.verifyBinderNamespace` off and on, which `rejectionsSayWhatPre21Said` compares exactly. The tests compare 1,152 evaluations against it, and check that the evaluation ids made equal the recorded ones, both ways. |
| `julc-compiler/src/test/resources/adr078/pre21-programs.txt` | `Adr078Programs.main` | The digest of every generated source (a stale recording fails the test), and, for each generated program (seed 78, 10 programs, 40 rewrites each, alternately an exact reversal of the base's qualified-name order and a random renaming) and mode, how many rewrites compile and how many give the base's bytes. |
| `julc-tools/src/test/resources/adr078/pre21-debug.txt` | `Adr078DebugFixtures.main` | For each debug fixture program, as JuLC's source debugger (`SourceDebugService`) shows it: the executable lines; the line and column of every Java-breakpoint stop, with a breakpoint on every executable line; the visible locals at each stop; every change of Java location over all steps; the final status. |

## The pre21 class path

- **Coordinates.** The recorders use the public `0.1.0-pre21` artifacts from Maven Central: `org.julclang:julc-tools`
  and `org.julclang:julc-vm-java` at `0.1.0-pre21`. Their transitive dependencies bring in `julc-compiler`,
  `julc-stdlib`, `julc-vm`, `julc-core`, `julc-ledger-api` and the rest at the same version.
- **Building it without JOUST.** `pre21-classpath.build.gradle.txt` is a two-dependency Gradle build. Copy it to
  `build.gradle` in an empty directory, add `settings.gradle`, and run it with any Gradle 9 wrapper:

  ```
  CP_OUT=cp.txt ./gradlew -p <that dir> --offline printCp
  ```

  The compiler fixtures were first recorded on JOUST's `julc-state-toolchain` test class path, which resolves the same
  artifacts. Recording them again on this class path gave identical lines.

The recorders, and the commands that run them on that class path:

```
javac -d pre21rec -cp "$(cat cp.txt)" \
    julc-compiler/src/test/java/org/julclang/compiler/adr078/Adr078Fixtures.java \
    julc-compiler/src/test/java/org/julclang/compiler/adr078/Adr078Programs.java \
    julc-tools/src/test/java/org/julclang/tools/adr078/Adr078DebugFixtures.java
java -cp "pre21rec:$(cat cp.txt)" org.julclang.compiler.adr078.Adr078Fixtures > pre21-fixtures.txt
java -cp "pre21rec:$(cat cp.txt)" org.julclang.compiler.adr078.Adr078Programs > pre21-programs.txt
java -cp "pre21rec:$(cat cp.txt)" org.julclang.tools.adr078.Adr078DebugFixtures > pre21-debug.txt
```

The header lines were then prepended. `pre21-debug.txt` is in `julc-tools/src/test/resources/adr078/`.

## Notes

- **Rejection text.** In the Gradle test JVM, `julc.verifyBinderNamespace` is set, so an unread mutually recursive
  library pair is rejected by the I13 binder check ("has no binder in scope"). Outside it, the rejection comes later,
  from the backend ("Unbound variable"). The recording was made outside it. The tests therefore compare *whether* a
  variant is rejected, not the rejection's wording.
- **No limit-free evaluation.** The recordings evaluate under a transaction's maximum budget (`REPLAY_LIMIT`). A replay
  that exhausts it would be reported as inconclusive. None did: 512 evaluations were compared, 0 inconclusive.
