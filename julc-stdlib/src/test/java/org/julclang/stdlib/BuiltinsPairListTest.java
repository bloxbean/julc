package org.julclang.stdlib;

import org.julclang.core.PlutusData;
import org.julclang.stdlib.lib.ValuesLib;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pair list stays a map on the JVM, as on-chain: {@code tailList}, {@code mkCons} and {@code mkNilPairData} leave a {@code MapData}. */
class BuiltinsPairListTest {

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData.MapData map(long... keys) {
        var entries = new ArrayList<PlutusData.Pair>();
        for (long key : keys) entries.add(new PlutusData.Pair(integer(key), integer(key + 10)));
        return new PlutusData.MapData(entries);
    }

    @Test
    void tailOfAMapIsAMap() {
        assertEquals(map(2, 3), Builtins.tailList(map(1, 2, 3)));
        assertEquals(map(), Builtins.tailList(map(1)));
        assertEquals(map(2), Builtins.tailList((Object) map(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> Builtins.tailList(map()));
    }

    @Test
    void tailOfAListIsAList() {
        var list = new PlutusData.ListData(List.of(integer(1), integer(2)));
        assertEquals(new PlutusData.ListData(List.of(integer(2))), Builtins.tailList(list));
    }

    @Test
    void consOntoAMapIsAMap() {
        assertEquals(map(1, 2), Builtins.mkCons(Builtins.mkPairData(integer(1), integer(11)), map(2)));
        assertEquals(map(1), Builtins.mkCons(Builtins.mkPairData(integer(1), integer(11)), Builtins.mkNilPairData()));
    }

    @Test
    void consOfWhatIsNotAPairOntoAMapFails() {
        assertThrows(IllegalArgumentException.class, () -> Builtins.mkCons(integer(1), map(2)));
        assertThrows(IllegalArgumentException.class, () -> Builtins.mkCons(new PlutusData.ConstrData(0, List.of(integer(1))), map()));
    }

    @Test
    void consOntoAListIsAList() {
        var pair = Builtins.mkPairData(integer(1), integer(11));
        assertEquals(new PlutusData.ListData(List.of(pair)), Builtins.mkCons(pair, Builtins.mkNilData()));
        assertEquals(new PlutusData.ListData(List.of(integer(1))), Builtins.mkCons(integer(1), Builtins.mkNilData()));
    }

    @Test
    void anEmptyPairListIsAnEmptyMap() {
        assertEquals(map(), Builtins.mkNilPairData());
    }

    @Test
    void whatReadsAListReadsAMap() {
        assertEquals(Builtins.mkPairData(integer(1), integer(11)), Builtins.headList(map(1, 2)));
        assertTrue(Builtins.nullList(Builtins.tailList(map(1))));
        assertFalse(Builtins.nullList(map(1)));
    }

    @Test
    void mapDataTakesAMapAndAListOfPairs() {
        assertEquals(map(1), Builtins.mapData(map(1)));
        assertEquals(map(1), Builtins.mapData(Builtins.mkCons(Builtins.mkPairData(integer(1), integer(11)), Builtins.mkNilData())));
        assertEquals(map(2), Builtins.mapData(Builtins.tailList(map(1, 2))));
    }

    /** What the libraries walk and build, called on the JVM with a map. */
    @Test
    void theLibrariesOnAMap() {
        var token = new PlutusData.BytesData(new byte[]{1});
        var tokens = new PlutusData.MapData(List.of(new PlutusData.Pair(new PlutusData.BytesData(new byte[]{9}), integer(0)), new PlutusData.Pair(token, integer(7))));
        assertEquals(BigInteger.valueOf(7), ValuesLib.findTokenAmount(tokens, token));
        assertEquals(BigInteger.ZERO, ValuesLib.findTokenAmount(tokens, new PlutusData.BytesData(new byte[]{2})));
        assertFalse(ValuesLib.isTokenMapZero(tokens));
        assertTrue(ValuesLib.isTokenMapZero(new PlutusData.MapData(List.of(new PlutusData.Pair(token, integer(0))))));
        var negated = ValuesLib.negateTokenMap(tokens);
        assertEquals(new PlutusData.MapData(List.of(new PlutusData.Pair(new PlutusData.BytesData(new byte[]{9}), integer(0)), new PlutusData.Pair(token, integer(-7)))), negated);
        var added = ValuesLib._addTokens(tokens, tokens);
        assertEquals(BigInteger.valueOf(14), ValuesLib.findTokenAmount(added, token));
    }

    @Test
    void aMapIsNotAListToUnListData() {
        assertThrows(IllegalArgumentException.class, () -> Builtins.unListData(Builtins.tailList(map(1, 2))));
        assertInstanceOf(PlutusData.MapData.class, Builtins.unMapData(Builtins.tailList(map(1, 2))));
    }
}
