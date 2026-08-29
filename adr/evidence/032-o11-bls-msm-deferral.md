# ADR-032 O11 evidence: typed BLS MSM deferral

**Issue:** [#96](https://github.com/bloxbean/julc/issues/96)

**Decision:** defer explicit typed MSM and automatic fusion from ADR-032
Milestone 2.

## Repository evidence

The existing BLS surface represents G1, G2, and Miller-loop results as the same
Java/PIR `byte[]` type. Its MSM methods accept `PlutusData` for both collections.
JuLC's ordinary `JulcList<BigInteger>` and `JulcList<byte[]>` lower elements
through Data wrappers, but the PV11 MSM builtins require native integer lists
and native G1/G2 element lists.

Changing only the Java signatures would therefore create an API that looks
typed while still emitting the wrong representation. Recognizing scalar-mul
and add chains over the current `byte[]` surface would also allow group mixing
and could change scalar/list failure and evaluation order.

## Safety decision

No BLS typing or fusion rule is shipped. In particular, JuLC will not:

- infer G1/G2/Miller identity from interchangeable `byte[]` terms;
- treat Data-wrapped JuLC lists as native builtin lists;
- fuse across compression, uncompression, group changes, traces, or failures;
- enable MSM based only on PV11 target legality.

O11 can resume only after a focused type-design ADR establishes distinct
native G1/G2/Miller PIR types, safe constructors for native scalar/point lists,
precise empty/mismatched-list and scalar semantics, experimental API migration,
and Java/Truffle differential fixtures. Keeping the existing raw explicit MSM
surface unchanged is safer than adding a parallel partially typed model.
