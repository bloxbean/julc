package org.julclang.core.text;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UplcPrettyPrinterTest {

    @Test
    void decodedProgramGetsUniqueScopeCorrectNames() {
        // (lam a (lam b [a b (lam c [c a])])) — decoded from FLAT, every binder is "i0"
        var program = Program.plutusV3(Term.lam("a", Term.lam("b",
                Term.apply(Term.apply(Term.var(2), Term.var(1)),
                        Term.lam("c", Term.apply(Term.var(1), Term.var(3)))))));
        var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(program));

        var named = UplcNames.uniquify(decoded);
        var text = UplcPrettyPrinter.print(named).text();

        assertEquals("(program 1.1.0 (lam i_1 (lam i_2 [i_1 i_2 (lam i_3 [i_3 i_1])])))", text);
        assertArrayEquals(UplcFlatEncoder.encodeProgram(program), UplcFlatEncoder.encodeProgram(named));
    }

    @Test
    void meaningfulNamesAreKeptAndDeduplicated() {
        var program = Program.plutusV3(Term.lam("ctx", Term.apply(
                Term.lam("x", Term.var(1)),
                Term.lam("x", Term.lam("ctx", Term.var(2))))));

        var text = UplcPrettyPrinter.print(UplcNames.uniquify(program)).text();

        assertEquals("(program 1.1.0 (lam ctx [(lam x x) (lam x_2 (lam ctx_2 x_2))]))", text);
    }

    @Test
    void unparseableNamesAndKeywordsAreSanitizedAndFreeVariablesMarked() {
        var program = Program.plutusV3(Term.lam("#pair-first", Term.lam("lam",
                Term.apply(Term.var(new NamedDeBruijn("x", 2)), Term.var(new NamedDeBruijn("y", 5))))));

        var text = UplcPrettyPrinter.print(UplcNames.uniquify(program)).text();

        assertEquals("(program 1.1.0 (lam _pair_first (lam lam_ [_pair_first free_5])))", text);
    }

    @Test
    void randomProgramsRoundTripThroughTextWithIdenticalFlatEncoding() {
        var random = new Random(42);
        for (int i = 0; i < 300; i++) {
            var program = Program.plutusV3(randomTerm(random, 0, 7));
            byte[] expected = UplcFlatEncoder.encodeProgram(program);
            var decoded = UplcFlatDecoder.decodeProgram(expected);
            var named = UplcNames.uniquify(decoded);
            assertArrayEquals(expected, UplcFlatEncoder.encodeProgram(named), "uniquify changed structure");

            for (int width : new int[]{20, 60, 200}) {
                var pretty = UplcPrettyPrinter.print(named, new UplcPrettyPrinter.Options(width, 2, 0));
                var reparsed = UplcParser.parseProgram(pretty.text());
                assertArrayEquals(expected, UplcFlatEncoder.encodeProgram(reparsed),
                        () -> "text did not parse back to the same program:\n" + pretty.text());
            }
        }
    }

    @Test
    void everyTermHasASpanCoveringItsText() {
        var random = new Random(7);
        for (int i = 0; i < 100; i++) {
            var named = UplcNames.uniquify(Program.plutusV3(randomTerm(random, 0, 6)));
            var pretty = UplcPrettyPrinter.print(named, new UplcPrettyPrinter.Options(30, 2, 0));
            var lines = pretty.text().split("\n", -1);

            assertEquals(countTerms(named.term()), pretty.terms().size());
            assertEquals(0, pretty.idOf(named.term()));
            for (int id = 0; id < pretty.terms().size(); id++) {
                Term term = pretty.terms().get(id);
                var span = pretty.spans().get(id);
                assertEquals(id, pretty.idOf(term));
                String text = slice(lines, span);
                String expectedStart = switch (term) {
                    case Term.Var v -> v.name().name();
                    case Term.Lam l -> "(lam " + l.paramName();
                    case Term.Apply ignored -> "[";
                    case Term.Force ignored -> "(force";
                    case Term.Delay ignored -> "(delay";
                    case Term.Const ignored -> "(con ";
                    case Term.Builtin ignored -> "(builtin ";
                    case Term.Error ignored -> "(error)";
                    case Term.Constr ignored -> "(constr ";
                    case Term.Case ignored -> "(case";
                };
                assertTrue(text.startsWith(expectedStart), () -> term + " span text: " + text);
                if (!(term instanceof Term.Apply)) {
                    assertEquals(0, bracketBalance(text), () -> "unbalanced span for " + term + ": " + text);
                }
            }
        }
    }

    @Test
    void longTermsBreakAcrossIndentedLines() {
        var body = Term.apply(Term.apply(Term.builtin(DefaultFun.AppendByteString),
                Term.const_(Constant.byteString(new byte[24]))), Term.const_(Constant.byteString(new byte[24])));
        var text = UplcPrettyPrinter.print(Program.plutusV3(Term.lam("x", body)),
                new UplcPrettyPrinter.Options(60, 2, 0)).text();

        assertEquals("""
                (program 1.1.0
                  (lam x
                    [(builtin appendByteString)
                     (con bytestring #000000000000000000000000000000000000000000000000)
                     (con bytestring #000000000000000000000000000000000000000000000000)]))""", text);
    }

    @Test
    void longConstantsCanBeShortened() {
        var program = Program.plutusV3(Term.const_(Constant.byteString(new byte[64])));

        var text = UplcPrettyPrinter.print(program, new UplcPrettyPrinter.Options(100, 2, 30)).text();

        assertEquals("(program 1.1.0 (con bytestring #00000000000…))", text);
    }

    @Test
    void veryDeepTermsDoNotOverflowTheStack() {
        Term term = Term.const_(Constant.integer(1));
        for (int i = 0; i < 200_000; i++) {
            term = i % 2 == 0 ? Term.lam("x", term) : Term.delay(term);
        }
        var named = UplcNames.uniquify(Program.plutusV3(term));
        var pretty = UplcPrettyPrinter.print(named);

        assertEquals(200_001, pretty.terms().size());
        assertTrue(pretty.text().startsWith("(program 1.1.0\n"));
    }

    // ---- helpers ----

    private static Term randomTerm(Random random, int depth, int maxDepth) {
        int choice = depth >= maxDepth ? random.nextInt(4) : random.nextInt(11);
        return switch (choice) {
            case 0 -> depth > 0 ? Term.var(1 + random.nextInt(depth)) : Term.const_(Constant.unit());
            case 1 -> Term.const_(randomConstant(random));
            case 2 -> Term.builtin(DefaultFun.values()[random.nextInt(20)]);
            case 3 -> Term.error();
            case 4, 5 -> Term.lam("i0", randomTerm(random, depth + 1, maxDepth));
            case 6, 7 -> Term.apply(randomTerm(random, depth, maxDepth), randomTerm(random, depth, maxDepth));
            case 8 -> random.nextBoolean()
                    ? Term.force(randomTerm(random, depth, maxDepth))
                    : Term.delay(randomTerm(random, depth, maxDepth));
            case 9 -> {
                var fields = new ArrayList<Term>();
                for (int i = random.nextInt(3); i > 0; i--) fields.add(randomTerm(random, depth, maxDepth));
                yield new Term.Constr(random.nextInt(5), fields);
            }
            default -> {
                var branches = new ArrayList<Term>();
                for (int i = 1 + random.nextInt(3); i > 0; i--) branches.add(randomTerm(random, depth, maxDepth));
                yield new Term.Case(randomTerm(random, depth, maxDepth), branches);
            }
        };
    }

    private static Constant randomConstant(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> Constant.integer(random.nextLong());
            case 1 -> Constant.byteString(new byte[random.nextInt(8)]);
            case 2 -> Constant.string("s\"" + random.nextInt(100) + "\n");
            case 3 -> Constant.bool(random.nextBoolean());
            default -> Constant.data(PlutusData.constr(random.nextInt(3),
                    PlutusData.integer(random.nextInt()), PlutusData.bytes(new byte[]{1, 2})));
        };
    }

    private static int countTerms(Term root) {
        int count = 0;
        var stack = new ArrayList<Term>(List.of(root));
        var seen = new HashSet<Integer>();
        while (!stack.isEmpty()) {
            Term t = stack.removeLast();
            count++;
            switch (t) {
                case Term.Lam l -> stack.add(l.body());
                case Term.Apply a -> { stack.add(a.function()); stack.add(a.argument()); }
                case Term.Force f -> stack.add(f.term());
                case Term.Delay d -> stack.add(d.term());
                case Term.Constr c -> stack.addAll(c.fields());
                case Term.Case cs -> { stack.add(cs.scrutinee()); stack.addAll(cs.branches()); }
                default -> seen.add(count);
            }
        }
        return count;
    }

    private static String slice(String[] lines, UplcPrettyPrinter.Span span) {
        var sb = new StringBuilder();
        for (int line = span.startLine(); line <= span.endLine(); line++) {
            String text = lines[line - 1];
            int from = line == span.startLine() ? span.startColumn() - 1 : 0;
            int to = line == span.endLine() ? span.endColumn() - 1 : text.length();
            sb.append(text, from, to);
            if (line < span.endLine()) sb.append('\n');
        }
        return sb.toString();
    }

    private static int bracketBalance(String text) {
        int balance = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\\') i++;
                else if (ch == '"') inString = false;
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '(' || ch == '[') {
                balance++;
            } else if (ch == ')' || ch == ']') {
                balance--;
            }
        }
        return balance;
    }
}
