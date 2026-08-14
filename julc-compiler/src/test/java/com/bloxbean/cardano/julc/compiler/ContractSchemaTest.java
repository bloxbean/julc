package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void capturesProductiveRecursiveSumWithNominalBackReference() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @SpendingValidator
                class RecursiveGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Node next) implements Node {}
                    record Datum(Node root) {}
                    record Redeemer(BigInteger expected) {}

                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer,
                                            ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = new JulcCompiler().compileContract(source).contractSchema();
        var datum = assertInstanceOf(PirType.RecordType.class, schema.datum().type());
        var node = assertInstanceOf(PirType.SumType.class, datum.fields().getFirst().type());
        var cons = node.constructors().stream()
                .filter(constructor -> constructor.name().equals("Cons"))
                .findFirst().orElseThrow();
        var recursive = assertInstanceOf(
                PirType.NamedTypeRef.class, cons.fields().get(1).type());

        assertEquals("Node", recursive.name());
        assertEquals(PirType.NamedKind.SUM, recursive.kind());
        assertSame(node, schema.namedDefinitions().get(recursive.stableId()));
    }

    @Test
    void recursiveNominalFieldCanBeInspectedByValidatorLogic() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @SpendingValidator
                class RecursiveInspectionGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Node next) implements Node {}
                    record Datum(Node root) {}
                    record Redeemer(BigInteger expected) {}

                    static boolean hasAtLeastTwo(Node node) {
                        return switch (node) {
                            case End end -> false;
                            case Cons cons -> switch (cons.next()) {
                                case End tail -> false;
                                case Cons tail -> true;
                            };
                        };
                    }

                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer,
                                            ScriptContext ctx) {
                        return hasAtLeastTwo(datum.root());
                    }
                }
                """;

        assertNotNull(new JulcCompiler().compileContract(source).compileResult().program());
    }

    @Test
    void recursiveNominalFieldLogicEvaluatesAgainstEncodedData() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @MintingValidator
                class RecursiveEvaluationGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Node next) implements Node {}

                    static boolean hasAtLeastTwo(Node node) {
                        return switch (node) {
                            case End end -> false;
                            case Cons cons -> switch (cons.next()) {
                                case End tail -> false;
                                case Cons tail -> true;
                            };
                        };
                    }

                    @Entrypoint
                    static boolean validate(Node redeemer, ScriptContext ctx) {
                        return hasAtLeastTwo(redeemer);
                    }
                }
                """;

        var program = new JulcCompiler().compileContract(source).compileResult().program();
        var end = PlutusData.constr(0);
        var oneNode = PlutusData.constr(1, PlutusData.integer(1), end);
        var twoNodes = PlutusData.constr(
                1, PlutusData.integer(1),
                PlutusData.constr(1, PlutusData.integer(2), end));
        var vm = JulcVm.create();

        var accepted = vm.evaluateWithArgs(program, List.of(mintingContext(twoNodes)));
        var rejected = vm.evaluateWithArgs(program, List.of(mintingContext(oneNode)));

        assertTrue(accepted.isSuccess(), "two recursive nodes should satisfy the validator");
        assertFalse(rejected.isSuccess(), "one recursive node should be rejected");
    }

    private static PlutusData mintingContext(PlutusData redeemer) {
        return PlutusData.constr(
                0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
    }

    @Test
    void rejectsStrictRecursiveRecordWithoutFiniteBaseAtSource() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @MintingValidator
                class BadRecursiveGate {
                    record Bad(BigInteger value, Bad next) {}

                    @Entrypoint
                    static boolean validate(Bad redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var error = assertThrows(
                CompilerException.class, () -> new JulcCompiler().compileContract(source));

        assertTrue(error.getMessage().contains("no finite base constructor"));
        assertFalse(error.diagnostics().isEmpty());
        assertTrue(error.diagnostics().getFirst().line() > 0);
    }

    @Test
    void acceptsContainerGuardedSelfRecursion() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.util.List;

                @MintingValidator
                class RecursiveListGate {
                    record Tree(List<Tree> children) {}

                    @Entrypoint
                    static boolean validate(Tree redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = new JulcCompiler().compileContract(source).contractSchema();
        var tree = assertInstanceOf(PirType.RecordType.class, schema.redeemer().type());
        var children = assertInstanceOf(PirType.ListType.class, tree.fields().getFirst().type());
        assertInstanceOf(PirType.NamedTypeRef.class, children.elemType());
    }

    @Test
    void capturesProductiveMutuallyRecursiveSums() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @MintingValidator
                class MutualGate {
                    sealed interface Left permits LeftEnd, ToRight {}
                    record LeftEnd() implements Left {}
                    record ToRight(Right next) implements Left {}

                    sealed interface Right permits RightEnd, ToLeft {}
                    record RightEnd() implements Right {}
                    record ToLeft(Left next) implements Right {}

                    @Entrypoint
                    static boolean validate(Left redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = new JulcCompiler().compileContract(source).contractSchema();
        var left = assertInstanceOf(PirType.SumType.class, schema.redeemer().type());
        var rightRef = assertInstanceOf(PirType.NamedTypeRef.class,
                left.constructors().get(1).fields().getFirst().type());
        var right = assertInstanceOf(
                PirType.SumType.class, schema.namedDefinitions().get(rightRef.stableId()));
        var leftRef = assertInstanceOf(PirType.NamedTypeRef.class,
                right.constructors().get(1).fields().getFirst().type());

        assertEquals("Left", leftRef.name());
        assertSame(left, schema.namedDefinitions().get(leftRef.stableId()));
    }

    @Test
    void rejectsMutualStrictCycleWithoutFiniteBase() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @MintingValidator
                class BadMutualGate {
                    record Left(Right next) {}
                    record Right(Left next) {}

                    @Entrypoint
                    static boolean validate(Left redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var error = assertThrows(
                CompilerException.class, () -> new JulcCompiler().compileContract(source));

        assertTrue(error.getMessage().contains("no finite base constructor"));
        assertTrue(error.getMessage().contains("Left"));
        assertTrue(error.getMessage().contains("Right"));
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
