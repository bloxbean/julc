package org.julclang.compiler.imports;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.JulcVm;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Issue #328, ADR-075: what a Java program compiles to does not depend on the order of its {@code import} declarations,
 * on the order of the library sources it is compiled with, or on the package or name of a library class. Each of these
 * used to move the order of the library methods' bindings, and so the script hash.
 */
class ImportOrderInvarianceTest {

    private static final List<String> PACKAGES = List.of("aaa.x", "org.julclang.m", "zzz.y", "com.example.deep.pkg");

    private static JulcVm vm;

    @BeforeAll
    static void vm() {
        vm = CompilerTestVm.pv11();
    }

    /** The imports of the validator and of the method source, {@code PKG} and {@code HELPER} still to fill in. */
    private static final List<String> IMPORTS = List.of(
            "import java.math.BigInteger;",
            "import org.julclang.core.PlutusData;",
            "import org.julclang.ledger.ScriptContext;",
            "import org.julclang.ledger.TxInfo;",
            "import org.julclang.stdlib.annotation.Entrypoint;",
            "import org.julclang.stdlib.annotation.SpendingValidator;",
            "import org.julclang.stdlib.lib.AddressLib;",
            "import org.julclang.stdlib.lib.ContextsLib;",
            "import org.julclang.stdlib.lib.MathLib;",
            "import org.julclang.stdlib.lib.ValuesLib;",
            "import PKG.HELPER;",
            "import PKG.Rules;");

    private static String fill(String text, String pkg, String helper) {
        return text.replace("PKG", pkg).replace("HELPER", helper);
    }

    private static String imports(List<String> imports, String pkg, String helper) {
        var text = new StringBuilder();
        for (var line : imports) text.append(fill(line, pkg, helper)).append('\n');
        return text.toString();
    }

    private static String validator(List<String> imports, String pkg, String helper) {
        return "package " + pkg + ".v;\n\n" + imports(imports, pkg, helper) + fill("""

                @SpendingValidator
                class Probe {
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        TxInfo tx = ctx.txInfo();
                        boolean signed = ContextsLib.signedBy(tx, HELPER.owner());
                        BigInteger lovelace = ValuesLib.lovelaceOf(tx.outputs().head().value());
                        boolean script = AddressLib.isScriptAddress(tx.outputs().head().address());
                        return script && signed && Rules.accepts(tx.fee()) && lovelace.compareTo(HELPER.limit()) > 0;
                    }
                }
                """, pkg, helper);
    }

    private static String method(List<String> imports, String pkg, String helper) {
        return "package " + pkg + ".v;\n\n" + imports(imports, pkg, helper) + fill("""

                class Calc {
                    static BigInteger f(BigInteger x) {
                        BigInteger capped = MathLib.min(x, HELPER.limit());
                        return Rules.accepts(x) ? HELPER.sum(capped).add(MathLib.abs(x)) : MathLib.max(x, BigInteger.ONE);
                    }
                }
                """, pkg, helper);
    }

    private static String helper(String pkg, String helper) {
        return fill("""
                package PKG;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class HELPER {
                    public static byte[] owner() { return new byte[]{1, 2, 3}; }
                    public static boolean positive(BigInteger x) { return x.signum() > 0; }
                    public static BigInteger limit() { return BigInteger.valueOf(2000000); }
                    public static BigInteger sum(BigInteger n) {
                        if (n.signum() <= 0) { return BigInteger.ZERO; }
                        return n.add(sum(n.subtract(BigInteger.ONE)));
                    }
                }
                """, pkg, helper);
    }

    private static String rules(String pkg, String helper) {
        return fill("""
                package PKG;

                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.stdlib.lib.MathLib;

                @OnchainLibrary
                public class Rules {
                    public static boolean accepts(BigInteger fee) {
                        return HELPER.positive(fee) && MathLib.abs(fee).compareTo(BigInteger.valueOf(10000)) > 0;
                    }
                }
                """, pkg, helper);
    }

    /** The class path's stdlib sources the source resolves, then the user libraries, in the given order. */
    private static List<String> libraries(String source, List<String> user, Random random) {
        var available = LibrarySourceResolver.scanClasspathSources(ImportOrderInvarianceTest.class.getClassLoader());
        var all = new ArrayList<>(LibrarySourceResolver.resolve(source, available));
        all.addAll(user);
        Collections.shuffle(all, random);
        return all;
    }

    private static CompileResult compile(boolean asMethod, OptimizationLevel level, String source, List<String> libraries) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level));
        var result = asMethod ? compiler.compileMethod(source, "f", libraries) : compiler.compile(source, libraries);
        assertFalse(result.hasErrors(), () -> String.valueOf(result.diagnostics()));
        return result;
    }

    private static String bytes(CompileResult result) {
        return HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(result.program()));
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void importOrderLibraryOrderPackageAndClassNameDoNotReachTheScript(OptimizationLevel level) {
        for (boolean asMethod : new boolean[]{false, true}) {
            var random = new Random(328 + level.ordinal());
            String expected = null;
            int variants = 0;
            for (String pkg : PACKAGES) {
                for (String helper : List.of("Helper", "Assist")) {
                    for (int shuffle = 0; shuffle < 3; shuffle++) {
                        var imports = new ArrayList<>(IMPORTS);
                        Collections.shuffle(imports, random);
                        String source = asMethod ? method(imports, pkg, helper) : validator(imports, pkg, helper);
                        var libraries = libraries(source, List.of(helper(pkg, helper), rules(pkg, helper)), random);
                        String actual = bytes(compile(asMethod, level, source, libraries));
                        if (expected == null) expected = actual;
                        assertEquals(expected, actual, (asMethod ? "method" : "validator") + " at " + level + " in " + pkg
                                + " with " + helper + ", imports " + imports);
                        variants++;
                    }
                }
            }
            assertEquals(PACKAGES.size() * 2 * 3, variants);
        }
    }

    @ParameterizedTest
    @EnumSource(OptimizationLevel.class)
    void theReorderedProgramComputesWhatJavaDoes(OptimizationLevel level) {
        var random = new Random(3281);
        var imports = new ArrayList<>(IMPORTS);
        Collections.shuffle(imports, random);
        String source = method(imports, "com.example.deep.pkg", "Assist");
        var result = compile(true, level, source,
                libraries(source, List.of(helper("com.example.deep.pkg", "Assist"), rules("com.example.deep.pkg", "Assist")), random));
        for (long x : new long[]{-20_000, -5, 0, 5, 9_999, 10_001, 12_345}) {
            var value = BigInteger.valueOf(x);
            var evaluation = vm.evaluateWithArgs(result.program(), List.of(PlutusData.integer(value)));
            assertEquals(expected(value), TermExtractor.extractInteger(TermExtractor.extractResultTerm(evaluation)),
                    "f(" + x + ") at " + level);
        }
    }

    /** {@code Calc.f} in Java. */
    private static BigInteger expected(BigInteger x) {
        var limit = BigInteger.valueOf(2_000_000);
        var capped = x.min(limit);
        boolean accepts = x.signum() > 0 && x.abs().compareTo(BigInteger.valueOf(10_000)) > 0;
        if (!accepts) return x.max(BigInteger.ONE);
        var sum = BigInteger.ZERO;
        for (var n = capped; n.signum() > 0; n = n.subtract(BigInteger.ONE)) sum = sum.add(n);
        return sum.add(x.abs());
    }
}
