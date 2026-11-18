# ADR-078 M0 evidence: the diagnosis

All runs on 2026-11-18, JDK Liberica 25.0.4, JuLC `0.1.0-pre21` (tag `v0.1.0-pre21`, `08b021ce`) as JOUST resolves it
from Maven.

## Files

| File | What |
|---|---|
| `Adr078Diag.java.txt` | Compiles a JOUST `.jsc` example with JOUST's `Toolchain` as committed and after `JscV2HashStabilityTest.renameAll`, then recompiles the generated validator with `JulcCompiler.compileContractWithDetails` over the same pool and options, checks the hash equals the toolchain's, and writes the UPLC. |
| `crowdfund-diff.txt` | Crowdfund and Tips: the binders that move, the only lines of name-stripped UPLC that differ, and the two generated methods. |
| `joust-corpus-renameall.txt` | All 67 JOUST examples, as committed vs `renameAll`: how many lines of name-stripped UPLC differ. |
| `Adr078Probe.java.txt` | JuLC-only probes of the three cases (no JOUST). |
| `probe-results.txt` | The probes' hashes and name-stripped UPLC diffs, and the shared dispatcher's ids. |

## Commands

```
# JOUST worktree joust-julc-pre21 at 5522cafc (branch chore/julc-0.1.0-pre21); nothing was written there.
# 1. The toolchain module's test class path, by an init script that writes sourceSets.test.runtimeClasspath:
./gradlew --offline --no-daemon -q -I cp.init.gradle :julc-state-toolchain:printTestCp
# 2. Compile the scratch programs against it (package org.julclang.state.toolchain, to reach the test's helpers):
javac -d classes -cp "$(cat cp.txt)" Adr078Diag.java Adr078Probe.java
# 3. Run from julc-state-toolchain/ (the test reads ../examples/dsl):
java -cp "classes:$(cat cp.txt)" org.julclang.state.toolchain.Adr078Diag out Crowdfund.jsc   # or ALL
java -cp "classes:$(cat cp.txt)" probe.Adr078Probe probe-out
# 4. Name-stripped comparison (FLAT keeps no names, so this compares what reaches the bytes):
sed -E 's/\(lam [^ ()]+/(lam _/g' X-base.uplc | tr ' ' '\n' > a; (same for X-renamed) > b; diff a b
```

## Results

- Crowdfund reproduces: `28e115b5…` as committed, `f338eb56…` after `renameAll`, 6,284 B both. The
  details compile gives the same two hashes as the toolchain.
- The UPLC has 680 binders in both, in the same order but for one adjacent pair:
  - `Crowdfund.contribute__orElse1` and `Crowdfund.refund__orElse2`, at positions 69 and 70, swap.
  - The two JOUST-generated methods have identical bodies (`o.isPresent() ? o.get() : 0`).
  - `refund` is not renamed (JOUST keeps it as a language word), and `contribute` becomes `zqaai`, so the qualified-name
    tie-break of ADR-075 flips.
  - Two de Bruijn indices change (`i33`→`i32`, `i25`→`i26`). Nothing else changes.
- Across the 67 JOUST examples only Crowdfund and Tips move, and Tips moves the same way: `claim__orElse5` and
  `credit__orElse2` swap. That is ADR-075 exception 1 (identical bodies ordered by name). No JOUST example uses the shared
  shape checker; `#__julc-shape-check` occurs in none of the 67 programs.
- The JuLC-only probes reproduce all three cases:
  - **Case 1.** Renaming one of two identical library methods changes the hash.
  - **Case 2a.** Renaming library class `A` to `Z` changes the shared checker's ids. Credential goes from 0 to 3 and
    TxOutRef from 2 to 0, and the dispatcher's chain is reordered.
  - **Case 2b.** Swapping the two methods inside one library class has the same effect, with no rename.
  - **Case 3.** Swapping two validator helper methods changes the hash.
  - **Case 3b.** Swapping two validator helpers that each check a shape changes both the helpers' order and the ids.
  - **Control.** Renaming the validator's helpers while keeping their order changes nothing.
