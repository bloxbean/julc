# ADR-008: Milestone C.3 — Productive Recursive ADTs

- **Status:** Implemented on feature branch; pending manual review
- **Date:** 2026-08-12
- **Feature branch:** `feat/verification-c3-productive-recursion`
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-004 — Milestone C](004-milestone-c-reusable-verification-integration.md),
  [ADR-006 — Lean Containers and Optional Values](006-milestone-c2-lean-containers-and-optionals.md)

## Context

Milestone C.2 generates strict Lean types and codecs for arbitrary
**nonrecursive** combinations of records, variants, booleans, optionals, lists,
and maps. Both the JuLC compiler and `julc verify init` currently reject a
recursive datum or redeemer.

A useful recursive Java type must have a finite constructor path:

```java
sealed interface Node permits End, Cons {}
record End() implements Node {}
record Cons(BigInteger value, Node next) implements Node {}
```

The following strict record has no finite inhabitant and is not a supported
recursive type:

```java
record Node(BigInteger value, Node next) {}
```

The limitation starts before CIP-57 or Lean generation. `TypeRegistrar`
topologically sorts Java record and sealed-interface declarations and rejects
cycles. `PirType.RecordType` and `PirType.SumType` contain their children by
value, so they cannot faithfully represent a finite nominal graph containing a
back-reference. C.3 therefore requires a small compiler type-model extension;
changing only `VerificationProjectGenerator` would not make recursive JuLC
contracts compilable.

Recursive verification also has two different meanings that must not be
blurred:

- Blaster can analyze the exact UPLC over an explicitly bounded recursive
  domain or unfolding depth; this is bounded artifact evidence.
- Lean can prove a general theorem by induction over the generated recursive
  type; this can be unbounded and kernel-checked, but it is not automatically a
  theorem about the exact UPLC unless the required bridge is also proved.

Increasing CEK preprocessing fuel is neither of those things and must not be
presented as recursive proof depth.

## Decision

JuLC will support productive recursive datum/redeemer ADTs through nominal type
references, strongly connected dependency groups, strict total Lean codecs,
and an explicit recursive verification bound.

C.3 will be delivered in four ordered slices:

1. compiler nominal recursion and productivity diagnostics;
2. faithful recursive CIP-57 definitions;
3. recursive Lean declarations and codecs; and
4. bounded artifact evidence plus a separate unbounded induction example.

All four slices are required for the C.3 exit criterion. Partial work must
continue to fail closed in `julc verify init`.

## Supported initial forms

The initial supported surface is:

- self-recursive sealed sums with at least one finite constructor;
- mutually recursive named records/sums whose dependency group is productive;
- recursion through record and constructor fields;
- recursion nested under `Optional`, `List`, and `Map`; and
- arbitrary combinations of the C.2 primitive and container types inside the
  recursive group.

The initial surface excludes:

- generic user-defined recursive types;
- recursive functions as schema fields;
- arrays, pairs, functions, or opaque `Data` at the contract boundary;
- coinductive or intentionally infinite values;
- Java `null` as a recursion terminator; and
- any claim that bounded symbolic exploration establishes an unbounded
  theorem.

## Compiler type-model design

### Nominal references

Add a nominal reference form to compiler-owned type metadata, conceptually:

```text
NamedTypeRef(stableId, displayName, RECORD | SUM)
```

`stableId` is an unambiguous compiler identity, normally the fully qualified
Java name. `displayName` is used only for diagnostics and generated Lean names.
Recursive fields contain `NamedTypeRef` instead of embedding an infinitely
expanded `RecordType` or `SumType`.

The compiler retains a registry from stable ID to the completed named
definition. Consumers must resolve a nominal reference deliberately when they
need fields or constructors. Lowering continues to treat records, sums, and
nominal references as on-chain `Data`; a nominal reference does not introduce
a new UPLC representation.

`ContractSchema` carries a compile-scoped snapshot of completed named
definitions alongside the boundary arguments. The snapshot may include
definitions that are not reachable from this validator; schema generation
follows references on demand and emits only reachable definitions. This keeps
schema generation compiler-owned and avoids reparsing Java or guessing
recursive definitions from names.

### Registration by strongly connected component

Replace the current cycle-rejecting topological registration with:

1. collect every record and sealed-interface declaration and dependency;
2. compute strongly connected components (SCCs);
3. topologically order the SCC condensation graph;
4. predeclare stable nominal identities for every member of one SCC;
5. resolve fields, variants, and containers, using nominal references for
   declarations in the active SCC; and
6. validate the completed group before exposing a successful compilation or
   contract schema; a failed compilation discards its compile-scoped resolver.

Nonrecursive SCCs retain existing behavior. Ambiguous imports, duplicate
types, and unresolved names remain compiler errors with Java source locations.

### Productivity

JuLC will compute the least fixed point of finitely constructible named types.
A constructor is productive when each of its strict named fields is already
productive. A sum is productive when at least one constructor is productive;
a record has its single record constructor.

Primitive fields are productive. `Optional`, `List`, and `Map` provide finite
empty constructors, but references nested inside them remain part of the
recursive dependency graph for codec generation and positivity checking.

Every reachable recursive SCC must contain a finite construction path. A
strict cycle without one fails compilation at the involved Java declarations,
with a diagnostic naming the cycle. This includes direct and mutual
nonproductive cycles.

The validator must also reject any recursive occurrence that Lean cannot
accept as strictly positive. All C.2 containers are positive; future function
or opaque wrapper forms are not implicitly accepted.

## CIP-57 generation

Recursive named fields are emitted as ordinary JSON Pointer references:

```json
{
  "Node": {
    "anyOf": [
      {"title": "End", "dataType": "constructor", "index": 0,
       "fields": []},
      {"title": "Cons", "dataType": "constructor", "index": 1,
       "fields": [
         {"title": "value", "dataType": "integer"},
         {"title": "next", "$ref": "#/definitions/Node"}
       ]}
    ]
  }
}
```

`SchemaGenerator` must track definitions by stable nominal identity, allocate a
deterministic CIP-57 key, insert an in-progress marker before walking fields,
and emit a `$ref` when it encounters the same definition again. Collision and
JSON Pointer escaping rules from C.1/C.2 remain fail closed.

Blueprint validation must accept the recursive reference graph while still
rejecting dangling references and malformed constructor schemas.

## Lean generation

### Declaration groups

The generator computes SCCs from the selected validator's reachable CIP-57
definitions. Acyclic definitions keep their C.2 output. Recursive SCCs become
one deterministic Lean mutual-inductive declaration group. Constructor tags,
field order, and nested container shape come only from CIP-57.

Generated names retain all C.2 normalization and reserved-name checks. A name
collision aborts generation before publishing a workspace.

### Total codecs

Generated `toData` and `fromData` implementations must be total:

- `toData` is structurally recursive over the generated ADT;
- `fromData` uses an explicit decreasing measure or a generated fuel derived
  from the finite input `Data` tree;
- mutual recursion and recursion inside list/map entries use generated helper
  groups with a termination argument accepted by Lean;
- constructor index and arity checks remain exact; and
- a wrong outer constructor, malformed recursive child, bad nested item, key,
  or value returns `none` for the whole value.

`partial`, `unsafe`, `axiom`, project-owned `sorry`, and silently truncated
decoding are forbidden in generated codec code.

The evidence suite must prove or definitionally check, for representative
values:

```text
fromData (toData value) = some value
```

It must also check malformed recursive data and preservation of list/map order
and duplicate map keys inside recursive nodes.

## Recursive verification depth

Add a setting distinct from CEK fuel:

```text
julc verify init ... --recursive-depth 4
```

The positive integer is stored in the verification manifest and used only by
generated bounded recursive-domain predicates, witnesses, and artifact
experiments. Exhausting this bound is `COULD-NOT-EVALUATE` or a clearly labeled
out-of-scope witness; it is never validator failure and never a proof.

The existing `--fuel` continues to control UPLC preprocessing/evaluation steps.
The generated README and manifest must display both values and their different
meanings.

Unbounded recursive claims require an explicit Lean induction theorem. The
evidence suite will include at least one kernel-checked composition lemma, such
as decoding after encoding for the representative recursive ADT. That result
must be reported separately from any `SMT-VALID` exact-artifact property.

## Failure and result classification

Generation fails closed for:

- an unproductive recursive SCC;
- a recursive occurrence in an unsupported or non-positive position;
- an unknown or ambiguous nominal reference;
- a malformed or dangling recursive CIP-57 reference;
- a Lean name collision inside a mutual group;
- inability to generate a total codec; or
- a nonpositive recursive-depth setting.

Expected result labels remain:

- `KERNEL-PROVED` for a completed Lean induction theorem with no
  project-specific admissions;
- `SMT-VALID` for a Blaster/Z3 property within its declared domain and trust
  model;
- `REFUTED`/`FALSIFIED` when a concrete counterexample is found; and
- `COULD-NOT-EVALUATE` for depth, CEK fuel, unsupported semantics, or solver
  limits.

## Implementation plan

### C.3.1 — Compiler and blueprint graph

- add nominal named references and a completed-definition registry;
- register declaration SCCs with forward identities;
- diagnose productive and nonproductive cycles at Java source locations;
- include the completed compile-scoped named-definition snapshot in
  `ContractSchema`;
- emit deterministic recursive CIP-57 `$ref` graphs; and
- add compiler/blueprint tests for direct, container-nested, and mutual
  recursion.

### C.3.2 — Lean recursive schema generator

- replace the current blanket recursion rejection with SCC classification;
- generate recursive and mutual Lean inductive groups;
- generate total strict encoders/decoders and `IsData` instances;
- retain C.2 wrappers and duplicate-map semantics; and
- reject malformed, nonproductive, unsupported, or colliding graphs before
  atomically publishing a workspace.

### C.3.3 — Evidence and reporting

- add `--recursive-depth` and manifest metadata;
- create real spending and minting recursive Java fixtures;
- compile generated workspaces against the pinned Blaster stack;
- add round trips, malformed data, depth exhaustion, and a vulnerable negative
  control;
- add at least one unbounded kernel-checked induction lemma; and
- document exactly which evidence is bounded versus unbounded.

## Required tests

At minimum:

1. `End | Cons(Integer, Node)` compiles, emits a self `$ref`, and generates a
   strict Lean codec.
2. A recursive field under `Optional`, `List`, and `Map` is represented without
   flattening or truncation.
3. A productive mutually recursive pair compiles and generates one mutual
   declaration/codec group.
4. `record Bad(Bad next)` and a mutual strict cycle fail at Java source
   locations.
5. Wrong recursive tags, arities, primitive fields, and nested children decode
   to `none`.
6. Deep valid values round-trip, and duplicate map entries remain ordered and
   duplicated.
7. Recursive-depth exhaustion is not reported as validator rejection or proof.
8. The generated code contains no project-owned `sorry`, `axiom`, `unsafe`, or
   `partial` declaration.
9. A representative induction lemma is accepted by the Lean kernel.
10. Existing C.1/C.2 and Milestone A/B evidence remains established.

## Compiler regression guard

C.3 necessarily changes compiler type resolution, but it must not change the
runtime representation or lowering of existing programs.

The implementation must capture a corpus of nonrecursive validators before the
type-model change and compare after the change:

- final UPLC text or canonical AST;
- double-CBOR `compiledCode`;
- Cardano script hash;
- blueprint schemas; and
- compiler diagnostics for existing invalid programs.

Any existing-contract UPLC or script-hash difference blocks the milestone
unless separately explained, reviewed, and approved. Recursive contracts add
new supported inputs; they do not authorize unrelated optimizer or lowering
changes.

## Documentation deliverable

Update `verification/README.md` and add `verification/c3/README.md` with:

- prerequisites and one reproduction command;
- supported Java recursive forms;
- nonproductive examples and expected diagnostics;
- the difference between `--fuel` and `--recursive-depth`;
- bounded versus unbounded claim language; and
- the exact tests and expected terminal result.

Generated workspaces and build directories remain reproducible ignored output.
Committed Lean evidence, fixtures, and scripts must be sufficient to reproduce
the result.

## Exit criteria

Milestone C.3 is complete only when:

- productive self and mutual recursive JuLC boundary ADTs compile;
- nonproductive cycles fail with source diagnostics;
- exact recursive CIP-57 and total Lean codecs are generated without
  handwritten schema code;
- spending and minting fixtures compile against pinned Blaster dependencies;
- bounded depth is explicit and cannot be confused with an unbounded proof;
- at least one recursive theorem is kernel-checked by induction;
- existing nonrecursive script bytes remain unchanged; and
- the full Gradle, C.2, and Milestone A/B verification suites pass.

## Consequences

C.3 makes recursive contract data available to both JuLC and the generated
verification workspace. It also introduces nominal references into compiler
metadata and therefore has a larger regression surface than C.2.

The explicit type graph and productivity analysis are useful beyond Lean:
blueprint generation, diagnostics, future annotation field paths, and
certificate rendering can all consume the same compiler-owned model. The cost
is that every consumer must handle nominal references deliberately rather than
assuming the type tree is acyclic.

## Implementation evidence

The feature branch implements the four slices above. The compiler represents
back-references nominally, registers recursive SCCs, rejects nonproductive
cycles at Java source locations, and publishes recursive CIP-57 `$ref` graphs.
`julc verify init` generates single and mutual Lean inductives with total
depth-indexed decoders and a finite-`Data`-derived `IsData` decoder depth.

`verification/c3/scripts/verify.sh` rebuilds spending and minting fixtures,
regenerates both workspaces with `--recursive-depth 4`, verifies dependency
pins and artifact hashes, compiles the generated support, and checks the
committed `CodecTests.lean`. That file includes strict malformed and explicit
depth-exhaustion checks, recursive optional/list/map round trips, duplicate-map
preservation, mutual recursion, and a kernel-checked unbounded
`decodeChain (chainDecodeDepth value) (encodeChain value) = some value` theorem
and the actual generated-instance theorem
`IsData.fromData (IsData.toData value) = some value`, both proved by induction.
A deliberately permissive decoder is the codec-level negative control; it
accepts malformed data that the generated strict decoder rejects.
Contract-property negative controls remain mandatory when a developer
specializes the generated `SecurityProperty.lean`.

The compiler regression suite also evaluates a recursive minting redeemer in
the JuLC VM. A two-node value is accepted and a one-node value is rejected by
validator logic that switches through the nominal recursive field, exercising
the lowering path rather than merely asserting that a program was produced.

Existing compiler golden tests remain the regression guard for nonrecursive
UPLC text/bytes and script hashes. This implementation adds support for new
recursive inputs without changing their existing on-chain `Data`
representation.
