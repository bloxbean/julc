package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContractSchemaTest {

    private static final String SOURCE = """
            import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
            import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
            import com.bloxbean.cardano.julc.ledger.ScriptContext;
            import java.math.BigInteger;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;

            @SpendingValidator
            public class SchemaGate {
                public record Datum(List<BigInteger> values,
                                    Map<byte[], BigInteger> balances,
                                    Optional<byte[]> owner,
                                    boolean active) {}
                public sealed interface Action permits Update, Close {}
                public record Update(BigInteger amount) implements Action {}
                public record Close() implements Action {}

                @Entrypoint
                public static boolean validate(Datum datum, Action redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void schemaAwareCompilationIsByteIdenticalAndExposesResolvedTypes() {
        var compiler = new JulcCompiler();
        var ordinary = compiler.compile(SOURCE);
        var captured = compiler.compileContract(SOURCE);

        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(ordinary.program()),
                UplcFlatEncoder.encodeProgram(captured.compileResult().program()));

        var schema = captured.contractSchema();
        assertEquals("spending", schema.purpose());
        assertInstanceOf(PirType.RecordType.class, schema.datum().type());
        assertInstanceOf(PirType.SumType.class, schema.redeemer().type());
        var datum = (PirType.RecordType) schema.datum().type();
        assertInstanceOf(PirType.ListType.class, datum.fields().get(0).type());
        assertInstanceOf(PirType.MapType.class, datum.fields().get(1).type());
        assertInstanceOf(PirType.OptionalType.class, datum.fields().get(2).type());
        assertInstanceOf(PirType.BoolType.class, datum.fields().get(3).type());
        assertTrue(schema.parameters().isEmpty());
        assertTrue(schema.datum().sourceLocation().line() > 0);
    }

    @Test
    void ordinaryCompilationRemainsAvailableWhenMultiSchemaIsUnsupported() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;

                @MultiValidator
                public class MultiGate {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var compiler = new JulcCompiler();
        assertNotNull(compiler.compile(source).program());
        var error = assertThrows(CompilerException.class, () -> compiler.compileContract(source));
        assertTrue(error.getMessage().contains("--no-blueprint"));
        assertFalse(error.diagnostics().isEmpty());
        assertTrue(error.diagnostics().getFirst().line() > 0);
    }

    @Test
    void spendingLedgerOptionalIsNotPartOfTheAttachedDatumSchema() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.Optional;

                @SpendingValidator
                class OptionalDatumGate {
                    record Datum(BigInteger value, Optional<byte[]> owner) {}
                    record Redeemer(BigInteger value) {}

                    @Entrypoint
                    static boolean validate(Optional<Datum> datum, Redeemer redeemer,
                                            ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = new JulcCompiler().compileContract(source).contractSchema();
        var datum = assertInstanceOf(PirType.RecordType.class, schema.datum().type());
        assertEquals("Datum", datum.name());
        assertInstanceOf(PirType.OptionalType.class, datum.fields().get(1).type(),
                "nested Optional is part of the attached datum encoding");
    }

    @Test
    void pathApiPreservesSourceIdentityAndProgramBytes(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("SchemaGate.java");
        Files.writeString(sourceFile, SOURCE);

        var compiler = new JulcCompiler();
        var ordinary = compiler.compile(sourceFile);
        var captured = compiler.compileContract(sourceFile);

        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(ordinary.program()),
                UplcFlatEncoder.encodeProgram(captured.compileResult().program()));
        assertEquals("SchemaGate.java",
                captured.contractSchema().datum().sourceLocation().fileName());
    }
}
