package org.julclang.core.text;

import org.julclang.core.DeepTerms;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UplcPrinter} needs no Java stack per level of a term and prints the text it printed before (issue #327). */
class DeepUplcPrinterTest {

    @Test
    void printsDeepTermsInEveryChildPositionOnASmallStack() {
        for (var position : DeepTerms.Position.values()) {
            var term = DeepTerms.spine(100_000, position);
            String text = DeepTerms.onSmallStack(() -> UplcPrinter.print(Program.plutusV3(term)));
            assertTrue(text.startsWith("(program 1.1.0 "), position.toString());
            assertTrue(text.length() > 100_000, position.toString());
        }
    }

    @Test
    void printsWhatTheRecursivePrinterPrinted() {
        var random = new Random(3273);
        for (int i = 0; i < 5_000; i++) {
            var term = DeepTerms.random(random, 1 + random.nextInt(60));
            var expected = new StringBuilder();
            recursivePrint(term, expected);
            assertEquals(expected.toString(), UplcPrinter.print(term), "term " + i);
        }
    }

    @Test
    void namesAreSanitizedAsBefore() {
        var term = new Term.Lam("a.b$c", new Term.Apply(DeepTerms.var(1), new Term.Lam("1x", DeepTerms.var(1))));
        var expected = new StringBuilder();
        recursivePrint(term, expected);
        assertEquals(expected.toString(), UplcPrinter.print(term));
    }

    /** The recursive form this change replaced, kept as the reference. */
    private static void recursivePrint(Term term, StringBuilder sb) {
        switch (term) {
            case Term.Var v -> sb.append(UplcPrinter.sanitizeName(v.name().name()));
            case Term.Lam l -> {
                sb.append("(lam ").append(UplcPrinter.sanitizeName(l.paramName())).append(' ');
                recursivePrint(l.body(), sb);
                sb.append(')');
            }
            case Term.Apply a -> {
                sb.append('[');
                recursivePrint(a.function(), sb);
                sb.append(' ');
                recursivePrint(a.argument(), sb);
                sb.append(']');
            }
            case Term.Force f -> {
                sb.append("(force ");
                recursivePrint(f.term(), sb);
                sb.append(')');
            }
            case Term.Delay d -> {
                sb.append("(delay ");
                recursivePrint(d.term(), sb);
                sb.append(')');
            }
            case Term.Const c -> {
                sb.append("(con ");
                UplcPrinter.printConstant(c.value(), sb);
                sb.append(')');
            }
            case Term.Builtin b -> sb.append("(builtin ").append(UplcPrinter.builtinName(b.fun())).append(')');
            case Term.Error ignored -> sb.append("(error)");
            case Term.Constr c -> {
                sb.append("(constr ").append(Long.toUnsignedString(c.tag()));
                for (var field : c.fields()) {
                    sb.append(' ');
                    recursivePrint(field, sb);
                }
                sb.append(')');
            }
            case Term.Case cs -> {
                sb.append("(case ");
                recursivePrint(cs.scrutinee(), sb);
                for (var branch : cs.branches()) {
                    sb.append(' ');
                    recursivePrint(branch, sb);
                }
                sb.append(')');
            }
        }
    }
}
