package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A ledger type's {@code fromPlutusData} on {@code frontend=JAVAC} accepts exactly what the JVM's decoder accepts, at the
 * edges where that decoder is laxer than the strict boundary: a field more on a constructor, fields on a {@code Bool} and on
 * an empty {@code Optional}; and refuses what it refuses, a present {@code Optional} of two fields among them. The JVM's
 * answer is computed here, by the decoder itself, not written down.
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
                new Input("Value", "a map of maps", new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(0),
                        new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(0), integer(5)))))))),
                new Input("Value", "an amount that is bytes", new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(0),
                        new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(0), bytes(1)))))))),
                new Input("Value", "a policy of no map", new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(0), integer(5))))),
                new Input("Value", "a list", new PlutusData.ListData(List.of())));
    }

    private static boolean jvmDecodes(Input input) throws ReflectiveOperationException {
        var method = Class.forName("org.julclang.ledger." + input.type()).getMethod("fromPlutusData", PlutusData.class);
        try {
            method.invoke(null, input.data());
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) return false;
            throw e;
        }
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

    @ParameterizedTest
    @MethodSource("inputs")
    void theScriptDecodesWhatTheJvmDecodes(Input input) throws ReflectiveOperationException {
        boolean jvm = jvmDecodes(input);
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                    .setFrontend(CompilerFrontend.JAVAC).setOptimizationLevel(level)).compileMethod(source(input.type()), "m").program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(input.data()));
            assertEquals(jvm, result instanceof EvalResult.Success, () -> input + " at " + level + ": " + result);
        }
    }
}
