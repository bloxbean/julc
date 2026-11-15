# ADR-074: Stack-safe term walks

**Status:** Proposed (2026-11-15).
**Date:** 2026-11-15
**Risk class:** correctness-sensitive, **medium**. Every walk over PIR and UPLC terms changes form. None changes what it
computes, and the output of every compilation is meant to stay byte for byte as it was (I2). The risk is a walk whose
steps now run in a different order. A changed order could move a fresh name, a source position or a debug record, or
choose another rewrite.
**Issue:** [#327](https://github.com/bloxbean/julc/issues/327)
**Relates to:** [ADR-064](064-embeddable-wasm-engine.md) (the Wasm engine), [ADR-068](068-javac-frontend-migration.md)
(the javac frontend), [ADR-060](060-generated-binder-namespace.md) (generated names)

## Context

A validator's PIR term nests as deeply as the validator is long, not as deeply as its Java nests. Some examples:
- Every helper method is a `let` around the rest of the program, so 300 helpers nest 300 levels.
- Every statement of a block is the body of the one before.
- Every `if (...) return ...;` in a row is the `else` of the previous one.
- Every element of a list literal is a `cons` around the rest.

javac handles all of these in loops. JuLC's walks over PIR and UPLC recursed on the Java stack. These included:
- the PIR passes;
- UPLC generation and the optimizer;
- the PIR verifier;
- FLAT encoding and decoding;
- the source-map serializer;
- the call graph's SCC.

A long program therefore exhausted the stack:
- **GraalVM Web Image** (the Wasm engine, ADR-064) runs on Node's default main-thread stack of about 1 MB. JOUST contracts failed there with about 270 to 300 `else if` branches, each lowered to a helper method. Some of them are under 16 KB, so they are deployable. The overflow reached JavaScript as an opaque `TypeError: type incompatibility when transforming from/to JS`, not as a `StackOverflowError`. Whether a program failed also depended on engine warm-up.
- **JVM:** with `-Xss256k`, a 1,000-helper chain overflowed. The first frame was `MethodDependencyResolver.strongConnect`, the Tarjan SCC, which recurses along the call chain. Then came `NarrowingElisionPass.holdsNarrowing` / `PirHelpers.mapChildren`, then every other walk in turn.

A host can work around this with a bigger stack (JOUST now runs its engine on an 8 MB worker), but every other host
of the Wasm engine hits the overflow. A Web Image has no threads, so JuLC cannot move the work to a thread with a stack
of its own choosing either.

## Goals

- A program's compilation, from the PIR that lowering builds to the FLAT bytes, uses an amount of Java stack that
  does not grow with the program's length.
- No compilation output changes: script bytes, source maps, debug metadata, diagnostics and optimization reports.
- One mechanism, used the same way by every walk, readable next to the recursion it replaces.

## Non-goals

- **Nesting in the Java source.** An expression inside an expression or a block inside a block is limited by javac's
  own recursion, which runs first. JuLC's front-end walks over javac trees follow that nesting and keep recursing; a
  front end that generates Java caps its nesting (JOUST's `.jsc` caps it at 64).
- **Depth inside data.** A deeply nested `PlutusData` constant, or a deeply nested type, is walked recursively as
  before.
- **Text.** `UplcParser` keeps its own nesting limit of 1,000.
- **Evaluation.** The VMs are unchanged. The Java CEK machine evaluates deep terms in a loop; converting a deep
  *result* value back to a term (`ValueConverter`) still recurses.

## Decision

### D1. `Trampoline`: a recursion whose pending work is on the heap

`org.julclang.core.recursion.Trampoline<T>` (julc-core) describes a recursive computation. A recursive method returns
a `Trampoline` and starts with `Trampoline.defer`, so that calling it only describes the work. `run()` performs it in
a loop with an explicit stack of continuations:

```java
Trampoline<PirTerm> walk(PirTerm term, Scope scope) {
    return Trampoline.defer(() -> switch (term) {
        case PirTerm.Let let -> walk(let.value(), scope).then(value ->
                walk(let.body(), scope.with(let)).map(body -> new PirTerm.Let(let.name(), value, body)));
        ...
    });
}
PirTerm result = walk(term, null).run();
```

Steps run in the order the code describes, each finishing before the next starts: `a.then(x -> b)` runs all of `a`
before any of `b`. A walk that reads or updates state as it goes therefore behaves as its recursive form did.
`each`, `any` and `all` sequence a list in order, `any` and `all` stopping where `||` and `&&` would. One `run()`
drives a recursion; a step may `run()` an unrelated computation, never one that re-enters the walk it is part of.

A simple predicate or collector whose order does not matter uses an explicit work list instead
(`PirHelpers.anyTerm`). So does a walk whose order is easy to keep by hand, such as FLAT encoding, a pre-order walk
with list markers.

### D2. Term equality without recursion

`PirTerm` and UPLC `Term` records keep their structural `equals`, but compute it with a work list
(`PirTermEquality`, `TermEquality`). The UPLC optimizer compares whole programs to find its fixpoint, and the passes
compare a rebuilt term with the original. `hashCode` is unchanged: nothing hashes whole terms, and the default stays
consistent with an `equals` that gives the same answers.

### D3. Graph walks

Both Tarjan SCC walks keep the depth-first path on a heap stack, with one frame per vertex holding its next edge:
`MethodDependencyResolver` (methods) and `TypeGroups` (types). Edges are visited in the same order, so the components
and their members come out as they did.

### D4. What is converted

- **julc-core:** `UplcFlatEncoder`, `UplcFlatDecoder`, `SourceMapSerializer`, `UplcPrinter`.
- **PIR (`org.julclang.compiler.pir`):**
  - the PIR passes, `PirSubstitution` and the `PirHelpers` walks;
  - `TermTypes` and `PirFormatter`.
- **UPLC and backend:**
  - `UplcGenerator`, `BooleanProof`, `UplcOptimizer`;
  - `PirVerifier`, `PirClosure`, `PirLinker`;
  - the codegen term walks, `DebugMetadataCollector`.
- **Front end:** the lowering steps that follow a block's statements one after another.

(The implementation's commit lists each function.)

## Invariants

- **I1.** No walk over a term built from a whole program recurses on the Java stack along the term: compiling the
  shapes in `DeepProgramCompileTest` succeeds on a 256 KB stack at every optimization level, with a source map and with
  debug metadata, and the FLAT round trip succeeds on the same stack.
- **I2.** Every compilation's outputs are unchanged. A corpus recorded from the repository's own test suites is
  replayed on `main` and on this change, and every fingerprint matches (Verification).
- **I3.** A converted walk performs its side effects in the original order: fresh names, positions, provenance
  records, optimizer rule records, bit writes.
- **I4.** Term equality gives the record's answer, and `hashCode` stays consistent with it.

## Alternatives rejected

- **Run the compiler on a thread with a large stack.** This works on a JVM, and a host could do it. A Web Image has no
  threads, and the issue is for every host.
- **Cap the depth with a diagnostic.** The front end could measure the term and refuse a program past a bound. A
  bound that is safe on a 1 MB Wasm stack is a few hundred helpers, far below deployable contracts. It would also turn
  a failure into a refusal, not a fix.
- **Change the shape of the term.** `n` bindings visible to a body nest `n` deep in any lambda-calculus encoding,
  including UPLC's own, so the depth cannot be removed. Only its cost on the stack can.
- **Convert only the `let` spine.** The deep path runs through every kind of node: `let` bodies, `else` branches,
  `cons` arguments, and in UPLC, `Apply` and `Lam`. A walk that loops only on `let` still recurses on the rest.
- **Hand-written explicit stacks everywhere.** For a rewrite that threads scope and state, a hand-written stack
  machine is far from the recursion it replaces and easy to get subtly wrong. `Trampoline` keeps each walk's code
  close to its recursive form, and so close to what reviewers already know.

## Compatibility

- No compilation output changes (I2).
- `PirTerm` and `Term` gain `equals` overrides with the same results.
- `Trampoline` and `PirHelpers.traverseChildren` / `children` / `anyTerm` are new public API.
- Each converted walk allocates a few small objects per node, which is measured in Verification.

## Risks

- **A changed order.** A walk's steps could run in a different order than before. Mitigations:
  - the corpus replay (I2);
  - the existing goldens, byte snapshots and the ADR-060 byte checks;
  - per-walk tests comparing against the recursive reference on random terms.
- **A walk that still recurses.** A missed walk stays a limit. `DeepProgramCompileTest` covers the shapes the issue
  names and others; a new pass must follow D1.
- **Compile time.** Each converted walk now allocates on the heap. This is measured in Verification.

## Verification

(Filled in from the implementation.)

## Open questions

- A lint that rejects new direct recursion over `PirTerm` or `Term` in the passes.
- `ValueConverter` (deep result values in the Java VM) and `UplcParser`'s nesting limit.
