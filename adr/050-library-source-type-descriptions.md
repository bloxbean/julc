# ADR-050: Source type descriptions for on-chain libraries

Status: Accepted for implementation

## Context and decision

Concrete PIR signatures erase nominal newtype identities. DSL consumers need source
signatures and type definitions, independently of the library method name. Extend
LibraryExport with ordered source parameters and result references; extend
LibraryProvider with immutable type definitions and named PIR definitions. Preserve
the three-argument export constructor and existing materialize API.

JavaLibraryProvider derives descriptions from the same parsed sources and resolver
used to compile implementations. Include bundled ledger types and supplied records,
sealed sums and newtypes. References use qualified nominal identities and container
arguments. Definitions retain compiler-selected representations and constructor tags.
Only representation-verified record fields/newtype fields permit direct projections;
special encodings remain opaque. Source types are never reconstructed by matching
erased PIR shapes. Overloads and duplicate ownership are rejected.

Sources may come from explicit JARs containing META-INF/plutus-sources/**/*.java.
Read these as resources, never load classes or execute JVM initializers. Collect
dependencies deterministically, reject conflicting source paths and missing sources.
Source discovery is a separate service from the neutral metadata contract.

## Invariants and compatibility

No change to existing Java compilation, codecs, evaluation order or target selection.
Imported types stay nominal. No arbitrary source type is silently represented as
Data. Concrete containers are supported; polymorphic Java method specialization and
generic user-defined records remain a separate extension and must be diagnosed.
Sums may be passed through library functions without implying frontend pattern or
constructor support. Constructors/factories in supported Java source remain callable.

## Alternatives, risks and verification

Per-method frontend signatures and inference from erased PIR are rejected. Reflection
cannot supply source semantics and is rejected. Reuse TypeRegistrar/TypeResolver;
do not introduce a second encoding planner. Primary risks are nominal erasure,
incorrect special-layout projection and incomplete dependency sources. Test newtypes,
records, sums, nested containers, recursive references, collisions, missing sources,
unsupported signatures and VM behavior. Run compiler and dependent regression suites.

## Milestones and open work

1. Neutral descriptions and Java-source extraction with compatibility tests.
2. Deterministic JAR source input and frontend consumption with VM fixtures.
3. Native executable and Java regression validation.

Generic schemes/specialization, programmatic export catalogues and stable serialized
metadata are future work; this API remains experimental and invocation-local.

## Validation evidence

The compiler (1,652 tests), stdlib (411), testkit (193) and annotation processor
(20) suites passed with no failures on 20 September 2026. Focused neutral-provider
fixtures cover nominal newtypes with identical PIR, recursive sums and tags,
non-record ledger layouts, raw-container diagnostics and duplicate ownership.
Consumer integration additionally covers source-only JARs, nested record lists,
record factories and projections, nominal mismatch rejection, exposed imports,
transitive library dependencies and native-image compilation. The Java facade,
LibraryCompiler, TypeRegistrar and TypeResolver implementations are unchanged.
