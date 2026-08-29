package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrictDataBoundaryTest {
    private static final JulcVm VM = CompilerTestVm.pv11();
    private static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    @Test
    void strictRecordRejectsWrongTagMissingTrailingAndMalformedFields() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator
                class StrictRecord {
                    record Redeemer(BigInteger amount, byte[] owner) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        assertValid(program, PlutusData.constr(0,
                PlutusData.integer(1), PlutusData.bytes(new byte[]{1})));
        assertInvalid(program, PlutusData.constr(9,
                PlutusData.integer(1), PlutusData.bytes(new byte[]{1})));
        assertInvalid(program, PlutusData.constr(0, PlutusData.integer(1)));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.integer(1), PlutusData.bytes(new byte[]{1}),
                PlutusData.integer(2)));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{1})));
        assertInvalid(program, PlutusData.integer(1));
    }

    @Test
    void nestedRecordAndVariantAreValidatedEvenWhenUserCodeIgnoresThem() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator
                class StrictNested {
                    sealed interface Action permits Pay, Cancel {}
                    record Pay(BigInteger amount) implements Action {}
                    record Cancel() implements Action {}
                    record Redeemer(byte[] owner, Action action) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        assertValid(program, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}),
                PlutusData.constr(0, PlutusData.integer(10))));
        assertValid(program, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}),
                PlutusData.constr(1)));
        assertInvalid(program, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}),
                PlutusData.constr(0, PlutusData.integer(10), PlutusData.integer(11))));
        assertInvalid(program, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}),
                PlutusData.constr(7)));
        assertInvalid(program, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}),
                PlutusData.constr(1, PlutusData.integer(0))));
    }

    @Test
    void standaloneVariantRecordsFailAtSourceUntilNominalTagsAreUnified() {
        String direct = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator class DirectVariant {
                    sealed interface Action permits Cancel, Pay {}
                    record Cancel() implements Action {}
                    record Pay(BigInteger amount) implements Action {}
                    @Entrypoint static boolean validate(Pay redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var directError = assertThrows(CompilerException.class,
                () -> new JulcCompiler(STDLIB).compile(direct));
        assertTrue(directError.getMessage().contains(
                "variant record 'Pay' of sealed interface 'Action'"));
        assertTrue(directError.getMessage().contains("declare the sealed interface type"));
        assertTrue(directError.diagnostics().getFirst().line() > 0);

        String firstVariant = direct.replace("Pay redeemer", "Cancel redeemer");
        var firstVariantError = assertThrows(CompilerException.class,
                () -> new JulcCompiler(STDLIB).compile(firstVariant));
        assertTrue(firstVariantError.getMessage().contains(
                "variant record 'Cancel' of sealed interface 'Action'"));

        String nested = direct
                .replace("record Pay(BigInteger amount) implements Action {}", """
                        record Pay(BigInteger amount) implements Action {}
                        record Envelope(Pay action) {}""")
                .replace("Pay redeemer", "Envelope redeemer");
        var nestedError = assertThrows(CompilerException.class,
                () -> new JulcCompiler(STDLIB).compile(nested));
        assertTrue(nestedError.getMessage().contains(
                "variant record 'Pay' of sealed interface 'Action'"));

        String sumTyped = direct.replace("Pay redeemer", "Action redeemer");
        var sumProgram = new JulcCompiler(STDLIB).compile(sumTyped).program();
        assertValid(sumProgram, PlutusData.constr(1, PlutusData.integer(5)));
    }

    @Test
    void strictContainersOptionalsAndDuplicateMapEntries() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                @MintingValidator
                class StrictContainers {
                    record Redeemer(List<BigInteger> values,
                                    Map<byte[], BigInteger> balances,
                                    Optional<byte[]> owner) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        var duplicateMap = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.integer(2)),
                new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.integer(3)));
        assertValid(program, PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                duplicateMap,
                PlutusData.constr(0, PlutusData.bytes(new byte[]{9}))));
        assertValid(program, PlutusData.constr(0,
                PlutusData.list(), PlutusData.map(), PlutusData.constr(1)));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(1), PlutusData.bytes(new byte[]{2})),
                PlutusData.map(), PlutusData.constr(1)));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.list(),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.integer(1), PlutusData.integer(2))),
                PlutusData.constr(1)));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.list(), PlutusData.map(),
                PlutusData.constr(0, PlutusData.bytes(new byte[]{9}),
                        PlutusData.bytes(new byte[]{10}))));
    }

    @Test
    void productiveRecursiveValuesAreCheckedAtEveryDepth() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator
                class StrictRecursive {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Node next) implements Node {}
                    @Entrypoint
                    static boolean validate(Node redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        var end = PlutusData.constr(0);
        assertValid(program, PlutusData.constr(1, PlutusData.integer(1),
                PlutusData.constr(1, PlutusData.integer(2), end)));
        assertInvalid(program, PlutusData.constr(1, PlutusData.integer(1),
                PlutusData.constr(1, PlutusData.bytes(new byte[]{2}), end)));
        assertInvalid(program, PlutusData.constr(1, PlutusData.integer(1),
                PlutusData.constr(0, PlutusData.integer(99))));
    }

    @Test
    void spendingDatumAndRedeemerAreBothStrictIncludingLedgerOptionalDatum() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class StrictSpend {
                    record Datum(byte[] owner) {}
                    record Redeemer(BigInteger amount) {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        assertTrue(VM.evaluateWithArgs(program, List.of(spendingContext(
                PlutusData.constr(0, PlutusData.bytes(new byte[]{1})),
                PlutusData.constr(0, PlutusData.integer(2))))).isSuccess());
        assertFalse(VM.evaluateWithArgs(program, List.of(spendingContext(
                PlutusData.constr(4, PlutusData.bytes(new byte[]{1})),
                PlutusData.constr(0, PlutusData.integer(2))))).isSuccess());
        assertFalse(VM.evaluateWithArgs(program, List.of(spendingContext(
                PlutusData.constr(0, PlutusData.bytes(new byte[]{1})),
                PlutusData.constr(0, PlutusData.integer(2), PlutusData.integer(3))))).isSuccess());

        String optionalSource = source
                .replace("import java.math.BigInteger;", """
                        import java.math.BigInteger;
                        import java.util.Optional;""")
                .replace("Datum datum, Redeemer redeemer", "Optional<Datum> datum, Redeemer redeemer");
        var optionalProgram = new JulcCompiler(STDLIB).compile(optionalSource).program();
        assertTrue(VM.evaluateWithArgs(optionalProgram, List.of(spendingContextOptional(
                PlutusData.constr(1), PlutusData.constr(0, PlutusData.integer(2))))).isSuccess());
        assertFalse(VM.evaluateWithArgs(optionalProgram, List.of(spendingContextOptional(
                PlutusData.constr(0, PlutusData.constr(8, PlutusData.bytes(new byte[]{1}))),
                PlutusData.constr(0, PlutusData.integer(2))))).isSuccess());
    }

    @Test
    void canonicalBooleanAndUtf8AreCheckedWhenFieldsAreUnused() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class PrimitiveShapes {
                    record Redeemer(boolean enabled, String label) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        assertValid(program, PlutusData.constr(0, PlutusData.constr(0),
                PlutusData.bytes("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertValid(program, PlutusData.constr(0, PlutusData.constr(1),
                PlutusData.bytes("yes".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.constr(1, PlutusData.integer(0)), PlutusData.bytes(new byte[0])));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.constr(2), PlutusData.bytes(new byte[0])));
        assertInvalid(program, PlutusData.constr(0,
                PlutusData.constr(0), PlutusData.bytes(new byte[]{(byte) 0xc3, 0x28})));
    }

    @Test
    void mutualAndThroughContainerRecursionRejectMalformedValues() {
        String mutual = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class MutualBoundary {
                    sealed interface Left permits LeftEnd, ToRight {}
                    record LeftEnd() implements Left {}
                    record ToRight(Right next) implements Left {}
                    sealed interface Right permits RightEnd, ToLeft {}
                    record RightEnd() implements Right {}
                    record ToLeft(Left next) implements Right {}
                    @Entrypoint static boolean validate(Left redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var mutualProgram = new JulcCompiler(STDLIB).compile(mutual).program();
        assertValid(mutualProgram, PlutusData.constr(1,
                PlutusData.constr(1, PlutusData.constr(0))));
        assertInvalid(mutualProgram, PlutusData.constr(1,
                PlutusData.constr(1, PlutusData.constr(0, PlutusData.integer(1)))));

        String container = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.util.List;
                @MintingValidator class TreeBoundary {
                    record Tree(List<Tree> children) {}
                    @Entrypoint static boolean validate(Tree redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var treeProgram = new JulcCompiler(STDLIB).compile(container).program();
        assertValid(treeProgram, PlutusData.constr(0, PlutusData.list(
                PlutusData.constr(0, PlutusData.list()))));
        assertInvalid(treeProgram, PlutusData.constr(0, PlutusData.list(
                PlutusData.constr(0, PlutusData.list(), PlutusData.integer(1)))));
    }

    @Test
    void recordGuardAndProjectionShareRootDestruction() {
        String ignored = recordProjectionSource("return true;");
        String projected = recordProjectionSource(
                "return redeemer.value().compareTo(BigInteger.ZERO) >= 0;");
        PirTerm ignoredPir = new JulcCompiler(STDLIB).compileWithDetails(ignored).pirTerm();
        PirTerm projectedPir = new JulcCompiler(STDLIB).compileWithDetails(projected).pirTerm();
        assertEquals(countBuiltin(ignoredPir, DefaultFun.UnConstrData),
                countBuiltin(projectedPir, DefaultFun.UnConstrData),
                "using a top-level record field must reuse the checked root pair");
        assertEquals(countBuiltin(ignoredPir, DefaultFun.HeadList),
                countBuiltin(projectedPir, DefaultFun.HeadList),
                "using a top-level record field must reuse the checker's raw field binding");
        assertEquals(countBuiltin(ignoredPir, DefaultFun.TailList),
                countBuiltin(projectedPir, DefaultFun.TailList),
                "using a top-level record field must reuse the checker's remaining-field binding");
    }

    @Test
    void opaqueDataRemainsOpaqueAndVerificationMetadataDoesNotChangeUplc() {
        String plain = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;
                @MintingValidator class Opaque {
                    @Entrypoint static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        String annotated = plain.replace("@MintingValidator class Opaque", """
                @com.bloxbean.cardano.julc.verification.annotation.ControlledMint(
                    authority=\"00000000000000000000000000000000000000000000000000000000\",
                    tokenName=\"01\", quantity=\"1\")
                @MintingValidator class Opaque""");
        var compiler = new JulcCompiler(STDLIB);
        var plainProgram = compiler.compile(plain).program();
        var annotatedProgram = compiler.compile(annotated).program();
        assertArrayEquals(UplcFlatEncoder.encodeProgram(plainProgram),
                UplcFlatEncoder.encodeProgram(annotatedProgram));
        assertValid(plainProgram, PlutusData.integer(1));
        assertValid(plainProgram, PlutusData.constr(99, PlutusData.bytes(new byte[]{1})));
    }

    @Test
    void malformedBoundaryFailsBeforeUserTrace() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator class TraceOrder {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return Builtins.equalsData(
                            Builtins.trace(\"user-entrypoint\", Builtins.iData(redeemer.value())),
                            Builtins.iData(redeemer.value()));
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        EvalResult malformed = VM.evaluateWithArgs(program,
                List.of(mintingContext(PlutusData.constr(9, PlutusData.integer(1)))));
        assertFalse(malformed.isSuccess());
        assertFalse(malformed.traces().contains("user-entrypoint"));

        EvalResult canonical = VM.evaluateWithArgs(program,
                List.of(mintingContext(PlutusData.constr(0, PlutusData.integer(1)))));
        assertTrue(canonical.isSuccess());
        assertTrue(canonical.traces().contains("user-entrypoint"));
    }

    @Test
    void completedCompilerContainsNoExecutableLegacyBoundaryMode() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator class BoundaryDelta {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(STDLIB).compile(source).program();
        assertInvalid(program, PlutusData.constr(7, PlutusData.integer(1)));

        assertTrue(java.util.Arrays.stream(JulcCompiler.class.getDeclaredClasses())
                .noneMatch(type -> type.getSimpleName().equals("BoundarySemantics")));
        assertTrue(java.util.Arrays.stream(JulcCompiler.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("boundarySemantics")));
        assertTrue(java.util.Arrays.stream(JulcCompiler.class.getDeclaredConstructors())
                .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes()))
                .noneMatch(type -> type.getSimpleName().equals("BoundarySemantics")));
    }

    @Test
    void ambientSettingsCannotSelectLegacyBoundarySemantics() {
        String previous = System.getProperty("julc.boundary.semantics");
        try {
            System.setProperty("julc.boundary.semantics", "legacy");
            var program = new JulcCompiler(STDLIB).compile(
                    recordProjectionSource("return true;")).program();
            assertInvalid(program, PlutusData.constr(5, PlutusData.integer(1)));
        } finally {
            if (previous == null) System.clearProperty("julc.boundary.semantics");
            else System.setProperty("julc.boundary.semantics", previous);
        }
    }

    @Test
    void unsupportedDirectAndNestedBoundaryTypesFailAtJavaParameters() {
        String direct = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.types.JulcArray;
                import java.math.BigInteger;
                @MintingValidator class UnsupportedDirect {
                    @Entrypoint static boolean validate(
                            JulcArray<BigInteger> redeemer, ScriptContext ctx) { return true; }
                }
                """;
        var directError = assertThrows(CompilerException.class,
                () -> new JulcCompiler(STDLIB).compile(direct));
        assertTrue(directError.getMessage().contains("Unsupported strict boundary type ArrayType"));
        assertTrue(directError.diagnostics().getFirst().line() > 0);

        String nested = direct
                .replace("import java.math.BigInteger;", """
                        import java.math.BigInteger;
                        import java.util.List;""")
                .replace("JulcArray<BigInteger> redeemer",
                        "List<JulcArray<BigInteger>> redeemer");
        var nestedError = assertThrows(CompilerException.class,
                () -> new JulcCompiler(STDLIB).compile(nested));
        assertTrue(nestedError.getMessage().contains("Unsupported strict boundary type ArrayType"));
        assertTrue(nestedError.diagnostics().getFirst().line() > 0);
    }

    private static void assertValid(com.bloxbean.cardano.julc.core.Program program,
                                    PlutusData redeemer) {
        assertTrue(VM.evaluateWithArgs(program, List.of(mintingContext(redeemer))).isSuccess());
    }

    private static void assertInvalid(com.bloxbean.cardano.julc.core.Program program,
                                      PlutusData redeemer) {
        assertFalse(VM.evaluateWithArgs(program, List.of(mintingContext(redeemer))).isSuccess());
    }

    private static PlutusData mintingContext(PlutusData redeemer) {
        return PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
    }

    private static PlutusData spendingContext(PlutusData datum, PlutusData redeemer) {
        return spendingContextOptional(PlutusData.constr(0, datum), redeemer);
    }

    private static PlutusData spendingContextOptional(PlutusData optionalDatum, PlutusData redeemer) {
        return PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(1, PlutusData.integer(0), optionalDatum));
    }

    private static String recordProjectionSource(String body) {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MintingValidator class SharedBoundary {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        %s
                    }
                }
                """.formatted(body);
    }

    private static int countBuiltin(PirTerm term, DefaultFun target) {
        return switch (term) {
            case PirTerm.Builtin(var fun) -> fun == target ? 1 : 0;
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Error _ -> 0;
            case PirTerm.Let(_, var value, var body) ->
                    countBuiltin(value, target) + countBuiltin(body, target);
            case PirTerm.LetRec(var bindings, var body) -> bindings.stream()
                    .mapToInt(binding -> countBuiltin(binding.value(), target)).sum()
                    + countBuiltin(body, target);
            case PirTerm.Lam(_, _, var body) -> countBuiltin(body, target);
            case PirTerm.App(var function, var argument) ->
                    countBuiltin(function, target) + countBuiltin(argument, target);
            case PirTerm.IfThenElse(var condition, var yes, var no) ->
                    countBuiltin(condition, target) + countBuiltin(yes, target)
                            + countBuiltin(no, target);
            case PirTerm.DataConstr(_, _, var fields) -> fields.stream()
                    .mapToInt(field -> countBuiltin(field, target)).sum();
            case PirTerm.DataMatch(var scrutinee, var branches) ->
                    countBuiltin(scrutinee, target) + branches.stream()
                            .mapToInt(branch -> countBuiltin(branch.body(), target)).sum();
            case PirTerm.Trace(var message, var body) ->
                    countBuiltin(message, target) + countBuiltin(body, target);
        };
    }
}
