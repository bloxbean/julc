# ADR-078 M1a: how the pre21 expectations were recorded

The D5 tests compare M1a with `0.1.0-pre21` (`08b021ce`) without loading pre21 at test time. Their pre21 side is a
pair of committed recordings, both produced by the test sources themselves:

| Recording | Producer | What it holds |
|---|---|---|
| `julc-compiler/src/test/resources/adr078/pre21-fixtures.txt` | `Adr078Fixtures.main` | For every fixed fixture, variant and mode (4 levels, with and without source maps): the SHA-256 of the FLAT program and its size, or `REJECTED` and the first line of the rejection. For every compiled one, the evaluation of each argument vector under `REPLAY_LIMIT`: the result term, failure reason or exhaustion, the traces, and the budget on a separate line. |
| `julc-compiler/src/test/resources/adr078/pre21-programs.txt` | `Adr078Programs.main` | For each generated program (seed 78, 3 programs, 40 rewrites each) and mode: how many rewrites compile, and how many give the base's bytes. |

## The pre21 class path

`Adr078Fixtures` and `Adr078Programs` use only JuLC's public compiler API, so they compile against the pre21 artifacts.
They were compiled and run on the test class path of JOUST's `julc-state-toolchain` at `joust-julc-pre21@5522cafc`.
That class path resolves `org.julclang:*:0.1.0-pre21` from Maven Central; `cp.txt` is printed as the M0 README
describes.

```
javac -d pre21rec -cp "$(cat cp.txt)" \
    julc-compiler/src/test/java/org/julclang/compiler/adr078/Adr078Fixtures.java \
    julc-compiler/src/test/java/org/julclang/compiler/adr078/Adr078Programs.java
java -cp "pre21rec:$(cat cp.txt)" org.julclang.compiler.adr078.Adr078Fixtures > pre21-fixtures.txt
java -cp "pre21rec:$(cat cp.txt)" org.julclang.compiler.adr078.Adr078Programs > pre21-programs.txt
```

The header lines were then prepended. To re-record after changing a fixture, run the same commands on the pre21 class
path.

## Notes

- **Rejection text.** In the Gradle test JVM, `julc.verifyBinderNamespace` is set, so an unread mutually recursive
  library pair is rejected by the I13 binder check ("has no binder in scope"). Outside it, the rejection comes later,
  from the backend ("Unbound variable"). The recording was made outside it. The tests therefore compare *whether* a
  variant is rejected, not the rejection's wording.
- **No limit-free evaluation.** The recordings evaluate under a transaction's maximum budget (`REPLAY_LIMIT`). A replay
  that exhausts it would be reported as inconclusive. None did: 512 evaluations were compared, 0 inconclusive.
