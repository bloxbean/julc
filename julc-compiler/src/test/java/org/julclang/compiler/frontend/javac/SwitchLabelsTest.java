package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.frontend.javac.subset.SwitchRules;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A label nothing lowers is rejected: a second label {@code JULC0075}, one that is no pattern {@code JULC0076} (ADR-068 M5). {@code -source 21} keeps a second pattern out of a program and the platform keeps out an
 * {@code enum}, so no source reaches this today, and the tests hand {@link SwitchRules} what javac would build: real trees,
 * with the labels of a case replaced. Neither of those is a rule of JuLC's, so the rule is held here.
 */
class SwitchLabelsTest {

    private static final JavacProgram PROGRAM = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("""
            class P {
                sealed interface S permits A, B { }
                record A() implements S { }
                record B() implements S { }
                static long patterns(S s) { return switch (s) { case A a -> 1; case B b -> 2; }; }
                static long fallback(S s) { return switch (s) { default -> 1; }; }
                static long constant(int i) { return switch (i) { case 1 -> 1; default -> 2; }; }
            }
            """)), List.of());

    private static final List<SwitchExpressionTree> SWITCHES = switches();
    private static final SwitchExpressionTree PATTERNS = SWITCHES.get(0);
    private static final SwitchExpressionTree FALLBACK = SWITCHES.get(1);
    private static final SwitchExpressionTree CONSTANT = SWITCHES.get(2);

    private static List<SwitchExpressionTree> switches() {
        var found = new ArrayList<SwitchExpressionTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
                found.add(tree);
                return super.visitSwitchExpression(tree, unused);
            }
        }.scan(PROGRAM.roots().getFirst(), null);
        return found;
    }

    private static CaseLabelTree label(SwitchExpressionTree tree, int entry) {
        return tree.getCases().get(entry).getLabels().getFirst();
    }

    /** {@code tree}, answering {@code method} with {@code answer}. */
    @SuppressWarnings("unchecked")
    private static <T> T with(Class<T> type, T tree, String method, Object answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, called, arguments) -> {
            if (called.getName().equals(method)) return answer;
            try {
                return called.invoke(tree, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    /** {@code tree}, whose one case has {@code labels}. */
    private static SwitchExpressionTree withLabels(SwitchExpressionTree tree, CaseLabelTree... labels) {
        var entry = with(CaseTree.class, tree.getCases().getFirst(), "getLabels", List.of(labels));
        return with(SwitchExpressionTree.class, tree, "getCases", List.of(entry));
    }

    private static String stop(SwitchExpressionTree tree) {
        var rejection = assertThrows(CompilerException.class, () -> new SwitchRules(PROGRAM.facts()).check(tree, null, null));
        return rejection.diagnostics().getFirst().code();
    }

    @Test
    void aSecondPatternStops() {
        assertEquals("JULC0075", stop(withLabels(PATTERNS, label(PATTERNS, 0), label(PATTERNS, 1))));
    }

    @Test
    void aSecondLabelStopsAfterADefaultToo() {
        assertEquals("JULC0075", stop(withLabels(FALLBACK, label(FALLBACK, 0), label(PATTERNS, 1))));
    }

    @Test
    void aConstantLabelStops() {
        assertEquals("JULC0076", stop(withLabels(PATTERNS, label(CONSTANT, 0))));
    }

    @Test
    void oneDefaultGoesThrough() {
        var model = new SwitchRules(PROGRAM.facts()).check(FALLBACK, null, null);
        assertTrue(model.arms().isEmpty());
        assertSame(FALLBACK.getCases().getFirst(), model.defaultEntry());
    }
}
