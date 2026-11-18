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
# 2. Compile the scratch programs against it (Adr078Diag is in package org.julclang.state.toolchain, to reach the
#    test's helpers; it calls probe.Adr078Census, so compile them together; the probe files are independent):
javac -d classes -cp "$(cat cp.txt)" Adr078Diag.java Adr078Census.java Adr078ExamplesCensus.java Adr078Probe.java \
      R2Probes.java R3Probes.java R4Probes.java C1Probe.java
# 3. Run from julc-state-toolchain/ (the test reads ../examples/dsl):
java -cp "classes:$(cat cp.txt)" org.julclang.state.toolchain.Adr078Diag out Crowdfund.jsc   # or ALL
java -cp "classes:$(cat cp.txt)" probe.Adr078Probe probe-out
java -cp "classes:$(cat cp.txt)" probe.Adr078ExamplesCensus ../julc-examples/src/main/java   # julc-examples@49614db8
# Census flags: -Dadr078.locals=true adds the locals-rename variant; -Dadr078.pir=true writes the PIR.
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

## r2 additions (after review round 1)

| File | What |
|---|---|
| `Adr078Census.java.txt` | From a compiled program's PIR, recovers the library bindings and their ADR-075 content keys (by reflection on `LibraryBindingOrder`), computes D1's ranks (`T`, then D1b for unreachable methods), and simulates Kahn's order with pre21's comparator and with M1a's. It flags D1b ties between bodies that are not α-equal (the residual), and equal-key groups. |
| `Adr078ExamplesCensus.java.txt` | Runs the census over a source tree of validators (julc-examples). |
| `m1a-census-joust.txt` | The census over the 67 JOUST examples. The summary is at the end: M1a's order is the same before and after `renameAll` for 67/67; there are no residual ties; 5/67 change the order of the binders present in the default-profile UPLC; 17/67 change the full order (`NONE`, source maps); 12/67 keep unreachable library bindings after the default optimizer. |
| `m1a-census-julc-examples.txt` | The census over `../julc-examples` (43 validators compiled, 3 skipped): no tie between reachable methods, and no simulated order change. Corrected in r5: 31 of 43 programs have a D1b name tie between unread methods (see the r4 table). |
| `m1a-case1-probe.txt` | The JuLC-only two-identical-bodies probe. M1a's `key@rank` order is the same for both names. |
| `C1Probe.java.txt`, `c1-probe-results.txt` | Reproduces Codex C1. A datum holding two records with the same simple name and fields is 276 B; renaming one gives 318 B. |

The census simulates M1a on pre21's captured PIR (after the PIR passes, before UPLC). Its counts are estimates. M1a's
exit measures them on the implementation.

## r3 additions (after review round 2)

| File | What |
|---|---|
| `R2Probes.java.txt`, `r2-probes.txt` | Codex round-2 findings run against pre21 at the default profile, `NONE` and source maps. **C11** (an unread helper reads one twin): identical at the default profile, different at `NONE` and with source maps. **C13** (a cross-class recursive type group, class `A` renamed to `Z`): different in all three modes. **C14** (one overload-family member renamed): 88 → 96 B. **C15**: a mutually recursive library pair is rejected ("Unbound variable") whether reachable or not, and a self-recursive method compiles. **C12**, my reconstruction from the review summary: identical 998 B and 30,764,348 CPU for both twin names, so Codex's numbers are not reproduced (the mechanism is accepted). |
| `m1a-census-joust.txt` (regenerated) | Every figure is labelled as a simulation (C16). Adds `D1A-ONLY` (base vs renamed with unread ties by name: 67/67 equal on JOUST) and the corrected maximum: 283 library methods (Safes). D1a′ (r3) is not simulated. |

## r4 additions (after review round 3)

| File | What |
|---|---|
| `C12.java.txt` | Codex's exact C12 program, adopted as a D5 fixture. On pre21: `alpha` gives 1,010 B and 31,073,556 CPU and succeeds; `zeta` gives 1,012 B and 31,201,556 CPU and exhausts the budget under `ExBudget(31_150_000, 110_000)`. |
| `R3Probes.java.txt`, `r3-probes.txt` | Round-3 findings run against pre21. **C12:** reproduced exactly. **C19:** α-equal twins with different local spellings; renaming the method changes FLAT with source maps (`471fb25d` → `18ed8feb`, 45 B); with O10 disabled, or at `NONE`, nothing changes. Local renames alone also change bytes. **C19c:** a reached library method's local, renamed to collide with another method's local, gives 33 → 73 B at the default profile. **C20:** pool `[B, A]` compiles and `[A, B]` is rejected ("Undefined variable: B"). |
| `m1a-census-*.txt` (regenerated) | Labelled as label-sequence comparisons (C23). Adds `anyNameTie` and `spellingTie`: every JOUST program, and 31 of 43 julc-examples programs, has a D1b name tie; 19 JOUST programs (all `AddressAdapter.payment` / `AddressLib.paymentCredential`) and no julc-examples program have one whose values differ as written. |

## r5 additions (after review round 4)

| File | What |
|---|---|
| `R4Probes.java.txt`, `r4-probes.txt` | **C26:** a root that is also its own library, with `alpha`/`beta` bodies that read `xs.get(0)` and `xs.get(1)`. Renaming `alpha` to `zeta` changes FLAT at `PV11_COSTED` with source maps (`d471844a` → `b378ec82`, 311 B). Nothing changes with O9 (`pv11.o9.list-to-array`) disabled, at `pv11-safe` or `NONE`, or with source maps off. **C27:** an unnamed-package `@OnchainLibrary class Math` whose `magnitude` is renamed to `abs` goes to the stdlib intrinsic: 33 → 28 B at the default profile, 37 → 52 B at `NONE`. |
| `joust-corpus-locals.txt` | JOUST's "locals, lambda and loop variables, fns, parameters and methods renamed" edit, on pre21, for all 67 examples: lines of name-stripped UPLC that differ, at the default profile. 0 for every example. |

The two census measurements are separate (C34): (a) the order filtered to binder names in the default-profile UPLC
changes for 5/67; (b) the full simulated order changes for 17/67. Both are simulations compared as label sequences.

## M1a (implementation)

| File | What |
|---|---|
| `m1a-results.md` | The M1a results: in-repo oracles and Blaster unchanged, D5's results, the julc-examples gate (pass), compile time, and what was not done. |
| `m1a-pre21-recording.md` | How the committed pre21 expectations of the D5 tests (`julc-compiler/src/test/resources/adr078/`) were produced. |
| `Adr078ExamplesGate.java.txt` | The julc-examples gate runner: release FLAT and source-map-on FLAT plus the complete `DFS index → SourceLocation` map per validator; `-Dadr078.timingRounds=N` adds the compile-time rounds. |
| `m1a-gate-julc-examples-pre21.txt`, `m1a-gate-julc-examples-m1a.txt` | The gate's output on pre21 and on M1a (`../julc-examples@49614db8`). They are identical for all 43 programs. |
| `m1a-corpus-replay.txt`, `Adr078Replay.java.txt`, `compare_replay.py.txt` | ADR-075's corpus replay, run on pre21 and on M1a: the two outputs are identical (47,593 FLAT digests and 266,441 evaluations; corrected in r8 and r9). |
| `pre21-classpath.build.gradle.txt` | A two-dependency Gradle build that resolves the pre21 class path (`julc-tools` and `julc-vm-java` at `0.1.0-pre21`), so it can be rebuilt without JOUST. |
| `m3-joust-gate.txt` | M3, JOUST's half of the gate, run by the coordinator on `4bac295d`: passed (67 contracts × 10 edits, `renameAll` included; maps-on 67/67 identical). |
| `m1a-replay-comparator-controls.txt` | Negative controls of the replay comparator and runner (C12, C20, C21, C24, C27, C29–C31, C34). These fail: a changed result, trace or FLAT; a missing record; a missing or orphan higher-limit retry; a malformed record or unknown kind; an extra exclusion; any harness error, including the runner's own path-staging failure. Budget-only changes and inconclusive cases are reported and pass. |
| `C22Probes.java.txt`, `c22-probes.txt` | Codex's C22 on pre21 and on the head: a custom registry's materialised `q.M.b` changes the order z, a, b to a, b, z, and a genuine cycle beside it is reported as `q.M.d` where pre21 said `q.M.a`. |
| `m3-joust-gate-2c0edb6e.txt` | M3 re-run by the coordinator on `2c0edb6e`: passed, every `[hash]` line identical to the `4bac295d` run. |
| `m3-joust-gate-6c68f45a.txt` | M3's final gate run by the coordinator on the final main code `6c68f45a`: passed, all 73 `[hash]` lines identical to the earlier runs. M3 complete. |
| `compare_replay_controls.py.txt` | The script that produces `m1a-replay-comparator-controls.txt` from the archived replay outputs (round 4). |
