package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the record and sealed value mapping (ADR-066 D3) against Data written by hand from the documented
 * encoding ({@code docs/src/content/docs/guides/advanced-guide.md}, "Record Encoding", "Sealed Interface (Sum
 * Type) Encoding", "Boolean Encoding"): a record is {@code Constr tag [components in declaration order]}, a
 * standalone record has tag 0, a variant has its constructor tag, and a {@code boolean} is {@code Constr 0 []}
 * (false) or {@code Constr 1 []} (true). No expected value comes from JuLC.
 */
class RecordMappingTest {

    /** Standalone records, one without components, and records with sealed and record components. */
    static final class Shapes {
        record Point(BigInteger x, BigInteger y) {}
        record Unit() {}
        record Flags(boolean on, BigInteger n, boolean off) {}

        // Declared Tri, Circle, Square; permitted Circle, Square, Tri.
        sealed interface Shape permits Circle, Square, Tri {}
        record Tri(BigInteger a, BigInteger b, BigInteger c) implements Shape {}
        record Circle(BigInteger r) implements Shape {}
        record Square(BigInteger s, boolean filled) implements Shape {}

        record Drawing(Shape shape, Point at, Unit unit) {}
        record Frame(Drawing inner, boolean visible) {}
    }

    /** A record under two sealed parents at different positions (#279). */
    static final class TwoParents {
        sealed interface Left permits Shared, OnlyLeft {}
        sealed interface Right permits OnlyRight, Shared {}
        record Shared(BigInteger v) implements Left, Right {}
        record OnlyLeft() implements Left {}
        record OnlyRight() implements Right {}
        record Holder(Shared shared) {}
    }

    /** A record under two sealed parents at the same position. */
    static final class EqualParents {
        sealed interface Up permits Both, OnlyUp {}
        sealed interface Down permits Both, OnlyDown {}
        record Both(BigInteger v) implements Up, Down {}
        record OnlyUp() implements Up {}
        record OnlyDown() implements Down {}
    }

    private static TagTable.Entry entry(Class<?> record, Class<?> parent, int tag) {
        return new TagTable.Entry(record.getName(), parent.getName(), tag);
    }

    /** The {@code permits} order. */
    static final TagTable PERMITS = TagTable.of(entry(Shapes.Circle.class, Shapes.Shape.class, 0),
            entry(Shapes.Square.class, Shapes.Shape.class, 1), entry(Shapes.Tri.class, Shapes.Shape.class, 2));
    /** Neither the declaration order nor the {@code permits} order. */
    static final TagTable OTHER = TagTable.of(entry(Shapes.Square.class, Shapes.Shape.class, 0),
            entry(Shapes.Tri.class, Shapes.Shape.class, 1), entry(Shapes.Circle.class, Shapes.Shape.class, 2));
    static final TagTable UNEQUAL = TagTable.of(entry(TwoParents.Shared.class, TwoParents.Left.class, 0),
            entry(TwoParents.OnlyLeft.class, TwoParents.Left.class, 1), entry(TwoParents.OnlyRight.class, TwoParents.Right.class, 0),
            entry(TwoParents.Shared.class, TwoParents.Right.class, 1));
    static final TagTable EQUAL = TagTable.of(entry(EqualParents.Both.class, EqualParents.Up.class, 0),
            entry(EqualParents.OnlyUp.class, EqualParents.Up.class, 1), entry(EqualParents.Both.class, EqualParents.Down.class, 0),
            entry(EqualParents.OnlyDown.class, EqualParents.Down.class, 1));

    private static final PlutusData TRUE = constr(1);
    private static final PlutusData FALSE = constr(0);

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    @Test
    void standaloneRecordsHaveTagZero() {
        assertEquals(constr(0, integer(1), integer(-2)), ValueMapping.toData(new Shapes.Point(big(1), big(-2))));
        assertEquals(constr(0), ValueMapping.toData(new Shapes.Unit()));
        assertEquals(constr(0, TRUE, integer(5), FALSE), ValueMapping.toData(new Shapes.Flags(true, big(5), false)));
        assertEquals(new Shapes.Point(big(1), big(-2)), ValueMapping.fromData(constr(0, integer(1), integer(-2)), Shapes.Point.class));
        assertEquals(new Shapes.Unit(), ValueMapping.fromData(constr(0), Shapes.Unit.class));
        assertEquals(new Shapes.Flags(false, big(0), true),
                ValueMapping.fromData(constr(0, FALSE, integer(0), TRUE), Shapes.Flags.class));
    }

    @Test
    void variantsTakeTheirTagTypedAsThemselvesOrAsTheSealedType() {
        var circle = new Shapes.Circle(big(7));
        var square = new Shapes.Square(big(3), true);
        var tri = new Shapes.Tri(big(1), big(2), big(3));
        assertEquals(constr(0, integer(7)), ValueMapping.toData(circle, PERMITS));
        assertEquals(constr(1, integer(3), TRUE), ValueMapping.toData(square, PERMITS));
        assertEquals(constr(2, integer(1), integer(2), integer(3)), ValueMapping.toData(tri, PERMITS));
        assertEquals(constr(1, integer(3), TRUE), ValueMapping.toData(square, Shapes.Shape.class, PERMITS));
        assertEquals(constr(1, integer(3), TRUE), ValueMapping.toData(square, Shapes.Square.class, PERMITS));

        assertEquals(square, ValueMapping.fromData(constr(1, integer(3), TRUE), Shapes.Shape.class, PERMITS));
        assertEquals(square, ValueMapping.fromData(constr(1, integer(3), TRUE), Shapes.Square.class, PERMITS));
        assertEquals(tri, ValueMapping.fromData(constr(2, integer(1), integer(2), integer(3)), Shapes.Shape.class, PERMITS));
        assertEquals(tri, ValueMapping.fromData(constr(2, integer(1), integer(2), integer(3)), Shapes.Tri.class, PERMITS));
        assertEquals(circle, ValueMapping.fromData(constr(0, integer(7)), Shapes.Shape.class, PERMITS));
    }

    /**
     * The table alone decides: under a table in neither the declaration order (Tri, Circle, Square) nor the
     * {@code permits} order (Circle, Square, Tri), which on JDK 25 is also reflection's order, every tag follows
     * the table.
     */
    @Test
    void tagsFollowTheTableNotTheDeclarationOrPermitsOrder() {
        assertEquals(List.of(Shapes.Circle.class, Shapes.Square.class, Shapes.Tri.class),
                Arrays.asList(Shapes.Shape.class.getPermittedSubclasses()), "the JDK's order, which the mapping must not follow");
        assertEquals(constr(2, integer(7)), ValueMapping.toData(new Shapes.Circle(big(7)), OTHER));
        assertEquals(constr(0, integer(3), FALSE), ValueMapping.toData(new Shapes.Square(big(3), false), OTHER));
        assertEquals(constr(1, integer(1), integer(2), integer(3)), ValueMapping.toData(new Shapes.Tri(big(1), big(2), big(3)), OTHER));
        assertEquals(new Shapes.Circle(big(7)), ValueMapping.fromData(constr(2, integer(7)), Shapes.Shape.class, OTHER));
        assertEquals(new Shapes.Square(big(3), false), ValueMapping.fromData(constr(0, integer(3), FALSE), Shapes.Shape.class, OTHER));
        assertEquals(new Shapes.Tri(big(1), big(2), big(3)),
                ValueMapping.fromData(constr(1, integer(1), integer(2), integer(3)), Shapes.Shape.class, OTHER));
    }

    @Test
    void nestedRecordsAndSealedComponents() {
        var drawing = new Shapes.Drawing(new Shapes.Tri(big(1), big(2), big(3)), new Shapes.Point(big(4), big(5)), new Shapes.Unit());
        var frame = new Shapes.Frame(drawing, true);
        var drawingData = constr(0, constr(2, integer(1), integer(2), integer(3)), constr(0, integer(4), integer(5)), constr(0));
        var frameData = constr(0, drawingData, TRUE);
        assertEquals(drawingData, ValueMapping.toData(drawing, PERMITS));
        assertEquals(frameData, ValueMapping.toData(frame, PERMITS));
        assertEquals(frame, ValueMapping.fromData(frameData, Shapes.Frame.class, PERMITS));
        // The same value under another table differs only in the variant's tag.
        assertEquals(constr(0, constr(0, constr(1, integer(1), integer(2), integer(3)), constr(0, integer(4), integer(5)), constr(0)), TRUE),
                ValueMapping.toData(frame, OTHER));
    }

    @Test
    void recordsInsideLists() {
        var list = JulcList.of(new Shapes.Circle(big(1)), new Shapes.Point(big(2), big(3)));
        assertEquals(new PlutusData.ListData(List.of(constr(0, integer(1)), constr(0, integer(2), integer(3)))),
                ValueMapping.toData(list, PERMITS));
    }

    @Test
    void valuesRoundTrip() {
        List<Record> values = List.of(new Shapes.Point(big(-6), big(6)), new Shapes.Unit(), new Shapes.Flags(true, big(0), true),
                new Shapes.Circle(big(0)), new Shapes.Square(big(-1), false), new Shapes.Tri(big(1), big(1), big(1)),
                new Shapes.Frame(new Shapes.Drawing(new Shapes.Square(big(2), true), new Shapes.Point(big(0), big(0)),
                        new Shapes.Unit()), false));
        for (var table : List.of(PERMITS, OTHER)) {
            for (Record value : values) {
                var data = ValueMapping.toData(value, table);
                assertEquals(value, ValueMapping.fromData(data, value.getClass(), table), value + " " + table);
                if (value instanceof Shapes.Shape) {
                    assertEquals(value, ValueMapping.fromData(data, Shapes.Shape.class, table), value + " " + table);
                }
                assertEquals(data, ValueMapping.toData(ValueMapping.fromData(data, value.getClass(), table), table));
            }
        }
    }

    @Test
    void aVariantNeedsItsTag() {
        // G1-I4: no reflection fallback. A table that misses a variant is valid on its own; mapping that variant is not.
        var partial = TagTable.of(entry(Shapes.Circle.class, Shapes.Shape.class, 0), entry(Shapes.Square.class, Shapes.Shape.class, 1));
        assertEquals(constr(0, integer(1)), ValueMapping.toData(new Shapes.Circle(big(1)), partial));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new Shapes.Tri(big(1), big(2), big(3)), partial));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(2, integer(1), integer(2), integer(3)),
                Shapes.Shape.class, partial));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new Shapes.Circle(big(1))));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), Shapes.Shape.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), Shapes.Circle.class));
    }

    @Test
    void malformedDataIsAHarnessError() {
        // Wrong arity.
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(1, integer(3)), Shapes.Shape.class, PERMITS));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), Shapes.Point.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1), integer(2), integer(3)), Shapes.Point.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), Shapes.Unit.class));
        // Unknown tag: no variant has it, or a standalone record or variant typed as itself has another one.
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(3), Shapes.Shape.class, PERMITS));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(1, integer(1), integer(2)), Shapes.Point.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(3), TRUE), Shapes.Square.class, PERMITS));
        // Not a Constr, and a component that is not what its type needs.
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(integer(1), Shapes.Shape.class, PERMITS));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(new PlutusData.ListData(List.of()), Shapes.Point.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, constr(2), integer(1), FALSE), Shapes.Flags.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, TRUE, integer(1)), Shapes.Point.class));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new Shapes.Point(null, big(1))));
    }

    /** Boundary validation (ADR-066 D3): unequal tags under two parents leave the record with no single encoding. */
    @Test
    void aRecordWithUnequalTagsUnderTwoParentsCannotBeMapped() {
        var shared = new TwoParents.Shared(big(1));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(shared, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(shared, TwoParents.Left.class, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(shared, TwoParents.Right.class, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new TwoParents.Holder(shared), UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), TwoParents.Left.class, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(1, integer(1)), TwoParents.Right.class, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), TwoParents.Shared.class, UNEQUAL));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, constr(0, integer(1))), TwoParents.Holder.class, UNEQUAL));
        // Its siblings map as usual.
        assertEquals(constr(1), ValueMapping.toData(new TwoParents.OnlyLeft(), UNEQUAL));
        assertEquals(new TwoParents.OnlyRight(), ValueMapping.fromData(constr(0), TwoParents.Right.class, UNEQUAL));
    }

    @Test
    void aRecordWithEqualTagsUnderTwoParentsMaps() {
        var both = new EqualParents.Both(big(4));
        assertEquals(constr(0, integer(4)), ValueMapping.toData(both, EQUAL));
        assertEquals(constr(0, integer(4)), ValueMapping.toData(both, EqualParents.Down.class, EQUAL));
        assertEquals(both, ValueMapping.fromData(constr(0, integer(4)), EqualParents.Up.class, EQUAL));
        assertEquals(both, ValueMapping.fromData(constr(0, integer(4)), EqualParents.Down.class, EQUAL));
        assertEquals(new EqualParents.OnlyDown(), ValueMapping.fromData(constr(1), EqualParents.Down.class, EQUAL));
    }

    /** JuLC's own library records have their own encodings (ADR-063 D4) and stay unmapped. */
    @Test
    void libraryRecordsAreNotUserRecords() {
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new PlutusData.Pair(integer(1), integer(2))));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(constr(0, integer(1)), PlutusData.ConstrData.class));
    }
}
