# Milestone E.4h — compositional authorization algebra

This directory contains reproducible evidence for
[ADR-024](../../adr/verification/024-milestone-e4h-authorization-algebra.md).
Experimental inner DSL schema 6 adds authorization relations over the complete
pinned V3 `txInfoSignatories` list without changing validator compilation.

The positive solver fixture proves that every successful execution of the
exact recorded spending validator has exactly two distinct approved committee
signers. The validator itself also rejects signers outside that committee, and
the exact-VM control covers that behavior. `noUnexpectedSigners()` remains an
independent composable relation with kernel-reduced duplicate, ordering, and
outsider controls; it is not silently folded into `exactlySigned(2)`.

An attempted combined `exactlySigned(2).and(noUnexpectedSigners())` proof did
not finish within a ten-minute calibration window at the pinned Blaster/Z3
revision. It is therefore not retained as successful SMT evidence. Users may
still request that stronger property, but an undetermined result must be
treated as `COULD-NOT-EVALUATE`, never as verification success. Duplicate
authorities and signatories count once for thresholds, and order does not
affect authorization relations. Raw list equality elsewhere in the DSL
remains ordered and duplicate-sensitive.

Run local evidence with:

```bash
verification/e4h/scripts/verify.sh
```

Run the positive control through Docker with:

```bash
E4H_BACKEND=docker verification/e4h/scripts/verify.sh
```

For the native launcher, build `:julc-cli:nativeCompile` with GraalVM 25.0.2
and invoke `julc-cli/build/native/nativeCompile/julc verify dsl` with the same
project, specification classpath/source, purpose, fuel 1700, recursive depth
4, and an `authorized-native` output directory. The native executable still
uses an installed child JVM for trusted Java property-builder execution. Its
certificate records the authenticated proof backend (`local`), not launcher
flavor.

Schema 6 accepts zero-free fixed 28-byte key hashes, explicitly bridged
compiler-owned datum/redeemer byte-string fields, and contract `List<byte[]>`
values. A
dynamic authority list may be empty; `allSigned()` then has the mathematical
empty-subset result `true`, so authorization properties should normally use a
positive threshold when emptiness is not intended.

The zero-free restriction is temporary and fail-closed: a conformance control
found that the pinned Blaster translation can collapse an embedded UPLC
constant containing `00` while the Lean property literal retains it. JuLC's VM
and Lean kernel preserve the bytes. Arbitrary Cardano key hashes are therefore
not approximated; a zero-containing fixed literal is rejected until the pinned
solver path is repaired and regression-tested.

Applied `@Param` authorities are deliberately unavailable in this milestone.
A blueprint parameter declaration does not authenticate the deployed value.
JuLC will expose parameter authorities only after runner preflight can
reconstruct ordered parameter application and prove that its UPLC bytes and
script hash equal the exact artifact being verified.

The trusted Java property builder still executes in the bounded child JVM.
An `SMT-VALID` result covers only the named implication for the exact artifact,
selected domain, CEK fuel, recursion depth, solver bounds, and pinned tools. It
does not prove key ownership, cryptographic signatures, multisig governance,
or whole-contract safety.

The evidence driver uses CEK fuel 1700. A 1000-fuel calibration completed but
correctly classified the property as vacuous because it could not establish a
successful symbolic execution. A 5000-fuel attempt spent more than eight
minutes elaborating the exact obligation before cancellation, so that larger
bound is not presented as practical evidence. The earlier recursive allow-list
fixture passed non-vacuity at fuel 1500, but its combined allow-list theorem
did not complete in the calibration window. The final 422-byte validator uses
an equivalent exact two-entry structural guard. At fuel 1700 and recursive
depth 4 its positive non-vacuity control completes in roughly 20 seconds. The
non-vacuity step must find a successful execution at the recorded bound;
otherwise the claim is rejected rather than accepted vacuously.
