# ADR-061: Control-flow correctness and semantic validation

Status: **Proposed — brainstorming draft; no implementation decision accepted**

Date: 2026-09-27

## Context and problem

JuLC should retain readable Java loops and conditionals without silently changing
the meaning of accepted programs. Recent reviews exposed two different problems:
generated names can capture source names, and assignments can fail to propagate
the values that later execution should observe. Fixing naming does not establish
correct state propagation.

[ADR-048](048-loop-conditional-local-updates.md) adds rejection rules for local
updates that existing lowering cannot preserve.
[ADR-050](050-if-loop-continuations.md) introduces shared continuations for a
particular interaction between branches and loops. These address real failures,
but correctness remains spread across several lowering paths and guards. Passing
their regression suites does not establish that all compositions are correct.

The discussion around [PR #186](https://github.com/bloxbean/julc/pull/186) and
[PR #199 / ADR-060](https://github.com/bloxbean/julc/pull/199) also exposed shared
field state being represented as lexical bindings local to an invocation.
Repeated execution then starts from the original binding instead of observing
the previous invocation's update.

This is about state **within one top-level evaluation**, not persistent Java
static state across transactions or separate evaluations.

### Concrete motivating cases

Historical review probes used a field initially zero and a helper that increments
it three times in a loop and returns its value:

| Execution within one evaluation | Java result | Observed JuLC result |
| --- | ---: | ---: |
| `h(3) + h(3)` | 9 | 6 |
| Sum three calls to `h(3)` in an enclosing loop | 18 | 9 |
| Update the field, then read it through self recursion | 3 | 0 |
| Update the field, then read it through mutual recursion | 3 | 0 |

These probes reproduced on PR #186 at `ef932b21` and on a review checkout of
PR #199 at `8433dbb1` with the proposed, uncommitted read-detection correction.
They are review evidence, not a claim that the cases are already permanent tests
on `main`. Preserve executable reproducers in the first implementation milestone.

A callback creates the same problem without a named helper:

```java
static long K = 0;

public static long m(long n) {
    JulcList<BigInteger> xs =
            JulcList.of(BigInteger.ONE, BigInteger.TWO);
    return xs.any(x -> {
        long i = 0;
        while (i < n) {
            K = K + 1;
            i = i + 1;
        }
        return K > n;
    }) ? 1 : 0;
}
```

For `n = 3`, Java returns 1: the first callback leaves `K = 3` and returns
false; the second leaves `K = 6` and returns true. The review probe returned 0
in JuLC. The enclosing method runs once, but its callback runs twice. Thus an
exception allowing mutation in an entrypoint that has no callers is insufficient
unless nested execution and effects are accounted for as well.

Traditional loops can expose the problem when they repeatedly call an updating
helper. This does not establish that every direct loop update is wrong. The
missing semantic obligation is the propagation of observable state across every
relevant execution boundary.

### Why previous tests missed these cases

Tests of one invocation, one loop, or one syntactic interaction do not cover
repeated calls, callbacks, recursion, and their combinations with shadowing and
branching. Existing UPLC optimizer properties compare UPLC before and after
optimization; both can agree on an already incorrect Java-to-PIR translation.
Input generators for a fixed contract also do not vary the source program.

This is a gap in the kinds of evidence collected, not evidence that a larger
number of similar example tests will solve the problem.

## Goals

- Define the supported source semantics and reject constructs outside them.
- Make binding, state flow, evaluation order, and abrupt control flow explicit.
- Find composition failures systematically, with small reproducible examples.
- Preserve typed values, deterministic compilation, and useful diagnostics.
- Build a tractable path to stronger assurance without claiming that testing
  proves correctness or that an experimental compiler is production-safe.

## Non-goals

- Full Java semantics, a heap, concurrency, or persistent mutable static fields.
- Removing readable local loops and conditionals from the language.
- Adding new loop syntax, recursion forms, or mutation operators implicitly.
- Replacing the shared PIR backend or proving the whole toolchain in one change.
- Treating contract-property verification as compiler semantic preservation.

## Proposed decision and unresolved choices

Adopt the following direction **if this ADR is accepted**. The choices below
remain proposals; this document does not change ADR-060 or authorize a compiler
rewrite.

| Area | Recommended direction | Decision still needed |
| --- | --- | --- |
| Mutable state | Immutable field/parameter inputs; mutation confined to owned local bindings | Exact subset and migration policy |
| Control flow | One typed, explicit representation before PIR | Block arguments/SSA versus structured continuations |
| Validation | Generate programs and inputs; compare against independent semantics | Initial grammar and oracle coverage |
| Formal assurance | Specify and validate a small lowering fragment first | Proof/translation-validation approach and implementation link |
| Rollout | Small milestones gated by semantic evidence | Scope and acceptance of each milestone |

### 1. Define a conservative mutation boundary

The recommended initial rule is:

- Fields and `@Param` values are immutable inputs after initialization/binding.
- Reassignment is permitted only for supported local bindings owned by the
  current method or lambda. A lambda can update its own locals.
- A lambda cannot mutate an enclosing binding. Helpers communicate changing
  values through arguments and results.
- The policy applies to every supported write form, not just loop assignments.
  Binding reassignment is distinct from mutation of an object or collection;
  supported library operations must have their effects specified separately.

Whether method-parameter reassignment belongs in the owned-local subset must be
specified explicitly. Do not infer support for arrays, element writes, instance
state, or other Java features from this rule.

This deliberately trades compatibility for a simpler state model. It would
reject some programs that happen to work today, including a field updated only
by a once-executed root method. It requires an accepted subset change, actionable
diagnostics, examples showing local-state replacements, and updated ADR-060 and
release wording. It must not be slipped into the naming fix as an incidental
refactor.

If mutable fields are a requirement, the coherent alternative is explicit store
passing through calls, returns, closures, recursion, and library boundaries.
That is a separate language and architecture decision, not another loop guard.

### 2. Resolve bindings once and model control flow explicitly

Introduce a private, typed Java-frontend representation before PIR, if a design
spike demonstrates that existing abstractions cannot express the required
invariants cleanly. Two candidate forms are typed blocks with arguments/SSA
versions and structured continuations with explicit state parameters.

Select one representation; do not maintain both as competing production paths.
Use stable declaration identities, distinct from display names and generated
binder names. Represent versions of an updated binding, lexical ownership,
control targets, and source provenance explicitly.

Branches pass the values required by their join. Loops pass values along the
backedge and each exit. A schematic example is:

```text
loop(total):
    if !condition(total): goto exit(total)
    if accept(total): goto join(total + 1)
    else:             goto join(total + 2)
join(next):
    goto loop(next)
exit(result):
    return result
```

Each condition is evaluated at its defined source position. Actual lowering must
also represent failure, short-circuiting, and supported abrupt exits. `break`,
`return`, and switch `yield` must identify their own targets; future constructs
remain unsupported until specified.

Lower this representation to existing PIR constructs where possible. Keep it
inside the Java frontend initially, respecting
[ADR-056](056-language-neutral-compiler-backend.md). A new public PIR construct
or backend contract requires its own justification and review.

Keep values typed across joins, including values without a generic Data encoding.
Do not solve accumulator transport by packing everything into untyped Data.
Share continuations rather than duplicating tails with exponential code growth.

### 3. Separate source effects from generated implementation details

Compute read/write and ownership facts using resolved source bindings or a
source-faithful typed representation. Reuse those facts for diagnostics and
lowering. PIR free variables answer a PIR closure question; synthetic bindings
can make them an inaccurate proxy for source-level field reads.

The review found a concrete example: a synthetic `let K = K` introduced around a
helper's loop can look like a field read even though its later source `K` is a
shadowing local. Exhaustively traversing every PIR binder does not remove this
semantic mismatch.

Preserve original source locations and choose diagnostics in a defined source
order. Identity-based lookup can be useful, but unordered map iteration must not
decide which error a user sees.

## Explicit compiler invariants

For every accepted construct, require:

1. **Binding:** every use resolves to its intended declaration and current value;
   generated names cannot capture source bindings.
2. **State flow:** every join, backedge, and exit receives the appropriate updated
   values. A block-local binding does not escape its scope or exist before its
   permitted initialization.
3. **Evaluation:** operands, conditions, arguments, and effects occur in the
   specified order and multiplicity; untaken branches are not evaluated.
4. **Control:** an abrupt exit reaches its intended target and does not execute
   the abandoned continuation.
5. **Effects:** accepted writes obey the selected ownership policy. Failure and
   observable trace order are preserved. Stateless helpers can still fail or
   trace; they cannot automatically be treated as freely reorderable expressions.
6. **Types and boundaries:** joins preserve type/representation agreement; ledger
   encoding and validator boundary interpretation remain unchanged.
7. **Determinism:** fixed input/configuration yields deterministic binders,
   diagnostics, and artifacts, independently of incidental collection ordering.
8. **Provenance:** transformations preserve or explicitly invalidate debug/source
   associations according to [ADR-058](058-playground-java-source-debugger.md).
   Stale or guessed source mappings are not acceptable.

IR validation should check what is structurally decidable: binding closure,
edge arity and types, definition availability, target validity, and effect-policy
violations. Passing a verifier is necessary evidence, not a proof of equivalence.

## Verification strategy

### Independent differential program testing

Build a small typed program generator using the existing jqwik infrastructure.
Generate an AST and its inputs, then print source; avoid mostly-invalid random
Java text. Begin with booleans, bounded integer examples or `BigInteger`, local
assignments, branches, and bounded loops. Extend deliberately to helpers,
supported callbacks, switches, and typed values.

For the Java/JuLC semantic intersection, execute javac/JVM code as an independent
oracle and compare with generated UPLC execution. Reset field fixtures only
between top-level evaluations, never between calls inside one evaluation.
Audit any JVM library adapters used by the oracle; a placeholder off-chain
implementation is not a reference semantics.

Document dialect differences, including numeric range/overflow and boundary
failure behavior. Restrict generated inputs to the agreed intersection or use
a separately specified reference interpreter for those features. Do not hide
differences by normalizing away observable errors. An interpreter must not reuse
the lowering algorithm it is supposed to check.

Maintain two separate properties:

- Programs from the supported grammar **must compile and agree** with the oracle.
- Programs intentionally outside the subset **must reject** with the expected
  diagnostic category and location.

Allowing either rejection or agreement for all generated programs would let a
compiler that rejects everything pass.

Compare returns and encodings, success/failure categories, and relevant traces
including the prefix before failure. Adapt method-return and validator-abort
boundaries explicitly. Exercise supported optimization modes and VM backends;
backend agreement alone does not validate the frontend.

Bound termination by construction: protect loop counters and bound nested work.
Track timeouts and budget exhaustion as inconclusive, not passing equivalence
checks. Require a minimum of conclusive cases for each coverage category and
report discard rates. Compiler crashes and hangs are independent failures.

### Composition coverage and shrinking

Bias generation toward semantic interactions, with explicit coverage quotas:

| Dimension | Required examples |
| --- | --- |
| Iterations/calls | Zero, one, multiple; helper twice and inside a loop |
| Updated bindings | Zero, one, several; update read immediately or after a join |
| Nesting | If in loop, loop in if, loop in loop, supported switch combinations |
| Binding | Shadowing before/after blocks; helper/lambda/iteration-local names |
| Exits | Each supported exit target; early versus final iteration |
| Branches/effects | Taken/untaken arms; short-circuiting; failure and trace order |
| Functions | Arguments/results; repeated callbacks with owned local updates |
| Boundaries | Empty/singleton/multiple collections; zero/negative values where valid |

Recursion and shared-field probes remain regressions with outcomes determined
by the accepted subset: semantic agreement where supported, rejection otherwise.

Shrink the AST and inputs while preserving typing, scope, termination, and the
conditions that expose the failure. Save the minimized source, input, expected
and actual observations, generator seed/version, compiler revision, target,
optimization/debug configuration, VM/library versions, and artifact hashes.
Promote each minimized failure to a readable permanent regression test.

### Complementary methods

- Exhaustively enumerate a tiny bounded grammar to cover all small combinations.
- Use metamorphic tests with checked preconditions: alpha-renaming, redundant
  blocks, and extracting a stateless expression with explicit inputs/outputs.
  Loop rewrites are valid only when evaluation order and exit semantics agree.
- Mutate compiler transformations intentionally: drop a join argument, use a
  stale accumulator, swap evaluation order, or target the wrong exit. Verify
  that the semantic suite detects these faults.
- Add coverage-guided fuzzing after a meaningful structured grammar and oracle
  exist. Coverage complements semantic interaction quotas; it does not replace them.
- Measure script size and execution budgets separately from semantic equality.
  Equal results do not imply acceptable costs or byte-identical scripts.

Existing unit, stdlib, testkit, annotation-processor, and integration suites remain
necessary. Generated testing supplements them rather than replacing readable
examples and explicit invalid-program tests.

### Formal methods: a scoped path

Specify a small local-state imperative fragment and the functional representation
to which it lowers. State preservation of returned values, traces, failures, and
control behavior, with an explicit termination/divergence model. Prove or validate
the individual translations before widening the fragment.

Options include a verified translator, a checked translation certificate, or
translation validation of each emitted result. Evaluate these on one concrete
loop-and-join lowering before choosing a framework. Bounded checking proves only
the checked bound; arbitrary loops require a suitable simulation argument or
invariants. Equal step counts/fuel across representations are not a valid general
equivalence criterion.

A proof of a separate model is not a proof of the Java implementation. Document
how the implementation is connected to the model and identify trusted components:
parser/name resolution, library semantics, lowerings outside the proof, checker,
serialization, VM, and boundary adapters. Existing exact-artifact contract-property
verification answers a different question from preservation of Java source meaning.

## Alternatives and tradeoffs

| Alternative | Assessment for discussion |
| --- | --- |
| Keep adding syntax-specific guards | Useful for urgent containment; not the proposed long-term architecture because compositions keep reopening obligations |
| Permit field updates only in once-executed roots | Preserves some examples, but requires sound callback/call/effect reasoning; does not solve local join correctness |
| Explicitly thread a shared field store | Coherent semantics, but adds closure, recursion, library, representation, and cost complexity; consider only if shared mutation is required |
| Reject all loops | Avoids useful syntax without addressing all binding, branch, and call errors; not recommended |
| Rewrite the whole compiler into SSA immediately | Large simultaneous change with no independent evidence baseline; not recommended |
| Add only more input properties | Misses source-program diversity; retain alongside program generation |
| Prove the entire toolchain first | Too broad for a first milestone; prefer a clearly bounded fragment and explicit trust boundary |

These are proposed dispositions, not accepted permanent rejections. Record the
final reasoning when the ADR is accepted so later work does not reintroduce a
discarded approach without addressing its limitations.

## Affected stages and modules

- `julc-compiler`: source validation/resolution, effect facts, control-flow
  generation, PIR lowering, diagnostics, provenance, and semantic test support.
- `julc-core` and the shared backend: initially consumers of existing terms;
  changes only if a separately justified representation gap is found.
- VM modules: execution oracles and differential coverage; no semantic VM change
  is implied by this proposal.
- `julc-stdlib`, `julc-testkit`, `julc-testkit-jqwik`, and annotation processing:
  library/oracle coverage and public compilation-path regressions.
- Debugging, documentation, and examples: preserve source associations and explain
  any accepted subset restrictions. Inspect sibling examples before migration.
- `julc-verification` and verification tooling: consult for proof integration;
  no expansion of their existing assurance claims by implication.

Start generator support in compiler tests. Extract a shared public abstraction
only after concrete consumers justify it.

## Compatibility and risks

Stricter mutation rules can reject previously accepted contracts. A control-flow
rewrite can change script bytes/hashes and execution costs even when behavior is
preserved. Neither effect is a patch-level implementation detail to conceal.
Publish migration examples and distinguish unchanged behavior from unchanged
artifacts. Existing deployed scripts do not change; recompilation can produce a
different script identity.

Other risks include incorrect oracle adapters, a generator that never exercises
its intended interactions, common bugs shared by interpreter and lowering,
excessive test runtime, and a temporary split between legacy and migrated paths.
Assign each migrated construct one production lowering path and retire obsolete
special cases only after its acceptance gate passes.

## Implementation milestones and acceptance gates

1. **Semantics and containment.** Preserve the known reproducers, inventory write
   forms and current restrictions, agree on the mutation policy, and update the
   governing ADRs. Urgent diagnostics can proceed independently with explicit
   scope; this design work must not delay a justified silent-miscompile fix.
2. **Independent evidence first.** Build the smallest typed source generator and
   oracle. Demonstrate rediscovery of representative historical failures on
   pinned revisions, then detect targeted compiler mutations. Establish fixed
   seed PR runs and larger scheduled campaigns with conclusive coverage quotas.
3. **Representation spike.** Implement one loop/branch fragment in the selected
   representation, its verifier, and an independent interpreter where useful.
   Review type, provenance, code-size, and cost behavior before wider migration.
4. **Incremental migration.** Migrate one coherent construct family at a time.
   Require oracle agreement, invalid-program diagnostics, focused/affected-module
   suites, relevant integration tests, deterministic outputs, and artifact/cost
   review across supported modes. Run repository-wide validation for cross-module
   changes; check external examples when public behavior changes.
5. **Scoped formal assurance.** Establish a preservation result or translation
   checker for the agreed fragment, including its implementation connection and
   limitations. Expand only after reviewing the trust boundary.

Do not expand the mutable subset merely to accommodate an unmodeled edge case.
No milestone is complete solely because its tests pass; review its invariants,
oracle coverage, and consistency with the accepted ADR.

## Open questions for brainstorming

1. Are mutable fields a real contract-author requirement, or can immutable inputs
   plus local state cover the intended programming model?
2. Should method parameters be assignable local bindings? What precise ownership
   and initialization rules should users see?
3. Which existing restrictions must remain until the new representation supports
   them, and how should compatibility breaks be communicated?
4. Do typed block arguments or structured continuations integrate better with
   PIR, debugging, native types, and acceptable script costs?
5. What is the smallest useful generated grammar, and which library behaviors
   have a trustworthy JVM oracle today?
6. Which semantic interaction quotas and runtime budgets belong in PR CI versus
   scheduled runs? How do we keep inconclusive results visible?
7. Is a verified translator, certificate checker, or translation validator the
   most practical first formal experiment for the actual implementation?
8. Which acceptance gates must pass before removing each legacy lowering path?

## Research references

These are sources of techniques, not drop-in JuLC correctness solutions:

- [Csmith](https://github.com/csmith-project/csmith): generation of valid programs
  for differential compiler testing; JuLC needs its own semantic subset.
- [jqwik](https://jqwik.net/docs/current/user-guide): structured generation,
  shrinking, reproducible properties, and coverage statistics.
- [Equivalence Modulo Inputs](https://people.inf.ethz.ch/suz/emi/index.html):
  testing with transformations whose equivalence is constrained to tested inputs.
- [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer): coverage-guided JVM
  fuzzing, potentially useful around a structured generator or compiler entrypoint.
- [CompCert](https://compcert.org/man/manual001.html): semantic preservation and
  explicit boundaries of a verified compiler's guarantees.
- [Alive2](https://github.com/AliveToolkit/alive2): translation validation in the
  LLVM setting; applying the idea to JuLC requires its own semantics and checker.
