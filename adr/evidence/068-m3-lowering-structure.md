# ADR-068 M3 and M4: how the javac lowering is structured

This is the plan for the javac lowering (ADR-068 D2), from the design review of M2. `PirGenerator` is too large for a
person to follow, so the port does not copy its shape. Correctness comes first: it ports the reference's behaviour and
bytes, and a structural change must not move either unless the ledger records why.

**Why not `PirGenerator`'s shape.** It has 3,654 lines, 11 mutable ambient fields read at a distance (`forEachAccumulatorVar`, `breakContinueFn`, `multiAccVars`, `hofUnwrappedVars`, `patternScopes`, `guardDepth`, `pendingCompareTo`, and others), 95 `symbolTable` sites, rejections mixed into lowering (57 throws, 14 `collectError`), and AST renaming for hygiene.

**Pattern: a mediator over focused construct families, with three rules.**
1. **Resolve, then lower.** A call becomes a sealed `CallTarget` by a pure function, then a family lowers that target.
2. **One immutable `Frame`** replaces the ambient fields, so no state leaks on an exception.
3. **One builder** for anything a source name touches: `Terms`.

**Packages:**

| Unit | Responsibility |
|---|---|
| `frontend.javac` (exists) | Reading: frontend, facts, registrar, program |
| `frontend.javac.lower` | Lowering. Uses only `JavacFacts`' public API, so `Trees` and `JavacTask` are unreachable |
| `JavacCompilation` | Coordinator: finds entrypoints, `@Param`, statics and libraries; produces the assembly input. No tree walking |
| `Lowering` | Mediator: holds services and the single dispatch |
| `Frame`, `Scope`, `Binder` | `Frame(Scope scope, Exit exit, LoopState loop, boolean inGuard, TypeVariables variables)`. `Scope` is a parent-linked immutable chain, Element to `Binder` (name, type, field projections, decoded flag) |
| `Terms` | The only code that builds a `Let`, `Lam` or `Var` for a source element; records positions and the I13 side table |
| `Literals`, `Names`, `Operators`, `Records`, `TypeTests`, `Lambdas`, `Switches`, `GuardConstants` | Expression families, target about 100 to 500 lines each |
| `Calls`, `CallTarget`, `CallTargets` | Classification and call lowering |
| `Statements`, `Ifs`, `Exits`, then `Loops` (M4) | Continuation-style statements |
| `frontend.javac.subset` | I2c rules (`EqualityRules`, `SwitchRules`, ...). Lowering assumes valid input (I12) |
| `codegen.ValidatorAssembly` | Neutral: statics, helpers, library lets, wrapper, `@Param` lambdas, backend |

```java
PirTerm expr(ExpressionTree tree, Frame f) {
    return switch (tree) {
        case LiteralTree t       -> literals.literal(t);
        case IdentifierTree t    -> names.identifier(t, f);
        case ParenthesizedTree t -> expr(t.getExpression(), f);
        case BinaryTree t        -> terms.at(t, operators.binary(t, f));
        case MethodInvocationTree t -> terms.at(t, calls.call(t, f));
        // ...
        default -> throw notPorted(tree); // JULC0084 until M5, then an internal error (I12)
    };
}
```

**Continuation-style statements.**
- `Supplier<PirTerm> cont` is vestigial: its only producer is `() -> branchContinuation` (`PirGenerator:615`). Use a plain term built once in the enclosing scope, reused by reference.
- Keep the reference's three `if` shapes as named methods (`ifWithExit`, `ifWithLoop` for the ADR-050 join, `ifForEffect`), so each is reviewed against its source.
- `withLocalsRenamed` disappears. Bare blocks splice their statements.
- M4 can drop most of `AccumulatorTypeAnalyzer`: types come from javac, and accumulators become a `Set<Element>`.

**I13.**
- `Scope.bind(element)` chooses the name when the binder is built: one name per element in a per-method table, `'k` on a visible clash.
- Seed `Scope` with statics, `@Param` and method binders before lowering any body, because their `Let`s are built later but are visible.
- An eagerly built continuation is safe. Its free variables are visible at any branch-local binder, so that binder takes `'k`. A lazily rebuilt continuation would break the "one name" rule, so do not rebuild it.
- `Terms` records `IdentityHashMap<PirTerm, Element>` for the binding check.

**Evaluation order.** Each family's Javadoc states its order. Separate generation order, which affects diagnostics, from evaluation order, which affects semantics. `Operators` holds D6's minimal binding in one place.

**Source maps.** `terms.at(tree, term)` is the only recorder, called at the reference's sites and not centrally, to avoid provenance noise. No post-pass and no renaming.

**Calls.**
- `CallTargets.classify` covers `Intrinsic`, `Stdlib`, `Library`, `Local`, `Accessor`, `TypeMethod(key)`, `Hof`.
- Dispatch `TypeMethodRegistry` through an exact-key entry from a small owner-FQCN to type-key map. Never use `dispatch`'s `rt.name() + "." + method` guess, which is why a user `Value` reaches ledger handlers.
- `equals` on `byte[]` or an interface resolves to `Object.equals`, so classification must also read the receiver's static type.
- `Library` needs no retry loop: signatures come from elements up front.

**Shared until M6:**
- Unchanged, JavaParser-free: `TypeMethodRegistry` (plus the exact-key entry), `StdlibLookup`, `LibraryMethodRegistry`, `PirHelpers`, `PirHofBuilders`, `PirSubstitution`, `LoopDesugarer`, `PatternMatchDesugarer`, `ValidatorWrapper`, `StrictBoundaryGenerator`, `StrictRecordEntrypoint`, `PirBackend`, the diagnostics classes.
- Moved mechanically: the `TypeRegistry` split, the neutral algorithms, assembly, and `swappedInOrder`/`isTrivialOperand`.
- Not shared: `SymbolTable`, `TypeInferenceHelper`, `AccumulatorTypeAnalyzer`.
- Ported: `JavaConstants`, the rule parts of `SwitchPatternChecker`, `SubsetValidator`, and the scan side of `DebugMetadataCollector` (split it; `Terms` is its hook).

**Order of work:** a preparation step (assembly, lints; M2's review fixes are done), then a walking skeleton (`return true`) through the I1 tools, then the ADR's construct order. Add two lints in the style of `GeneratedBinderNameLintTest`: no `Trees` or `JavacTask` import in `lower/`, and no source-binder `new PirTerm.Var/Let/Lam` outside `Terms`.

**What could go wrong:**
- **Byte parity.** Cleaner structure tempts "improvements". Port the shape first, including the `#_` and `#_if` lets; every deviation is a D13 row.
- **Rejection order.** Moving rejections into `subset/` can change which error comes first in a multi-error source, which I2a does not allow without a named decision. Preserve the reference's order, or leave those checks in place.
- **javac tree shapes (verified by probe):**
  - `if`, `while` and `switch` conditions are `PARENTHESIZED`, so a port of `getCondition() instanceof InstanceOfExpr` silently takes the wrong path. Strip exactly one level, because D6 must not unwrap user parentheses.
  - `int a = 1, b = 2;` is two `VariableTree`s with the same start position.
  - `- 5` is folded to literal `-5`.
  - Synthesized `<init>` is prepended to `getMembers()`.
- **Fragmentation.** Mutual recursion through a mediator means more jumps; the one-page dispatch and named families are the mitigation.
- **Coverage gaps.** A gap in `CallTargets` fails closed with `JULC0084`, which M5's zero-count gate exposes. An owner-to-key map cannot express an overload with different lowering; add an explicit entry there.
- **The `TypeRegistry` split** touches the frozen frontend; the gate-off baseline must stay identical.
- **ADR-061** may replace `Statements` and `Loops`. That boundary is deliberate, since expressions stay.
