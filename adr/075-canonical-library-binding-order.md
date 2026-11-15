# ADR-075: A canonical order for library bindings

**Status:** Proposed (2026-11-15). Implemented in the same change.
**Date:** 2026-11-15
**Risk class:** correctness-sensitive, **low**.
- Only the order of the `let`s that bind library methods around a validator changes.
- Each binding is a closed function value, or a value that is total and cheap. The
  [DelayedMethodPass](../julc-compiler/src/main/java/org/julclang/compiler/pir/DelayedMethodPass.java) makes every
  method with no parameters that may fail, trace or do more than a little work into a function, called where it is
  used. So the order of these bindings cannot change what a script computes, where it fails, or what it traces.
- The execution budget is unchanged too: the same bindings are evaluated, and a variable's cost does not depend on its
  de Bruijn index. Only the script's size can move, by the bits of a few indices.
- The bytes, and so the script hash, change for most programs that use two or more library methods.

**Issue:** [#328](https://github.com/bloxbean/julc/issues/328)
**Relates to:** [ADR-068](068-javac-frontend-migration.md) (library discovery D8, the frozen reference M6),
[ADR-060](060-generated-binder-namespace.md) (byte snapshots)

## Context

A validator's library methods are each bound by a `let` around the validator, with every method's dependencies outside
it. Several orders satisfy that. `ValidatorAssembly.topoSortLibraryMethods` ran Kahn's algorithm and, at each step, took
the first ready method *in the order the methods were registered*. That order came from:
- the order of the library sources the caller passed;
- for the class path's sources, the order `LibrarySourceResolver.resolve` found them in, which follows the validator's
  `import` declarations, breadth first.

Neither has a meaning in Java, yet each reached the script's bytes. JOUST reported it: moving a contract to another
package moved its import within JOUST's sorted import list, and changed the script hash although nothing else changed.

A probe on `main` (`a2bfc683`, a validator using four stdlib libraries and two user library classes) confirmed it:
- with the imports sorted, reversed or as written, the script had three different hashes;
- reversing the list of library sources changed the hash again.

## Goals

- The script does not depend on the order of `import` declarations or of library sources.
- The script does not depend on the package or the name of a library class, or on the names of a library method's
  parameters and locals. A package move then changes the script only where the package itself reaches it, such as a
  string constant.
- The order is deterministic and cheap to compute.

## Non-goals

- **The validator's own helper methods.** Their order follows their declaration order in the class through
  `MethodDependencyResolver`. That order is part of one source file, and JOUST generates it deterministically. Making it
  canonical as well is an open question.
- **Static fields.** Their initializers keep their textual order, which Java gives a meaning.
- **Minimizing script size.** The order is not chosen to make de Bruijn indices small.

## Decision

`LibraryBindingOrder.innermostFirst` replaces the registration order with a canonical one:
- **Dependencies:** a method is ready when every library method its body names is already bound. This is the same test
  as before, `PirHelpers.containsVarRef`.
- **Tie-break:** among ready methods, the next one bound is the one with the smallest **content key**. The qualified name
  decides only between methods whose keys are equal.
- **Content key:** a SHA-256 digest of the method's body in which:
  - a variable bound inside the body is written as its de Bruijn index;
  - a call of another library method is written as that method's content key, so the key is a Merkle hash over the
    dependency DAG;
  - a call of the method itself is a fixed token;
  - types and constructor names, which spell class names, are left out;
  - constants are written exactly: bytes as hex, `Data` as its CBOR.
- **Recursive groups:** methods that call each other are keyed with their calls inside the group as a fixed token. Their
  qualified names then decide where their bodies agree.
- **Stack safety:** the key is computed with a work list, not recursion.

## Invariants

- **I1.** Permuting imports or library sources leaves the script's bytes unchanged.
- **I2.** Moving library classes to other packages, or renaming them, leaves the bytes unchanged. Two methods with
  identical bodies are the one exception: they are told apart by name.
- **I3.** Every library method is bound inside the methods it calls, so each dependency is in scope where it is used.
- **I4.** The program computes the same results, failures and traces with the same budget as before. Only the order of
  closed or total bindings changes, and with it the de Bruijn indices that reach them.

## Alternatives rejected

- **Sort by qualified name.** This was simplest, and it fixes import order and library-source order. But a package
  move still crosses other packages in the sort, so JOUST's case (`org.julclang.state.examples.dsl` moving to
  `org.other.pkg` crosses `org.julclang.stdlib`) still moves the hash, and so does a class rename.
- **Sort the resolved library sources.** `LibrarySourceResolver.resolve` could return its sources sorted. That fixes
  only the class-path case: a caller that passes its own library list (the Gradle plugin, JOUST, `compile(String, List)`)
  still decides the order.
- **Put user libraries in a fixed place relative to jar libraries.** This needs to know which libraries are jars, which
  a caller that passes sources does not say, and it still orders user libraries among themselves by name.
- **Choose the order that minimizes script size.** This is a search, and the result would move with any change of any
  method.

## Compatibility

- The bytes change for programs whose library methods had more than one valid order. On `main`'s own goldens this
  moved:
  - 156 rows of the ADR-068 frozen reference;
  - four rows of the ADR-060 byte snapshot (fixtures `o11-1` and `o11-16`).

  Both tables were re-recorded and explained.
- `JavacLibrariesTest.theOrderOfTheLetsIsTheReferencesRegistration` asserted the old behaviour, that two library
  orders give two programs. It is now `theOrderOfTheLetsDoesNotFollowThePool` and asserts the opposite.
- Script hashes of deployed validators change on recompilation. This is a preview change; release notes say so.

## Risks

- **A binding that is not closed.** If some future library binding could fail or trace when bound, reordering bindings
  would change which failure or trace comes first. The DelayedMethodPass makes such methods called at their use; a
  change to it must keep this.
- **Key collisions.** Two different bodies with equal SHA-256 keys would fall back to name order. That is harmless and
  never expected.

## Verification

- `ImportOrderInvarianceTest`:
  - compiles a validator and a method at every optimization level, each in 24 variants:
    - four packages, including `org.julclang.m`, which sorts among the stdlib's;
    - two class names;
    - three random import orders and library-source orders per pair.
  - The validator uses four stdlib libraries and two user library classes, one recursive and one calling the other.
  - Every variant gives the same bytes. With the old order in place, the test fails at every level.
  - The reordered method is evaluated on the VM against Java's results.
- `LibraryBindingOrderTest` checks, on 500 random dependency DAGs including recursive methods:
  - the order is the same for every permutation of the input;
  - every method is bound inside the methods it calls;
  - moving and renaming classes and locals keeps the order;
  - methods that call each other are ordered canonically.
- The full suites, the difftest JVM/UPLC agreement and the external examples are recorded in the PR.

## Open questions

- Canonical order for a validator's own helper methods, independent of their declaration order.
