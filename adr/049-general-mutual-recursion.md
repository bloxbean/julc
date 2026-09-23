# ADR-049: General function mutual recursion in PIR

Status: Proposed

## Context and decision

`PirTerm.LetRec` represents an arbitrary ordered binding list, but UPLC lowering
previously implemented only a single fixed point or a two-binding Bekić
decomposition. Language-neutral PIR producers therefore had an artificial limit
of two mutually recursive functions.

Accept finite function-only strongly connected components in `PirLinker` and
recursively decompose groups larger than two in `UplcGenerator`. Select the first
binding in stable input order as the outer fixed point. Treat the remaining group
as an inner fixed point, substitute its requested projections into the outer
function, and bind each inner projection for the final body. Recursion reduces by
one participant until the existing two-binding implementation applies.

## Invariants and compatibility

Only lambda-valued recursive definitions are accepted by the neutral linker.
Eager recursive values remain rejected. Stable definition order determines the
decomposition, so compilation is deterministic. Lexical substitution remains
capture-avoiding and respects every PIR binder.

The single- and two-binding decomposition strategies are unchanged. Shared
substitution now alpha-renames binders that would capture a free variable in its
replacement. This also corrects the existing two-binding path for shadowing
programs that previously compiled incorrectly. Java frontend method ordering and
Java source-language acceptance are unchanged; unaffected programs retain their
existing output.

Fresh names are invocation-local and deterministic. Before renaming, reserve all
variable and binder names from both the source and replacement. Rename bound
occurrences with their original types and lexical scopes, including simultaneous
LetRec scope, match branch fields and pattern variables. Ordinary Let values,
match scrutinees and list nil branches retain their outer scopes.

Source maps and Java-locals debug provenance (ADR-058) are keyed by PIR node
identity, so variable occurrences that are not renamed keep their identity. Only a
renamed occurrence loses its source position; that happens only where the previous
lowering captured a variable and compiled incorrectly.

Repeated inner projections can increase script size and runtime work. This first
general implementation favors a small correctness-preserving extension over a
new tuple-of-functions runtime representation. Measure groups of increasing size
and revisit sharing if representative programs show material growth.

## Verification

Test three and four functions with different arities and return types, dependency
isolation, deterministic output, terminating evaluation, bounded divergence, and
closure rejection. Run the compiler, cross-VM pair-case, stdlib and testkit
suites. Compare existing Java artifacts before and after the change where the new
path is not exercised.

## Alternatives

An untyped Data dispatcher is rejected because it discards concrete function
types and adds runtime encoding. A new tuple-of-closures encoding may reduce
duplication but requires a larger typed design. Keeping the two-function limit is
rejected because the PIR model and frontend contract describe general recursive
groups.
