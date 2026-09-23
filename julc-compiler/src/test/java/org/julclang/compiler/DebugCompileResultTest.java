package org.julclang.compiler;

import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DebugCompileResultTest {
    private static final String SOURCE = """
            import java.math.BigInteger;

            @SpendingValidator
            class DebugLocalsValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger exact = BigInteger.valueOf(9007199254740993L);
                    return exact.compareTo(BigInteger.ZERO) > 0;
                }
            }
            """;

    @Test
    void localsCollectionIsByteIdenticalToSourceMapOnlyCompilation() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var sourceMapOnly = new JulcCompiler(StdlibRegistry.defaultRegistry(), options)
                .compileWithDetails(SOURCE, List.of());
        var withLocals = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setSourceMapEnabled(true)).compileForDebug(SOURCE, List.of());

        assertArrayEquals(UplcFlatEncoder.encodeProgram(sourceMapOnly.program()),
                UplcFlatEncoder.encodeProgram(withLocals.compileResult().program()));
        assertEquals(sourceMapOnly.optimizationReport(), withLocals.compileResult().optimizationReport());
        withLocals.debugMetadata().validateArtifact(withLocals.compileResult().program());
        assertTrue(withLocals.debugMetadata().bindings().stream().anyMatch(binding -> binding.name().equals("exact")));
    }

    @Test
    void metadataIdsAndDigestAreDeterministicAcrossFreshCompilers() {
        var first = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setSourceMapEnabled(true)).compileForDebug(SOURCE, List.of()).debugMetadata();
        var second = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setSourceMapEnabled(true)).compileForDebug(SOURCE, List.of()).debugMetadata();

        assertEquals(first.bindings().stream().map(org.julclang.core.debug.DebugMetadata.Binding::id).toList(),
                second.bindings().stream().map(org.julclang.core.debug.DebugMetadata.Binding::id).toList());
        assertEquals(first.canonicalDigest(), second.canonicalDigest());
    }

    @Test
    void debugEntryPointRequiresExplicitSourceMapCompilation() {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions());
        var error = assertThrows(IllegalStateException.class, () -> compiler.compileForDebug(SOURCE, List.of()));
        assertTrue(error.getMessage().contains("source-map"));
    }

    @Test
    void initializerDoesNotSeeItsOwnBinding() {
        var metadata = compileDebug(SOURCE).debugMetadata();
        String exactId = metadata.bindings().stream().filter(binding -> binding.name().equals("exact"))
                .map(DebugMetadata.Binding::id).findFirst().orElseThrow();
        String loweredId = metadata.loweredBindings().stream()
                .filter(binding -> exactId.equals(binding.sourceBindingId()))
                .map(DebugMetadata.LoweredBinding::id).findFirst().orElseThrow();

        assertTrue(metadata.suspensionPoints().stream().anyMatch(point ->
                        point.range() != null && point.range().startLine() == 7
                                && !point.expectedBinderIds().contains(loweredId)),
                "the initializer must execute outside the local's binder");
        assertTrue(metadata.suspensionPoints().stream().anyMatch(point ->
                        point.visibleBindings().stream().anyMatch(value -> value.bindingId().equals(exactId))),
                "the local must become visible in the continuation after initialization");
    }

    @Test
    void lambdaCapturesAndSameSpellingUseStableDistinctIdentities() {
        var metadata = compileDebug("""
                import java.math.BigInteger;

                @SpendingValidator
                class CaptureDebugValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        BigInteger captured = BigInteger.TEN;
                        var first = (BigInteger x) -> x.add(captured);
                        var second = (BigInteger x) -> x.subtract(captured);
                        return true;
                    }
                }
                """).debugMetadata();

        var xBindings = metadata.bindings().stream().filter(binding -> binding.name().equals("x")).toList();
        assertEquals(2, xBindings.size());
        assertEquals(2, xBindings.stream().map(DebugMetadata.Binding::id).collect(Collectors.toSet()).size());
        Set<String> loweredX = metadata.loweredBindings().stream()
                .map(DebugMetadata.LoweredBinding::sourceBindingId)
                .filter(id -> id != null && xBindings.stream().anyMatch(binding -> binding.id().equals(id)))
                .collect(Collectors.toSet());
        assertEquals(2, loweredX.size());

        String captured = metadata.bindings().stream().filter(binding -> binding.name().equals("captured"))
                .map(DebugMetadata.Binding::id).findFirst().orElseThrow();
        assertTrue(metadata.suspensionPoints().stream().anyMatch(point -> {
            var visible = point.visibleBindings().stream().map(DebugMetadata.VisibleBinding::bindingId)
                    .collect(Collectors.toSet());
            return visible.contains(captured) && xBindings.stream().anyMatch(binding -> visible.contains(binding.id()));
        }), "a lambda body must retain the exact outer capture identity");
        assertFalse(metadata.suspensionPoints().stream().anyMatch(point -> {
            var visible = point.visibleBindings().stream().map(DebugMetadata.VisibleBinding::bindingId)
                    .collect(Collectors.toSet());
            return visible.containsAll(xBindings.stream().map(DebugMetadata.Binding::id).toList());
        }), "same-spelled parameters from separate lambdas must never alias");
    }

    @Test
    void reassignmentMethodsDoNotClaimSourceBinderMappings() {
        var metadata = compileDebug("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                @SpendingValidator
                class MutableDebugValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<BigInteger> values = Builtins.unListData(redeemer);
                        BigInteger total = BigInteger.ZERO;
                        for (BigInteger value : values) {
                            total = total.add(value);
                        }
                        return total.signum() >= 0;
                    }
                }
                """).debugMetadata();

        Set<String> sourceIds = metadata.bindings().stream().map(DebugMetadata.Binding::id)
                .collect(Collectors.toSet());
        assertFalse(sourceIds.isEmpty());
        assertTrue(metadata.loweredBindings().stream().noneMatch(lowered ->
                        lowered.sourceBindingId() != null && sourceIds.contains(lowered.sourceBindingId())),
                "generated loop accumulators are not yet an identity-preserving source binding proof");
        assertTrue(metadata.suspensionPoints().stream().flatMap(point -> point.visibleBindings().stream())
                        .anyMatch(visible -> visible.availability()
                                == DebugMetadata.Availability.UNSUPPORTED_LOWERING
                                && visible.reason().contains("not identity-proven")),
                "an in-scope mutable declaration must be explicitly unavailable rather than guessed or hidden");
    }

    private static DebugCompileResult compileDebug(String source) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setSourceMapEnabled(true)).compileForDebug(source, List.of());
    }
}
