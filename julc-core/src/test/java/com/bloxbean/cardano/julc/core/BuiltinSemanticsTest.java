package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BuiltinSemantics} constant classification.
 * <p>
 * {@link Constant.ListConst} does not enforce that its values match its
 * declared element type, so the data-typed list tags must validate list
 * <i>contents</i> — trusting {@code elemType()} alone would let the optimizer
 * certify an erroring {@code listData}/{@code mapData} application as pure
 * (see adr/issues/julc-dce-soundness-fix-plan.md §2.8).
 */
class BuiltinSemanticsTest {

    private static final DefaultUni PAIR_DD = DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA);

    private static Constant wellFormedDataList() {
        return new Constant.ListConst(DefaultUni.DATA,
                List.of(Constant.data(PlutusData.integer(1))));
    }

    private static Constant contentMismatchedDataList() {
        return new Constant.ListConst(DefaultUni.DATA, List.of(Constant.integer(1)));
    }

    private static Constant wellFormedPairDataList() {
        return new Constant.ListConst(PAIR_DD,
                List.of(new Constant.PairConst(
                        Constant.data(PlutusData.integer(1)),
                        Constant.data(PlutusData.bytes(new byte[]{2})))));
    }

    private static Constant contentMismatchedPairDataList() {
        return new Constant.ListConst(PAIR_DD,
                List.of(new Constant.PairConst(Constant.integer(1), Constant.integer(2))));
    }

    @Test
    void listDataMatchRequiresDataElements() {
        assertTrue(BuiltinSemantics.constantMatches(wellFormedDataList(), BuiltinSemantics.ArgType.LIST_DATA));
        assertFalse(BuiltinSemantics.constantMatches(contentMismatchedDataList(), BuiltinSemantics.ArgType.LIST_DATA));
        // empty list is trivially well-formed
        assertTrue(BuiltinSemantics.constantMatches(
                new Constant.ListConst(DefaultUni.DATA, List.of()), BuiltinSemantics.ArgType.LIST_DATA));
    }

    @Test
    void listPairDataMatchRequiresDataPairElements() {
        assertTrue(BuiltinSemantics.constantMatches(wellFormedPairDataList(), BuiltinSemantics.ArgType.LIST_PAIR_DATA));
        assertFalse(BuiltinSemantics.constantMatches(contentMismatchedPairDataList(), BuiltinSemantics.ArgType.LIST_PAIR_DATA));
    }

    @Test
    void plainListMatchIgnoresContents() {
        // LIST consumers (nullList, chooseList) never inspect elements, so a
        // content-mismatched list still matches plain LIST
        assertTrue(BuiltinSemantics.constantMatches(contentMismatchedDataList(), BuiltinSemantics.ArgType.LIST));
    }

    @Test
    void contentMismatchedListTagsAsPlainList() {
        assertEquals(BuiltinSemantics.ArgType.LIST_DATA,
                BuiltinSemantics.constantType(wellFormedDataList()));
        assertEquals(BuiltinSemantics.ArgType.LIST_PAIR_DATA,
                BuiltinSemantics.constantType(wellFormedPairDataList()));
        // the downgrade: list-ness holds, the data-typed claim does not
        assertEquals(BuiltinSemantics.ArgType.LIST,
                BuiltinSemantics.constantType(contentMismatchedDataList()));
        assertEquals(BuiltinSemantics.ArgType.LIST,
                BuiltinSemantics.constantType(contentMismatchedPairDataList()));
    }

    @Test
    void plainListDoesNotSatisfyListData() {
        // the downgraded tag must not certify into a data-typed position
        assertFalse(BuiltinSemantics.typeSatisfies(
                BuiltinSemantics.ArgType.LIST, BuiltinSemantics.ArgType.LIST_DATA));
        assertFalse(BuiltinSemantics.typeSatisfies(
                BuiltinSemantics.ArgType.LIST, BuiltinSemantics.ArgType.LIST_PAIR_DATA));
        assertTrue(BuiltinSemantics.typeSatisfies(
                BuiltinSemantics.ArgType.LIST, BuiltinSemantics.ArgType.LIST));
    }
}
