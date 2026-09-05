# ADR-035: Lookup-owned import names

Status: Implemented and locally validated as a review correction for #22 / PR #124; re-review pending.

## Context and current behavior

JulcCompiler historically seeded import resolution with 14 hardcoded stdlib
class names. PR #124 removed all but Builtins on the assumption that source
scanning supplied every other name. Independent review disproved that assumption:
explicit-library-list entry points do not scan, and registry-only methods such
as ListsLib.any, ContextsLib.trace and MathLib.floorDiv require exact FQCN lookup.
Some typed overrides accept simple names, concealing the partial regression.

## Goals and non-goals

Preserve registry-served calls on explicit-list APIs while removing compiler-owned
stdlib name duplication. Preserve source discovery, import precedence and exact
lookup semantics. Do not add classpath scanning to explicit-list APIs, change
modular exponentiation, or redesign CLI/playground completion metadata.

## Decision and invariants

Add default `Set<String> registeredClassNames()` to StdlibLookup. It returns
importable fully qualified names owned by that lookup; the default is empty for
implementations without metadata. Simple-name aliases are not importable names.
StdlibRegistry derives this set from its actual registrations, filtering names
without dots. Composite lookups union their delegates' names. The compiler's
NewType wrapper delegates the metadata unchanged. Both compiler entry points
seed knownFqcns from the supplied lookup as well as existing types/library CUs.

- No source scanning is introduced or suppressed.
- Lookup order, argument evaluation, decoding, failures and target gating stay
  unchanged; this metadata enables name resolution only, not method legality.
- Actual registrations own names; no second registry or compiler list is added.
- Name-set iteration must not control generated-code order. Return deterministic
  sets where constructed; import resolution uses membership, not traversal order.
- Null lookup remains supported. Legacy lookup implementations still compile via
  the default method; custom FQCN registries should expose their names explicitly.

## Affected modules/stages and compatibility

julc-compiler: StdlibLookup, composite/NewType delegation and import-resolution
setup. julc-stdlib: registration-derived metadata. Tests exercise both overload
families and direct/composed/custom lookups. The new default interface method is
an additive API; no existing abstract implementation requirement changes. Source
libraries still obtain names from their parsed compilation units. Historical
representative bytes must remain unchanged.

## Alternatives rejected

Retaining only Builtins breaks registry-only calls. Keeping a smaller hardcoded
list would require manual synchronization. Scanning within explicit-list APIs
changes their contract. Adding simple-name fallback to registry lookup risks
collisions and changes resolution semantics. CLI/playground's separate source
library inventory cannot be replaced with registered-only names because it also
includes source-only classes; handle that in a separate follow-up.

## Risks and verification

Metadata can be lost through decorators or composites, or advertise simple-name
aliases incorrectly. Test the six reproduced explicit-empty-list failures,
expected VM results and traces, custom FQCN registrations, composition, null
lookup, and existing NewType behavior. Retain pre-change bytes and run compiler,
stdlib, testkit, plugin/processor and full repository suites. Green tests alone
do not prove all resolution behavior; review every known-name population site
and every lookup decorator.

## Milestones

1. Reproduce the six failures before fixing production code.
2. Implement lookup metadata, delegation and regression coverage.
3. Run affected/full validation and correct PR/evidence claims.

## Open questions

CLI/playground's static source-library inventory remains a separate cleanup.
External lookup decorators with custom FQCN registrations should propagate the
new metadata; the default cannot infer their private registration tables.
