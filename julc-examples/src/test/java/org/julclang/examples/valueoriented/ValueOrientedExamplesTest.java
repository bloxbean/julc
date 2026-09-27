package org.julclang.examples.valueoriented;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.testkit.MethodEvaluator;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks every example on the Value-Oriented Contract Code page. The "after" examples in
 * {@link ValueOrientedExamples} are compiled to UPLC and evaluated; the "before" examples below are
 * compiled from the page's text and must be rejected. {@link #pageShowsTheTestedCode} fails when the
 * page and the tested code drift apart.
 */
class ValueOrientedExamplesTest {
    static final Path SOURCE_ROOT = Path.of("src/test/java");
    static final Path PAGE = Path.of("../docs/src/content/docs/best-practices/value-oriented-code.md");

    static final PlutusData ONLY = PlutusData.constr(0);

    static PlutusData ints(long... values) {
        return PlutusData.list(Arrays.stream(values).mapToObj(PlutusData::integer).toArray(PlutusData[]::new));
    }

    static PlutusData deposit(long base) {
        return PlutusData.constr(1, PlutusData.integer(base));
    }

    static BigInteger integer(String method, PlutusData... args) {
        return MethodEvaluator.evaluateInteger(ValueOrientedExamples.class, SOURCE_ROOT, method, args);
    }

    static boolean bool(String method, PlutusData... args) {
        return MethodEvaluator.evaluateBoolean(ValueOrientedExamples.class, SOURCE_ROOT, method, args);
    }

    static EvalResult.Success success(String method, PlutusData... args) {
        var result = MethodEvaluator.evaluateRaw(ValueOrientedExamples.class, SOURCE_ROOT, method, args);
        return assertInstanceOf(EvalResult.Success.class, result, result.toString());
    }

    // --- The "after" examples, evaluated on the VM ---

    @Test
    void conditionalInitializer() {
        assertEquals(BigInteger.valueOf(3), integer("countPositive", ints(1, 2, 3)));
        assertEquals(BigInteger.ONE, integer("countPositive", ints(-1, 0, 2)));
        assertEquals(BigInteger.ZERO, integer("countPositive", ints()));
        assertEquals(BigInteger.valueOf(100), integer("limitFor", PlutusData.constr(1)));
        assertEquals(BigInteger.TEN, integer("limitFor", PlutusData.constr(0)));
    }

    @Test
    void switchArmYieldsItsResult() {
        assertEquals(BigInteger.valueOf(6), integer("armTotal", ONLY, ints(1, 2, 3)));
        assertEquals(BigInteger.ZERO, integer("armTotal", ONLY, ints()));
        assertEquals(BigInteger.valueOf(9), integer("armTotal", deposit(9), ints(1, 2, 3)));
    }

    @Test
    void copiedPatternBindingAccumulates() {
        assertEquals(BigInteger.valueOf(16), integer("depositTotal", deposit(10), ints(1, 2, 3)));
        assertEquals(BigInteger.TEN, integer("depositTotal", deposit(10), ints()));
        assertEquals(BigInteger.ZERO, integer("depositTotal", ONLY, ints(1, 2, 3)));
    }

    @Test
    void helperReturnsItsValue() {
        assertTrue(bool("paysFees", ints(1, 2, 3), PlutusData.integer(6)));
        assertFalse(bool("paysFees", ints(1, 2, 3), PlutusData.integer(5)));
        assertTrue(bool("paysFees", ints(), PlutusData.integer(0)));
    }

    @Test
    void listOperations() {
        assertTrue(bool("anyAbove", ints(1, 5, 3), PlutusData.integer(4)));
        assertFalse(bool("anyAbove", ints(1, 2, 4), PlutusData.integer(4)));
        assertFalse(bool("anyAbove", ints(), PlutusData.integer(4)));

        assertTrue(bool("allPresent", ints(1, 3), ints(3, 2, 1)));
        assertFalse(bool("allPresent", ints(1, 4), ints(3, 2, 1)));
        assertTrue(bool("allPresent", ints(), ints()));

        assertTrue(bool("anyDoubledAbove", ints(1, 3), PlutusData.integer(5)));
        assertFalse(bool("anyDoubledAbove", ints(1, 2), PlutusData.integer(4)));
    }

    @Test
    void filterAndLoopAgreeButCostDifferently() {
        PlutusData xs = ints(1, 5, 3, 7, 9, 2, 8, 4, 6, 10);
        PlutusData limit = PlutusData.integer(4);
        var filtered = success("countAbove", xs, limit);
        var looped = success("countAboveLoop", xs, limit);
        assertEquals(filtered.resultTerm(), looped.resultTerm());
        assertEquals(BigInteger.valueOf(6), integer("countAbove", xs, limit));
        assertEquals(BigInteger.ZERO, integer("countAboveLoop", ints(), limit));
        // The page states that filter(...).size() costs more than the loop here. If this flips,
        // update the "List operations" section.
        long filterCpu = filtered.consumed().cpuSteps();
        long loopCpu = looped.consumed().cpuSteps();
        assertTrue(filterCpu > 2 * loopCpu, "filter " + filterCpu + " vs loop " + loopCpu);
    }

    @Test
    void accumulatorLoop() {
        assertTrue(bool("withinCap", ints(1, 2, 3), PlutusData.integer(6)));
        assertFalse(bool("withinCap", ints(1, 2, 3), PlutusData.integer(5)));
        assertFalse(bool("withinCap", ints(), PlutusData.integer(5)));
    }

    // --- The "before" examples, as the page shows them, must be rejected ---

    static final String TYPES = """
            sealed interface Action permits Only, Deposit {}
            record Only() implements Action {}
            record Deposit(BigInteger base) implements Action {}
            """;

    /** Snippet, method to compile, and the start of the expected error message. */
    record Before(String snippet, String method, String error) {}

    static final Map<String, Before> BEFORE = new LinkedHashMap<>();
    static {
        BEFORE.put("conditional-update-in-loop", new Before("""
                static BigInteger countPositive(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = BigInteger.ZERO;
                        if (x.compareTo(BigInteger.ZERO) > 0) {
                            step = BigInteger.ONE;
                        }
                        acc = acc.add(step);
                    }
                    return acc;
                }
                """, "countPositive", "Conditional update to loop-body local 'step' is not supported"));
        BEFORE.put("conditional-update", new Before("""
                static BigInteger limitFor(boolean vip) {
                    BigInteger limit = BigInteger.TEN;
                    if (vip) {
                        limit = BigInteger.valueOf(100);
                    }
                    return limit;
                }
                """, "limitFor", "Unsupported expression: AssignExpr"));
        BEFORE.put("switch-arm-update", new Before("""
                static BigInteger armTotal(Action action, JulcList<BigInteger> xs) {
                    BigInteger total = BigInteger.ZERO;
                    BigInteger ignored = switch (action) {
                        case Only o -> {
                            for (var x : xs) {
                                total = total.add(x);
                            }
                            yield BigInteger.ZERO;
                        }
                        case Deposit d -> BigInteger.ZERO;
                    };
                    return total;
                }
                """, "armTotal", "Switch-expression arm cannot update enclosing variable 'total'"));
        BEFORE.put("pattern-reassignment", new Before("""
                static BigInteger depositTotal(Action action, JulcList<BigInteger> xs) {
                    return switch (action) {
                        case Deposit d -> {
                            for (var x : xs) {
                                d = new Deposit(d.base().add(x));
                            }
                            yield d.base();
                        }
                        case Only o -> BigInteger.ZERO;
                    };
                }
                """, "depositTotal", "Reassignment of switch case-pattern variable 'd' is not supported"));
        BEFORE.put("shared-field", new Before("""
                static BigInteger fee = BigInteger.ZERO;

                static boolean feeCovered(BigInteger paid) {
                    return paid.compareTo(fee) >= 0;
                }

                static boolean paysFees(JulcList<BigInteger> fees, BigInteger paid) {
                    for (var f : fees) {
                        fee = fee.add(f);
                    }
                    return feeCovered(paid);
                }
                """, "paysFees", "JULC0056"));
        BEFORE.put("map-chain", new Before("""
                static boolean anyDoubledAbove(JulcList<BigInteger> xs, BigInteger limit) {
                    return xs.map(x -> x.multiply(BigInteger.TWO)).any(y -> y.compareTo(limit) > 0);
                }
                """, "anyDoubledAbove", "JULC0055"));
    }

    @Test
    void beforeExamplesAreRejected() {
        for (var entry : BEFORE.entrySet()) {
            Before before = entry.getValue();
            String source = """
                    import java.math.BigInteger;
                    import org.julclang.core.types.JulcList;
                    class Before {
                    """ + TYPES + before.snippet() + "}\n";
            var error = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, before.method()),
                    entry.getKey());
            String reported = error.getMessage() + " " + error.diagnostics();
            assertTrue(reported.contains(before.error()), entry.getKey() + ": " + reported);
        }
    }

    // --- any and all check every element (a current limitation the page describes) ---

    static final String ANY_EVERY_ELEMENT = """
            static boolean anyLargeShare(JulcList<BigInteger> parts) {
                return parts.any(p -> BigInteger.valueOf(100).divide(p).compareTo(BigInteger.TEN) > 0);
            }
            """;

    static final String ANY_GUARDED = """
            static boolean anyLargeShare(JulcList<BigInteger> parts) {
                return parts.any(p -> p.signum() > 0
                        && BigInteger.valueOf(100).divide(p).compareTo(BigInteger.TEN) > 0);
            }
            """;

    static EvalResult evaluateSnippet(String snippet, String method, PlutusData... args) {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                class Snippet {
                """ + snippet + "}\n";
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, method);
        return org.julclang.testkit.ValidatorTest.evaluate(compiled.program(), args);
    }

    @Test
    void anyChecksEveryElement() {
        // Java's any stops at 5 (100 / 5 = 20 > 10) and returns true. The compiled any also
        // evaluates the predicate for 0, so the script fails.
        assertFalse(evaluateSnippet(ANY_EVERY_ELEMENT, "anyLargeShare", ints(5, 0)).isSuccess());
        var guarded = evaluateSnippet(ANY_GUARDED, "anyLargeShare", ints(5, 0));
        assertInstanceOf(EvalResult.Success.class, guarded, guarded.toString());
        assertEquals(new org.julclang.core.Term.Const(org.julclang.core.Constant.bool(true)),
                ((EvalResult.Success) guarded).resultTerm());
    }

    // --- The page shows exactly the tested code ---

    @Test
    void pageShowsTheTestedCode() throws IOException {
        String page = Files.readString(PAGE);
        List<String> snippets = new ArrayList<>();
        snippets.addAll(regions(SOURCE_ROOT.resolve("org/julclang/examples/valueoriented/ValueOrientedExamples.java")));
        snippets.addAll(regions(SOURCE_ROOT.resolve("org/julclang/examples/valueoriented/PaymentGateValidator.java")));
        snippets.addAll(regions(SOURCE_ROOT.resolve("org/julclang/examples/valueoriented/PaymentGateValidatorTest.java")));
        snippets.add(TYPES);
        BEFORE.values().forEach(before -> snippets.add(before.snippet()));
        snippets.add(ANY_EVERY_ELEMENT);
        snippets.add(ANY_GUARDED);
        assertEquals(22, snippets.size(), "regions, types, before examples and the any examples");
        for (String snippet : snippets)
            assertTrue(page.contains(snippet), "The page does not show this tested code:\n" + snippet);
    }

    /** The text between {@code // region name} and {@code // endregion}, with common indentation removed. */
    static List<String> regions(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        List<String> current = null;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("// region ")) {
                current = new ArrayList<>();
            } else if (trimmed.equals("// endregion")) {
                found.add(dedent(current));
                current = null;
            } else if (current != null) {
                current.add(line);
            }
        }
        assertNull(current, "unterminated region in " + file);
        return found;
    }

    static String dedent(List<String> lines) {
        int indent = lines.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line.isBlank() ? "" : line.substring(indent)).append('\n');
        return text.toString();
    }
}
