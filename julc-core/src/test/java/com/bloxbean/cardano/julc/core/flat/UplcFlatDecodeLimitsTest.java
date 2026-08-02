package com.bloxbean.cardano.julc.core.flat;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UplcFlatDecodeLimitsTest {

    private static final FlatDecodeLimits PV11 = new FlatDecodeLimits(32, 1024);

    @Test
    void constantTypeHeaderBoundaryMatchesPinnedLedger() {
        byte[] atLimit = encodeConstant(nestedEmptyList(15)); // 31 type nodes
        byte[] aboveLimit = encodeConstant(nestedEmptyList(16)); // 33 type nodes

        assertDoesNotThrow(() -> UplcFlatDecoder.decodeProgram(atLimit, PV11));
        var failure = assertThrows(FlatDecodingException.class,
                () -> UplcFlatDecoder.decodeProgram(aboveLimit, PV11));
        assertTrue(failure.getMessage().contains("exceeds 32 nodes"));

        // The pre-PV11 profile is deliberately unbounded.
        assertDoesNotThrow(() -> UplcFlatDecoder.decodeProgram(
                aboveLimit, FlatDecodeLimits.UNBOUNDED));
    }

    @Test
    void constructorFieldBoundaryMatchesPinnedLedger() {
        var atLimit = new Term.Constr(0, repeatedTerms(1024));
        var aboveLimit = new Term.Constr(0, repeatedTerms(1025));

        var decoded = UplcFlatDecoder.decodeProgram(
                UplcFlatEncoder.encodeProgram(Program.plutusV3(atLimit)), PV11);
        assertEquals(1024, ((Term.Constr) decoded.term()).fields().size());

        var failure = assertThrows(FlatDecodingException.class, () ->
                UplcFlatDecoder.decodeProgram(
                        UplcFlatEncoder.encodeProgram(Program.plutusV3(aboveLimit)), PV11));
        assertTrue(failure.getMessage().contains("more than 1024 fields"));

        assertDoesNotThrow(() -> UplcFlatDecoder.decodeProgram(
                UplcFlatEncoder.encodeProgram(Program.plutusV3(aboveLimit)),
                FlatDecodeLimits.UNBOUNDED));
    }

    @Test
    void constructorLimitDoesNotApplyToCaseBranches() {
        var term = new Term.Case(
                new Term.Constr(0, List.of()), repeatedTerms(1025));
        assertDoesNotThrow(() -> UplcFlatDecoder.decodeProgram(
                UplcFlatEncoder.encodeProgram(Program.plutusV3(term)), PV11));
    }

    private static byte[] encodeConstant(Constant constant) {
        return UplcFlatEncoder.encodeProgram(
                Program.plutusV3(Term.const_(constant)));
    }

    private static Constant nestedEmptyList(int depth) {
        DefaultUni elementType = DefaultUni.INTEGER;
        for (int i = 1; i < depth; i++) {
            elementType = new DefaultUni.Apply(new DefaultUni.ProtoList(), elementType);
        }
        return new Constant.ListConst(elementType, List.of());
    }

    private static List<Term> repeatedTerms(int count) {
        return new ArrayList<>(Collections.nCopies(count, Term.error()));
    }
}
