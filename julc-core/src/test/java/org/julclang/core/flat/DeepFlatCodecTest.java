package org.julclang.core.flat;

import org.julclang.core.DeepTerms;
import org.julclang.core.DefaultFun;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** FLAT encoding and decoding need no Java stack per level of a term, and write and read the same bits (issue #327). */
class DeepFlatCodecTest {

    @Test
    void deepTermsInEveryChildPositionRoundTripOnASmallStack() {
        for (var position : DeepTerms.Position.values()) {
            var program = Program.plutusV3(DeepTerms.spine(100_000, position));
            byte[] bytes = DeepTerms.onSmallStack(() -> UplcFlatEncoder.encodeProgram(program));
            var decoded = DeepTerms.onSmallStack(() -> UplcFlatDecoder.decodeProgram(bytes));
            assertEquals(program, decoded, position.toString());
        }
    }

    @Test
    void writesTheBitsTheRecursiveEncoderWrote() {
        var random = new Random(3271);
        for (int i = 0; i < 5_000; i++) {
            var term = DeepTerms.random(random, 1 + random.nextInt(60));
            assertArrayEquals(recursiveEncode(term), encode(term), "term " + i);
        }
    }

    @Test
    void readsWhatTheRecursiveDecoderRead() {
        var random = new Random(3272);
        for (int i = 0; i < 5_000; i++) {
            var term = DeepTerms.random(random, 1 + random.nextInt(60));
            byte[] bytes = encode(term);
            var decoded = new UplcFlatDecoder(new FlatReader(bytes)).readTerm();
            assertEquals(recursiveDecode(new FlatReader(bytes)), decoded, "term " + i);
            assertEquals(term, decoded, "term " + i + " round trip");
        }
    }

    @Test
    void theFieldLimitStillAppliesToADeepConstr() {
        // A Constr of two fields, nested 50,000 deep, decoded with a limit of one field.
        Term term = DeepTerms.var(1);
        for (int i = 0; i < 50_000; i++) term = new Term.Constr(0, List.of(term));
        term = new Term.Constr(0, List.of(term, DeepTerms.var(2)));
        byte[] bytes = UplcFlatEncoder.encodeProgram(Program.plutusV3(term));
        var limits = new FlatDecodeLimits(Integer.MAX_VALUE, 1);
        var error = assertThrows(AssertionError.class,
                () -> DeepTerms.onSmallStack(() -> UplcFlatDecoder.decodeProgram(bytes, limits)));
        assertEquals(FlatDecodingException.class, error.getCause().getClass());
        assertEquals("Constr has more than 1 fields", error.getCause().getMessage());
    }

    @Test
    void anUnknownTagDeepInsideATermIsReported() {
        var writer = new FlatWriter();
        for (int i = 0; i < 50_000; i++) writer.bits(4, 1); // Delay
        writer.bits(4, 15);
        writer.filler();
        byte[] bytes = writer.toByteArray();
        var error = assertThrows(AssertionError.class,
                () -> DeepTerms.onSmallStack(() -> new UplcFlatDecoder(new FlatReader(bytes)).readTerm()));
        assertEquals("Unknown Term tag: 15", error.getCause().getMessage());
    }

    private static byte[] encode(Term term) {
        var writer = new FlatWriter();
        new UplcFlatEncoder(writer).writeTerm(term);
        writer.filler();
        return writer.toByteArray();
    }

    // ---- the recursive forms this change replaced, kept as references ----

    private static byte[] recursiveEncode(Term term) {
        var writer = new FlatWriter();
        recursiveWrite(term, writer, new UplcFlatEncoder(writer));
        writer.filler();
        return writer.toByteArray();
    }

    private static void recursiveWrite(Term term, FlatWriter writer, UplcFlatEncoder constants) {
        switch (term) {
            case Term.Var v -> {
                writer.bits(4, 0);
                writer.word64(v.name().index());
            }
            case Term.Delay d -> {
                writer.bits(4, 1);
                recursiveWrite(d.term(), writer, constants);
            }
            case Term.Lam l -> {
                writer.bits(4, 2);
                recursiveWrite(l.body(), writer, constants);
            }
            case Term.Apply a -> {
                writer.bits(4, 3);
                recursiveWrite(a.function(), writer, constants);
                recursiveWrite(a.argument(), writer, constants);
            }
            case Term.Const c -> {
                writer.bits(4, 4);
                constants.writeConstant(c.value());
            }
            case Term.Force f -> {
                writer.bits(4, 5);
                recursiveWrite(f.term(), writer, constants);
            }
            case Term.Error _ -> writer.bits(4, 6);
            case Term.Builtin b -> {
                writer.bits(4, 7);
                writer.bits(7, b.fun().flatCode());
            }
            case Term.Constr c -> {
                writer.bits(4, 8);
                writer.word64(c.tag());
                for (var t : c.fields()) {
                    writer.listCons();
                    recursiveWrite(t, writer, constants);
                }
                writer.listNil();
            }
            case Term.Case cs -> {
                writer.bits(4, 9);
                recursiveWrite(cs.scrutinee(), writer, constants);
                for (var t : cs.branches()) {
                    writer.listCons();
                    recursiveWrite(t, writer, constants);
                }
                writer.listNil();
            }
        }
    }

    private static Term recursiveDecode(FlatReader reader) {
        int tag = reader.bits8(4);
        return switch (tag) {
            case 0 -> {
                long idx = reader.word64();
                yield new Term.Var(new NamedDeBruijn("i" + idx, (int) idx));
            }
            case 1 -> new Term.Delay(recursiveDecode(reader));
            case 2 -> new Term.Lam("i0", recursiveDecode(reader));
            case 3 -> {
                Term function = recursiveDecode(reader);
                yield new Term.Apply(function, recursiveDecode(reader));
            }
            case 4 -> new Term.Const(new UplcFlatDecoder(reader).readConstant());
            case 5 -> new Term.Force(recursiveDecode(reader));
            case 6 -> new Term.Error();
            case 7 -> new Term.Builtin(DefaultFun.fromFlatCode(reader.bits8(7)));
            case 8 -> {
                long constrTag = reader.word64();
                yield new Term.Constr(constrTag, recursiveList(reader));
            }
            case 9 -> {
                Term scrutinee = recursiveDecode(reader);
                yield new Term.Case(scrutinee, recursiveList(reader));
            }
            default -> throw new FlatDecodingException("Unknown Term tag: " + tag);
        };
    }

    private static List<Term> recursiveList(FlatReader reader) {
        var terms = new ArrayList<Term>();
        while (reader.listHasNext()) terms.add(recursiveDecode(reader));
        return terms;
    }
}
