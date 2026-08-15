# Milestone C.3 productive-recursion evidence

This suite exercises JuLC's generated recursive CIP-57 and Lean codecs against
the pinned Blaster stack.

It currently covers:

- productive `End | Cons` recursion used as a spending datum;
- optional, list, and map guarded self-recursion;
- productive mutual recursion;
- strict rejection of malformed recursive `Data`;
- a deliberately permissive codec negative control that accepts malformed
  `Data`, paired with the generated strict decoder rejecting the same input;
- ordered duplicate-map preservation inside a recursive value;
- explicit recursive-decoder depth exhaustion;
- concrete codec round trips; and
- unbounded, kernel-checked `decode(encode(value))` theorems proved by
  structural induction, including the actual generated `IsData` instance path.

Run from the repository root:

```bash
verification/c3/scripts/verify.sh
```

The generated manifest records both CEK `fuel` and `recursiveDepth`. They are
different limits. A bounded Blaster experiment is not an unbounded theorem.
The unspecialized scaffold compilation is pinned to CEK preprocessing fuel
`1000`; increasing that bound does not strengthen the codec induction theorem
and can make recursive guarded artifacts expensive to preprocess.
`CodecTests.lean` contains the separate induction example; it does not by
itself prove a contract-specific security property of the imported UPLC.

Supported Java recursion must be productive: a sealed sum such as
`End | Cons(Integer, Node)` has an explicit finite constructor. Records such as
`Tree(List<Tree>)` and `Graph(Map<BigInteger, Graph>)` are productive because
the container may be empty. A strict `record Bad(Bad next)` cycle is rejected
at its Java source location with a `no finite base constructor` diagnostic.

The suite's expected final line is:

```text
ESTABLISHED: Milestone C.3 recursive schemas, codecs, depth, and induction compile
```

Generated workspaces and fixture build output are reproducible and ignored.
