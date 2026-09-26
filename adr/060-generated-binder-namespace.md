# ADR-060: Generated binder names can never capture source names

**Status:** Proposed; implementation in progress
**Follows:** PR [#186](https://github.com/bloxbean/julc/pull/186)
(builder binders no longer capture user variables)

## Context and current behavior

UPLC resolves every variable to its nearest enclosing binder (de Bruijn indices).
PIR carries names, and `UplcGenerator` turns each name into the index of the
nearest binder with that name. Several lowerings wrap user code in binders
whose names they invent. When such a name is a legal Java identifier, a user
variable with the same name, referenced inside that scope, silently resolves to
the compiler's binder instead.

PR #186 fixed the list and map builders (`any`, `all`, `find`, `foldl`, `map`,
`filter`, `zip`, `get`, `contains`, map lookups, `Value.assetOf`) and a few
statement lets. It renames a builder binder only when a caller term in its
scope uses the same name. An inventory at the #186 head (`ef932b21`) found the
same class elsewhere. Every row below compiles today and gives a wrong result
without failing, unless noted.

| Site | Generated names | Trigger |
|---|---|---|
| Switch lowering (`UplcGenerator.generateDataMatch`) | `__match_data`, `__match_pair`, `__match_tag`, `__match_fields`, `__rest_N` | `BigInteger __match_tag = TEN;` read in a switch arm gives the constructor tag (0 instead of 10). A PV11 fallback keeps the legacy expansion when a dispatch mentions `__match_pair`, preserving the capture on purpose. |
| For-each and while lowering (`LoopDesugarer`) | `xs__`, `loop__forEach__N`, `loop__while__N` | A user `xs__` read in a loop body; an accumulator named `xs__`; a body local named `xs__` intercepting the break continuation. |
| Loop accumulators (`PirGenerator`) | `__acc_tuple`, `acc__forEach` | A user variable with that name read in a multi-accumulator loop, or in a loop with no accumulator (fails). |
| Validator wrapper (`ValidatorWrapper`) | `scriptContextData`, `ctxFields__`, `redeemer__`, `redeemer__decoded`, `datum__`, `datum__decoded`, `optDatum__`, `scriptInfo__`, `scriptInfoFields__`, `scriptInfoPair__`, `tag__`, `*__spend`, `redeemer__decoded__tagN` | A `@Param` with one of these names reads the script context, datum or purpose tag instead. |
| Parameter lambdas (`JulcCompiler`) | `<param>__raw`; `compileMethod` `<p>__raw`, `<p>__dec` | `@Param`s declared `cfg__raw` then `cfg`. |
| Unresolved member fallbacks (`PirGenerator`) | `.foo` pseudo-variables; `Var(foo)` for `x.foo(args)` | Resolve to the nearest binder or helper named `foo`. |
| Helper methods | bare method name, one namespace with variables | `var fee = fee(x); … fee(y)` calls the local (fails at run time); a field and a method with the same name cannot both be used. |

The same review found three adjacent miscompiles, where the compiler accepts
Java it does not implement:

| Construct | Current behavior | Java |
|---|---|---|
| Overloaded static methods | The last declaration wins at every call: `check(BigInteger)` vs `check(long)` returned `false`. | `true` |
| Multi-declarator declaration | `BigInteger a = ONE, b = TWO; a.add(b)` binds only `a`, so `b` read a static field: `11`. | `3` |
| Compound assignment in a loop | The operator is dropped: `count += 1` over three items gives `1`; `while (k < 3) { k += 1; }` never ends; `ok &= check(x)` keeps only the last check. | `3`, terminates, conjunction |

All results above were reproduced on the Java VM at BASELINE and PV11_SAFE at
`ef932b21`.

## Goals and non-goals

Goals:

- A source name can only ever resolve to the source declaration Java binds it
  to. This must hold by construction, not by the absence of an unlucky name.
- The rule is written down, enforced by tests, and applies to every future
  lowering.
- Constructs the compiler cannot lower faithfully are rejected with a
  diagnostic.
- Programs with no name coincidence keep byte-identical output.

Non-goals:

- Changing any other language semantics, lowering strategy or optimization.
- Making the name-counting PV11 passes scope-aware (see Risks).
- Supporting overloads, multi-declarators, `x++` or bitwise compound operators.

## Invariants

- **R1 Reserved namespace.** A *generated binder* is a binder whose name the
  compiler invents rather than copies from a source declaration. Every
  generated binder is named `"#" + <historical name>`, for example `#acc`,
  `#__match_tag`, `#xs__`, `#loop__forEach__0`, `#_`. The spelling is
  mechanical and one-to-one, so every pair of names that were distinct stays
  distinct and every pair that was equal stays equal. Existing names that
  already begin with `#` (`#if-join-N`, `#unit`, `#head_…`, `#tail_…`,
  `#array-N`, `#pair-first-N`, `#field-N`, `#fields-N`, `#value-N`) are
  unchanged. Names containing `#` are reserved for the compiler. `#` cannot
  occur in a Java identifier, and a leading `#` cannot equal a program-level
  definition name of the form `symbol#<hex>`.
- **R2 Construction-time hygiene.** Wherever caller, user or producer terms sit
  inside a generated binder, its name comes from
  `PirHelpers.hygienicName(base, avoid)`. `avoid` holds the free variables of
  those terms and the names of any caller-controlled binder placed between the
  generated binder and a generated reference to it. This keeps nested
  generated names apart and protects frontends that are not Java. The name is
  chosen when the lowering builds the term. There is no later renaming pass,
  because source maps and debug provenance key on PIR node identity
  (ADR-049, ADR-058).
- **R3 One source name, one declaration.** A binder that rebinds a user name
  (method parameters, locals, loop accumulators, `#if-join` parameters,
  `Let p = decode(p)`, self-aliases) keeps the exact source name, as the Java
  debugger requires. Block-local renames keep using `name'N`. Helper methods
  live in their own namespace, bound by qualified name as library methods
  already are. Call syntax resolves only against methods. Member access that
  cannot be resolved is a compile error. Overloads and multi-declarators are
  rejected.
- **R4 Frontends.** Code produced by another frontend must not bind or
  reference names containing `#`. The ADR-059 `PirVerifier` enforces this on
  producer definitions once that stack lands. It already rejects `#`. On
  `main` the backend receives a term that already links producer code with
  compiled Java library bodies, so it cannot tell them apart. R2 is the
  safety net until then.
- **R5 Determinism.** The same input produces the same names. No name depends
  on hash iteration order.

## Decision

1. Rename every generated binder according to R1, and apply R2 wherever
   foreign terms are in scope. `RECURSIVE_LIST_GET` stays one shared binding
   (`#go_get`, `#lst_get`, `#idx_get`), so ADR-043 promotion keeps recognising
   it. Remove the `__match_pair` direct-PIR fallback in switch lowering.
2. Lower `a op= b` as `a op b` for `+=`, `-=`, `*=`, `/=`, `%=` on integers,
   and `+=` on strings, with the operand order `target, value`. Reject
   bitwise, shift and boolean compound operators. Lowering `&=` or `|=` as
   `&&` or `||` would skip the right-hand side, which Java always evaluates,
   so a failing check could turn a rejection into an acceptance.
3. Reject overloaded methods and multi-declarator declarations.
4. Bind helper methods by qualified name, resolve calls against methods only,
   and turn unresolved member access into a compile error.
5. Guard the rule with three independent tests:
   - **G1 alpha-renaming oracle.** Renaming a user variable, parameter,
     method, field or `@Param` to any historical internal name must not
     change the output.
   - **G2 namespace check.** In tests, every PIR binder name must start
     with `#`, be a declared source identifier (optionally `'N`), or be a
     qualified method name.
   - **G3 source lint.** Every binder name the compiler constructs in
     `julc-compiler` and `julc-stdlib` must start with `#`.

## Alternatives rejected

- **Rename only on clash everywhere (the #186 approach).** Output stays
  dependent on the user's choice of names through the name-counting passes,
  and the namespace rule cannot be checked.
- **A global alpha-renaming pass after generation.** It replaces nodes and
  breaks node-identity source maps and debug provenance.
- **A `__` or `$` prefix.** Both are legal in Java. `__match_tag` is exactly
  this failure.
- **Shortened `#` names such as `#acc` for `acc_map`.** They merge names that
  are distinct today and can flip PV11 decisions without any user name
  involved.
- **Per-compilation counters for every name.** Heavy churn, and they break
  `RECURSIVE_LIST_GET` recognition.
- **Lowering `&=` and `|=` as short-circuit operators.** Unsound, as described
  above.

## Affected stages and modules

- `julc-compiler`: PIR generation (`PirGenerator`, `LoopBodyGenerator`,
  `LoopDesugarer`, `PirHofBuilders`, `PirHelpers`, `TypeMethodRegistry`,
  `PirSubstitution`), the validator wrapper and boundary code generation,
  `JulcCompiler` binding assembly, `SymbolTable`, `SubsetValidator`, and UPLC
  generation of switch dispatch.
- `julc-stdlib`: `StdlibRegistry` native-list builders.
- `julc-decompiler`: parameter-name reuse ignores names that are not Java
  identifiers.
- Docs, `AGENTS.md`, and ADR-038/041/043/044 notes.

## Compatibility and hash impact

UPLC FLAT stores de Bruijn indices, not names. A program in which no source
name equals an internal name produces identical bytes and hash at every
optimization level. This is checked against a 1,392-row snapshot captured at
`ef932b21` and against the external example validators.

Output can change once for these programs:

- Programs where a source name equalled an internal name. Some are
  miscompiles that are now fixed. Others were correct, but `LiteralFoldPass`,
  `ValueConversionSharingPass` and `ListIndexPromotionPass` count binders by
  name, so removing the coincidence can enable an optimization at PV11
  levels.
- Methods whose body used a builder binder with the method's own name, which
  were wrongly treated as recursive.
- Programs using compound assignment in loops. They are now correct.

Every changed artifact is listed with its reason in the PR and release notes.
Overloads, multi-declarators, bitwise compound operators and unresolved member
access now fail to compile.

PIR and UPLC text output now shows `#` names. This is cosmetic: compiled UPLC
text never parsed back, because variables print as `iN`.

## Risks

- The name-counting PV11 passes remain sensitive to coincidences between user
  names, and between user and library names. This preserves semantics and is
  only a conservative missed optimization. A later change could count by
  binder identity.
- Qualifying helper methods touches every call-resolution site. Qualified
  helpers must stay in the pre-loop variable snapshot, so the self-alias lets
  and name counts do not change.
- The ADR-059 stack is based on `c95ee610` and will conflict in the builders.
  Its `$julc$…` binders must move to `#julc-…`. Its `PirVerifier` must run
  only on producer definitions, never after linking, because linked Java
  library bodies contain `#` names.

## Milestones and stop gates

M0 baseline snapshot and exposure report; M1 this ADR; M2 compound
assignment, overload and multi-declarator rejection; M3 R1 and R2 at every
generated site; M4 method namespace; M5 guards; M6 documentation.

Stop and report if:

- a byte difference is not explained by the exposure report;
- an unresolved member access still compiles after M4;
- G2 finds a generated binder that does not start with `#`;
- a G1 target name has no rename that changes output at `ef932b21`;
- any golden `.flat.hex` or Blaster artifact hash changes.

## Verification

- The M0 snapshot (`optimization/adr060-pre-change-bytes.txt`), the golden
  FLAT files, every `*-pre-change-bytes.txt`, the native-constant regression
  and the Blaster artifact lock.
- External example validators compared at every level, with and without
  source maps.
- Accept and reject VM tests for every site at BASELINE and PV11_SAFE on the
  Java and Scalus VMs, each shown to fail at `ef932b21`.
- G1, G2 and G3 in the default test task.

## Open questions

- Whether the name-counting PV11 passes should count by binder identity, so
  their decisions no longer depend on any names.
- Whether `x++` and `x--` should be supported inside loops. They remain
  rejected with the existing diagnostic.
