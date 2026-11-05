package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.text.UplcPrinter;
import org.julclang.ledger.TxOutRef;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ledger type's {@code fromPlutusData} on {@code frontend=JAVAC} accepts exactly the Data whose shape is the type's: what the
 * JVM's decoder refuses fails, and so does what it decodes by ignoring part of it, so a value the script accepts is the JVM's.
 * The expected answer is computed here, from the JVM's decoder and its encoder, not written down: the script accepts where the
 * decoder returns a value whose Data is the input.
 */
class JavacLedgerFromDataTest {

    private record Input(String type, String label, PlutusData data) {
        @Override
        public String toString() {
            return type + ": " + label;
        }
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static PlutusData bytes(int n) {
        return new PlutusData.BytesData(new byte[n]);
    }

    private static PlutusData integer(long n) {
        return new PlutusData.IntData(BigInteger.valueOf(n));
    }

    private static final PlutusData TRUE = constr(1);


    static Stream<Input> inputs() {
        var hash = bytes(28);
        var credential = constr(0, hash);
        var address = constr(0, credential, constr(1));
        var bound = constr(0, constr(1, integer(5)), TRUE);
        return Stream.of(
                new Input("Credential", "well-formed", credential),
                new Input("Credential", "a field more", constr(0, hash, integer(1))),
                new Input("Credential", "no field", constr(0)),
                new Input("Credential", "a tag past the last", constr(2, hash)),
                new Input("Credential", "a field of another kind", constr(1, integer(1))),
                new Input("Credential", "no constructor", hash),
                new Input("PubKeyHash", "bytes", hash),
                new Input("PubKeyHash", "an integer", integer(3)),
                new Input("IntervalBound", "well-formed", bound),
                new Input("IntervalBound", "a Bool with a field", constr(0, constr(1, integer(5)), constr(1, integer(9)))),
                new Input("IntervalBound", "a Bool of tag 2", constr(0, constr(1, integer(5)), constr(2))),
                new Input("IntervalBound", "a field more", constr(0, constr(1, integer(5)), TRUE, integer(0))),
                new Input("Address", "well-formed, no staking credential", address),
                new Input("Address", "an empty Optional with a field", constr(0, credential, constr(1, integer(7)))),
                new Input("Address", "a present Optional of two fields",
                        constr(0, credential, constr(0, constr(0, credential), integer(7)))),
                new Input("Address", "an Optional of tag 2", constr(0, credential, constr(2))),
                new Input("Address", "a list", new PlutusData.ListData(List.of())),
                new Input("TxOutRef", "well-formed", constr(0, bytes(32), integer(0))),
                new Input("TxOutRef", "a field more", constr(0, bytes(32), integer(0), integer(1))));
    }

    /** Whether the JVM's decoder returns a value, and that value's Data is the input: nothing was ignored. */
    private static boolean jvmDecodesExactly(String type, PlutusData data) throws ReflectiveOperationException {
        var decoder = Class.forName("org.julclang.ledger." + type).getMethod("fromPlutusData", PlutusData.class);
        Object value;
        try {
            value = decoder.invoke(null, data);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) return false;
            throw e;
        }
        return value.getClass().getMethod("toPlutusData").invoke(value).equals(data);
    }

    private static EvalResult run(String source, OptimizationLevel level, PlutusData... arguments) {
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level)).compileMethod(source, "m").program();
        return CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(arguments));
    }

    private static String source(String type) {
        return """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.%1$s;

                class C {
                    static boolean m(PlutusData d) {
                        %1$s x = %1$s.fromPlutusData(d);
                        return true;
                    }
                }
                """.formatted(type);
    }

    /** What the value holds once decoded: a hash's bytes, the JVM's {@code hash()}. */
    @Test
    void aHashFromDataIsItsBytes() {
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.PubKeyHash;

                class C {
                    static byte[] m(PlutusData d) {
                        return PubKeyHash.fromPlutusData(d).hash();
                    }
                }
                """;
        var hash = new byte[]{1, 2, 3};
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                    .setOptimizationLevel(level)).compileMethod(source, "m").program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(new PlutusData.BytesData(hash)));
            var success = assertInstanceOf(EvalResult.Success.class, result, result::toString);
            String printed = UplcPrinter.print(success.resultTerm());
            assertTrue(printed.contains("bytestring") && printed.contains("#010203"), () -> level + ": " + printed);
        }
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void theScriptAcceptsExactlyTheTypesShape(Input input) throws ReflectiveOperationException {
        boolean exact = jvmDecodesExactly(input.type(), input.data());
        for (var level : OptimizationLevel.values()) {
            var result = run(source(input.type()), level, input.data());
            assertEquals(exact, result instanceof EvalResult.Success, () -> input + " at " + level + ": " + result);
        }
    }

    /**
     * Two decoded values compare as the JVM's do wherever the script accepts both, and Data with a field the type has not
     * is refused, so no comparison reads it.
     */
    @Test
    void decodedValuesCompareAsTheJvmsOrTheDataIsRefused() throws ReflectiveOperationException {
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.TxOutRef;

                class C {
                    static boolean m(PlutusData d, PlutusData other) {
                        TxOutRef r = TxOutRef.fromPlutusData(d);
                        return !r.equals(TxOutRef.fromPlutusData(other));
                    }
                }
                """;
        var other = constr(0, bytes(32), integer(0));
        for (var d : List.of(constr(0, bytes(32), integer(0)), constr(0, bytes(32), integer(1)), constr(0, bytes(32), integer(0), integer(1)))) {
            boolean exact = jvmDecodesExactly("TxOutRef", d);
            boolean jvm = !TxOutRef.fromPlutusData(d).equals(TxOutRef.fromPlutusData(other));
            for (var level : OptimizationLevel.values()) {
                var result = run(source, level, d, other);
                if (!exact) {
                    assertInstanceOf(EvalResult.Failure.class, result, () -> d + " at " + level);
                } else {
                    var success = assertInstanceOf(EvalResult.Success.class, result, () -> d + " at " + level);
                    assertTrue(UplcPrinter.print(success.resultTerm()).contains(jvm ? "True" : "False"), () -> d + " at " + level);
                }
            }
        }
    }

    /** A program has one dispatcher of shape checks, whatever the number of decodes and of their types. */
    @Test
    void decodesShareOneDispatcher() {
        String one = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.Address;
                import org.julclang.ledger.TxOutRef;

                class C {
                    static boolean m(PlutusData d, PlutusData e) {
                        Address a = Address.fromPlutusData(d);
                        TxOutRef r = TxOutRef.fromPlutusData(e);
                        return true;
                    }
                }
                """;
        String two = one.replace("TxOutRef r = TxOutRef.fromPlutusData(e);",
                "TxOutRef r = TxOutRef.fromPlutusData(e); Address b = Address.fromPlutusData(e); TxOutRef s = TxOutRef.fromPlutusData(d);");
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            int once = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(one, "m").scriptSizeBytes();
            int twice = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(two, "m").scriptSizeBytes();
            assertTrue(twice - once < 40, () -> level + ": " + once + " then " + twice + " bytes");
        }
    }

    /** A hash's decode is its check: the program is the bytes' decode, as {@code Builtins.unBData} is. */
    @Test
    void aHashsDecodeIsItsCheck() {
        String fromData = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.PubKeyHash;
                class C { static byte[] m(PlutusData d) { return PubKeyHash.fromPlutusData(d).hash(); } }
                """;
        String unBData = """
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.Builtins;
                class C { static byte[] m(PlutusData d) { return Builtins.unBData(d); } }
                """;
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            assertEquals(new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(unBData, "m").scriptSizeBytes(),
                    new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(fromData, "m").scriptSizeBytes(), level::toString);
        }
    }

    /**
     * In an entry point whose redeemer the strict boundary checks, a shape check is the program's, not the redeemer's: Data
     * of the redeemer's shape and not the decoded type's fails.
     */
    @Test
    void aShapeCheckBesideARedeemersCheckIsItsOwn() {
        String source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.Credential;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MintingValidator;

                @MintingValidator
                class Checked {
                    record Redeemer(PlutusData credential) {}

                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        Credential c = Credential.fromPlutusData(redeemer.credential());
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compile(source).program();
        for (var credential : List.of(constr(0, bytes(28)), constr(0, integer(5)))) {
            var context = constr(0, integer(0), constr(0, credential), constr(0, bytes(28)));
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(context));
            boolean expected = credential.equals(constr(0, bytes(28)));
            assertEquals(expected, result instanceof EvalResult.Success, () -> credential + ": " + result);
        }
    }

    /** A ledger type whose shape holds a map, a {@code Value} among them, is not decoded on-chain. */
    @ParameterizedTest
    @ValueSource(strings = {"Value", "TxOut", "TxInInfo", "TxInfo", "ScriptContext", "Committee", "GovernanceAction"})
    void aTypeThatHoldsAMapIsNotDecoded(String type) {
        var rejection = assertThrows(CompilerException.class, () -> run(source(type), OptimizationLevel.DEFAULT));
        assertEquals("JULC0128", rejection.diagnostics().getFirst().code(), rejection::getMessage);
    }
}
