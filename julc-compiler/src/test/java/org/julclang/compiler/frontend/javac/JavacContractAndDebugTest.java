package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.schema.ContractCompileResult;
import org.julclang.compiler.DebugCompileResult;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068, entry points' slice: {@code compileContract} and {@code compileForDebug} on {@code frontend=JAVAC} give the
 * reference's bytes, schema and debug metadata. A location of a schema or a binding is compared where both frontends
 * start the same tree; where the reference's declarator starts at a name, javac's scan starts there too.
 */
class JavacContractAndDebugTest {

    private static final String SPENDING = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.Param;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class Vault {
                record State(BigInteger amount, byte[] owner) {}
                sealed interface Action permits Take, Give {}
                record Take(BigInteger amount) implements Action {}
                record Give() implements Action {}

                @Param static BigInteger limit;
                @Param static byte[] admin;

                static BigInteger twice(BigInteger x) {
                    BigInteger y = x.add(x);
                    return y;
                }

                static boolean anyLarge(JulcList<BigInteger> xs) {
                    return xs.any(x -> x.compareTo(limit) > 0);
                }

                @Entrypoint
                static boolean validate(Optional<State> datum, Action redeemer, ScriptContext ctx) {
                    var held = datum.isPresent() ? datum.get().amount() : BigInteger.ZERO;
                    BigInteger doubled = twice(held);
                    BigInteger more = doubled.add(BigInteger.ONE);
                    return switch (redeemer) {
                        case Take t -> t.amount().compareTo(more) <= 0;
                        case Give g -> anyLarge(JulcList.of(held, doubled));
                    };
                }
            }
            """;

    private static final String MINTING = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MintingValidator;

            @MintingValidator
            class Mint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                    BigInteger total = BigInteger.ZERO;
                    for (BigInteger x : xs) {
                        total = total.add(x);
                    }
                    return total.signum() > 0;
                }
            }
            """;

    private static final String MULTI = """
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Param;
            import org.julclang.stdlib.annotation.Purpose;

            @MultiValidator
            class Protocol {
                record State(BigInteger counter) {}
                record Spend(BigInteger next) {}
                record MintIt(byte[] tokenName) {}

                @Param static BigInteger seed;

                @Entrypoint(purpose = Purpose.SPEND)
                static boolean spend(Optional<State> datum, Spend redeemer, ScriptContext ctx) {
                    return redeemer.next().compareTo(seed) > 0;
                }

                @Entrypoint(purpose = Purpose.MINT)
                static boolean mint(MintIt redeemer, ScriptContext ctx) {
                    return redeemer.tokenName().length > 0;
                }
            }
            """;

    private static final String MANUAL = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MultiValidator;

            @MultiValidator
            class Manual {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    private static CompilerOptions options(CompilerFrontend frontend, OptimizationLevel level, boolean sourceMap) {
        return new CompilerOptions().setFrontend(frontend).setOptimizationLevel(level).setSourceMapEnabled(sourceMap);
    }

    private static ContractCompileResult contract(String source, CompilerFrontend frontend, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options(frontend, level, false))
                .compileContract(source, List.of());
    }

    private static DebugCompileResult debug(String source, CompilerFrontend frontend, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options(frontend, level, true))
                .compileForDebug(source, List.of());
    }

    // --- Contract schema ---

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void aContractHasTheReferencesBytesAndSchema(OptimizationLevel level) {
        for (var source : List.of(SPENDING, MINTING, MULTI)) {
            var reference = contract(source, CompilerFrontend.JAVAPARSER, level);
            var javac = contract(source, CompilerFrontend.JAVAC, level);
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.compileResult().program()),
                    UplcFlatEncoder.encodeProgram(javac.compileResult().program()));
            assertEquals(view(reference.contractSchema()), view(javac.contractSchema()));
            assertEquals(reference.contractSchema().namedDefinitions(), javac.contractSchema().namedDefinitions());
        }
    }

    @Test
    void theSchemaReadsTheOptionalDatumBareAndOrdersInterfacesByTag() {
        var schema = contract(MULTI, CompilerFrontend.JAVAC, OptimizationLevel.DEFAULT).contractSchema();
        assertEquals(List.of(ContractSchema.Purpose.MINT, ContractSchema.Purpose.SPEND),
                schema.interfaces().stream().map(ContractSchema.ValidatorInterface::purpose).toList());
        var spend = schema.interfaces().get(1);
        assertEquals("datum", spend.datum().name());
        assertTrue(!(spend.datum().type() instanceof PirType.OptionalType), spend.datum().type()::toString);
        assertTrue(schema.purposeIndexed());
        assertEquals(List.of("seed"), schema.parameters().stream().map(ContractSchema.Argument::name).toList());
    }

    @Test
    void manualDispatchHasNoSchemaOnEitherFrontend() {
        var reference = assertThrows(CompilerException.class, () -> contract(MANUAL, CompilerFrontend.JAVAPARSER,
                OptimizationLevel.DEFAULT));
        var javac = assertThrows(CompilerException.class, () -> contract(MANUAL, CompilerFrontend.JAVAC,
                OptimizationLevel.DEFAULT));
        assertEquals(reference.diagnostics().getFirst().message(), javac.diagnostics().getFirst().message());
        assertEquals(reference.diagnostics().getFirst().line(), javac.diagnostics().getFirst().line());
        assertEquals(reference.diagnostics().getFirst().column(), javac.diagnostics().getFirst().column());
        // Without a schema the program compiles, on both frontends.
        new JulcCompiler(StdlibRegistry.defaultRegistry(), options(CompilerFrontend.JAVAC, OptimizationLevel.DEFAULT, false))
                .compile(MANUAL, List.of());
    }

    private static String view(ContractSchema schema) {
        var text = new StringBuilder("indexed=" + schema.purposeIndexed() + "\n");
        for (var validatorInterface : schema.interfaces()) {
            text.append(validatorInterface.purpose()).append(' ').append(validatorInterface.entrypointName())
                    .append(at(validatorInterface.sourceLocation())).append("\n  datum ").append(view(validatorInterface.datum()))
                    .append("\n  redeemer ").append(view(validatorInterface.redeemer())).append('\n');
        }
        for (var parameter : schema.parameters()) text.append("param ").append(view(parameter)).append('\n');
        return text.toString();
    }

    private static String view(ContractSchema.Argument argument) {
        return argument == null ? "-" : argument.name() + ": " + argument.type() + at(argument.sourceLocation());
    }

    /** A location's line; a field's column is where its modifiers start on javac and its name on the reference. */
    private static String at(SourceLocation location) {
        return " @" + location.line();
    }

    // --- Debug metadata ---

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void debugMetadataIsTheReferences(OptimizationLevel level) {
        for (var source : List.of(SPENDING, MINTING, MULTI)) {
            var reference = debug(source, CompilerFrontend.JAVAPARSER, level);
            var javac = debug(source, CompilerFrontend.JAVAC, level);
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.compileResult().program()),
                    UplcFlatEncoder.encodeProgram(javac.compileResult().program()));
            javac.debugMetadata().validateArtifact(javac.compileResult().program());
            assertEquals(view(reference.debugMetadata()), view(javac.debugMetadata()));
        }
    }

    @Test
    void aMaterializedLocalIsAssociatedWithItsBinderAndAMutatingMethodsAreNot() {
        var spending = debug(SPENDING, CompilerFrontend.JAVAC, OptimizationLevel.NONE).debugMetadata();
        var materialized = spending.loweredBindings().stream().map(DebugMetadata.LoweredBinding::sourceBindingId)
                .filter(id -> id != null).collect(Collectors.toSet());
        var names = spending.bindings().stream().filter(binding -> materialized.contains(binding.id()))
                .map(DebugMetadata.Binding::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(List.of("x", "limit", "admin")), names::toString);

        var minting = debug(MINTING, CompilerFrontend.JAVAC, OptimizationLevel.NONE).debugMetadata();
        assertTrue(minting.loweredBindings().stream().allMatch(binding -> binding.sourceBindingId() == null
                        || minting.bindings().stream().noneMatch(source -> source.id().equals(binding.sourceBindingId())
                        && source.name().equals("total"))),
                "a reassigned local is not presented as its Java value");
    }

    @Test
    void theDebugMetadataNamesTheReferencesIds() {
        var reference = debug(SPENDING, CompilerFrontend.JAVAPARSER, OptimizationLevel.DEFAULT).debugMetadata();
        var javac = debug(SPENDING, CompilerFrontend.JAVAC, OptimizationLevel.DEFAULT).debugMetadata();
        assertEquals(reference.bindings().stream().map(DebugMetadata.Binding::id).toList(),
                javac.bindings().stream().map(DebugMetadata.Binding::id).toList());
        assertEquals(reference.scopes().stream().map(DebugMetadata.Scope::id).toList(),
                javac.scopes().stream().map(DebugMetadata.Scope::id).toList());
        assertNotEquals(0, javac.bindings().size());
    }

    /**
     * A local that shadows a field takes another binder name (I13), so its binder is not the declaration as written: it
     * compiles, and it is not shown, where the reference shows it under its own name.
     */
    @Test
    void aRenamedLocalCompilesAndIsNotShown() {
        var source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MintingValidator;

                @MintingValidator
                class Shadow {
                    static final BigInteger K = BigInteger.TEN;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        BigInteger k = K;
                        BigInteger K = k.add(k);
                        return K.signum() > 0;
                    }
                }
                """;
        var metadata = debug(source, CompilerFrontend.JAVAC, OptimizationLevel.NONE).debugMetadata();
        var shown = metadata.loweredBindings().stream().map(DebugMetadata.LoweredBinding::sourceBindingId)
                .filter(id -> id != null).collect(Collectors.toSet());
        var local = metadata.bindings().stream().filter(binding -> binding.name().equals("K")
                && binding.kind() == DebugMetadata.BindingKind.LOCAL).findFirst().orElseThrow();
        assertTrue(!shown.contains(local.id()), "a renamed binder is not the declaration's");
        assertTrue(metadata.bindings().stream().anyMatch(binding -> binding.name().equals("k") && shown.contains(binding.id())));
    }

    private static String view(DebugMetadata metadata) {
        var text = new StringBuilder();
        // The reference names a string source by its path under the working directory, javac by its file name.
        metadata.sources().forEach(source -> text.append("source ").append(source.id()).append(' ')
                .append(Path.of(source.uri()).getFileName())
                .append(' ').append(source.contentSha256()).append('\n'));
        metadata.scopes().forEach(scope -> text.append("scope ").append(scope).append('\n'));
        // A binding's type is compared where it is shown: the reference also types a parameter it does not show, which
        // javac leaves at its spelling.
        var shown = metadata.loweredBindings().stream().map(DebugMetadata.LoweredBinding::sourceBindingId)
                .filter(id -> id != null).collect(Collectors.toSet());
        metadata.bindings().forEach(binding -> text.append("binding ").append(binding.id()).append(' ').append(binding.name())
                .append(' ').append(binding.declarationRange()).append(' ').append(binding.scopeId()).append(' ')
                .append(binding.kind()).append(' ').append(binding.declaredSpelling())
                .append(shown.contains(binding.id()) ? " " + binding.typeId() : "").append('\n'));
        metadata.loweredBindings().forEach(binding -> text.append("lowered ").append(binding).append('\n'));
        metadata.suspensionPoints().forEach(point -> text.append("point ").append(point).append('\n'));
        return text.toString();
    }
}
