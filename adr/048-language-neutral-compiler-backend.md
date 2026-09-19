# ADR-048: Experimental language-neutral compiler backend

Status: Proposed

## Context and goals

DSL frontends that produce typed PIR need the same target checks, validator
boundaries, lowering passes and optimizer as the Java frontend. Java compilation
currently owns post-wrapper orchestration. Direct PIR producers and the existing
Java frontend provide two consumers for a shared pipeline.

## Decision and affected stages

Extract `PirBackend` inside `julc-compiler`. Preserve `JulcCompiler` public APIs,
metadata, pass order, logging and source-map behavior. Add `FrontendProgram` and
`CompilerBackend` for specialized, closed, unwrapped PIR with explicit FUNCTION,
SPEND and MINT profiles. No Java AST crosses this entrypoint.

A producer supplies concrete types, named definitions and target provenance.
The backend checks lexical closure, target agreement, boundary policy and handler
arity/result shape, then applies the existing validator wrapper exactly once.
`julc-strict-v1` uses existing strict datum/redeemer decoding. Context remains the
existing ledger-supplied Data representation. Producers must not pre-wrap terms.

`PirLinker` links insertion-ordered definition maps through dependency SCCs.
Self recursion and two-function mutual recursion are supported; eager recursive
values and larger groups fail explicitly. Supplied definitions are strict; callers
must select their desired dependency closure before linking.

`LibraryProvider` describes concrete exports and materializes closed PIR.
`JavaLibraryProvider` uses the actual on-chain Java compiler and bundled ledger
sources, supports public static concrete methods, and rejects ambiguous overloads.
It does not execute library code on the JVM. Source parsing is invocation-local.

## Invariants and compatibility

Preserve Java evaluation order, data representation, failure behavior, wrapper
selection, source-map identity and optimization reporting. No VM or ledger codec
changes. Java discovery and multi-purpose orchestration remain in the existing
facade. The shared extraction starts after Java wrapping and parameter decoding.
No published version change is part of this work.

## Non-goals and limitations

This is an experimental producer API, not a verifier for untrusted PIR. Closure
checks do not prove term/type agreement or correct runtime representations.
Producers own type checking, specialization, hygiene, representation planning and
frontend diagnostics. This revision has no deployment parameters, optional-datum
profile, additional script purposes or frontend source-position transport.
Generic export schemes, programmatic library catalogues and public ledger type
adapters are follow-on work. These must not be approximated through erased Data.

## Alternatives

Source-to-Java translation adds a second frontend semantic translation. Duplicated
UPLC pipelines risk divergent pass ordering and target behavior. Both are rejected.
A physical module split and moving all Java orchestration through FrontendProgram
are deferred until richer metadata and API usage justify that migration.

## Risks and verification

A shared extraction can change optimization logs, captured PIR or source maps even
when evaluation agrees. Existing compiler, pair-case backend, stdlib and testkit
suites must pass. Direct API tests cover evaluation, closure failures, boundary
rejection, dependency linking and library visibility. Compare Java compilation
artifacts against the pre-extraction compiler across optimization and debug modes.

## Milestones and open questions

1. Extract the unchanged post-wrapper pipeline and establish regression evidence.
2. Add the bounded neutral entrypoint, linker and concrete Java provider with tests.
3. Separately design generic providers, ledger adapters and additional boundaries.

A complete PIR verifier, source-position API and versioning policy remain open.
