package org.julclang.difftest;

import org.julclang.difftest.RecordMappingTest.EqualParents;
import org.julclang.difftest.RecordMappingTest.Shapes;
import org.julclang.difftest.RecordMappingTest.TwoParents;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The tag table on its own, and its source validation before any run (ADR-066 D3). */
class TagTableTest {

    private static TagTable.Entry entry(Class<?> record, Class<?> parent, int tag) {
        return new TagTable.Entry(record.getName(), parent.getName(), tag);
    }

    @Test
    void entriesAreSortedByRecordThenParent() {
        var table = TagTable.of(new TagTable.Entry("P$B", "P$S", 1), new TagTable.Entry("P$A", "P$T", 0),
                new TagTable.Entry("P$A", "P$S", 0), new TagTable.Entry("P$C", "P$T", 1));
        assertEquals(List.of("P$A P$S 0", "P$A P$T 0", "P$B P$S 1", "P$C P$T 1"), table.lines());
        assertEquals(table, TagTable.of(new TagTable.Entry("P$C", "P$T", 1), new TagTable.Entry("P$A", "P$S", 0),
                new TagTable.Entry("P$B", "P$S", 1), new TagTable.Entry("P$A", "P$T", 0)));
        assertEquals(new TagTable.Entry("Probe$A", "Probe$S", 2), TagTable.Entry.parse(" Probe$A  Probe$S 2 "));
    }

    @Test
    void eachFamilyHasDistinctTagsFromZero() {
        // Duplicate tags, a duplicate entry, a gap, and a family that does not start at 0.
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("P$A", "P$S", 0), new TagTable.Entry("P$B", "P$S", 0)));
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("P$A", "P$S", 0), new TagTable.Entry("P$A", "P$S", 0)));
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("P$A", "P$S", 0), new TagTable.Entry("P$A", "P$S", 1),
                new TagTable.Entry("P$B", "P$S", 2)));
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("P$A", "P$S", 0), new TagTable.Entry("P$B", "P$S", 2)));
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("P$A", "P$S", 1)));
        assertThrows(HarnessException.class, () -> new TagTable.Entry("P$A", "P$S", -1));
        assertThrows(HarnessException.class, () -> new TagTable.Entry("P A", "P$S", 0));
        for (String text : List.of("Probe$A Probe$S", "Probe$A Probe$S x", "Probe$A Probe$S 0 1", "")) {
            assertThrows(HarnessException.class, () -> TagTable.Entry.parse(text), text);
        }
        // Families are separate: one record may have tag 0 under one parent and 1 under another (#279's shape).
        assertDoesNotThrow(() -> RecordMappingTest.UNEQUAL);
    }

    @Test
    void validTablesPassSourceValidation() {
        assertDoesNotThrow(() -> RecordMappingTest.PERMITS.validate(Shapes.class));
        assertDoesNotThrow(() -> RecordMappingTest.OTHER.validate(Shapes.class));
        assertDoesNotThrow(() -> RecordMappingTest.EQUAL.validate(EqualParents.class));
        // Unequal tags under two parents are valid in the source: the record may live inside m (ADR-066 D3).
        assertDoesNotThrow(() -> RecordMappingTest.UNEQUAL.validate(TwoParents.class));
        // A program without sealed interfaces needs no tags.
        assertDoesNotThrow(() -> TagTable.EMPTY.validate(Shapes.Point.class));
    }

    @Test
    void sourceValidationRejectsATableThatDoesNotFitTheProgram() {
        // A missing variant, no table at all, and a missing second parent.
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Shapes.Circle.class, Shapes.Shape.class, 0),
                entry(Shapes.Square.class, Shapes.Shape.class, 1)).validate(Shapes.class));
        assertThrows(HarnessException.class, () -> TagTable.EMPTY.validate(Shapes.class));
        assertThrows(HarnessException.class, () -> TagTable.of(entry(EqualParents.Both.class, EqualParents.Up.class, 0),
                entry(EqualParents.OnlyUp.class, EqualParents.Up.class, 1),
                entry(EqualParents.OnlyDown.class, EqualParents.Down.class, 0)).validate(EqualParents.class));
        // More tags than variants: a standalone record, or a record of another family, under the parent.
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Shapes.Circle.class, Shapes.Shape.class, 0),
                entry(Shapes.Square.class, Shapes.Shape.class, 1), entry(Shapes.Tri.class, Shapes.Shape.class, 2),
                entry(Shapes.Point.class, Shapes.Shape.class, 3)).validate(Shapes.class));
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Shapes.Circle.class, Shapes.Shape.class, 0),
                entry(Shapes.Square.class, Shapes.Shape.class, 1), entry(Shapes.Tri.class, Shapes.Shape.class, 2),
                entry(TwoParents.OnlyLeft.class, Shapes.Shape.class, 3)).validate(Shapes.class));
        // A family whose variants are outside the program: here, the interface alone (review F48, M6).
        assertThrows(HarnessException.class, () -> TagTable.EMPTY.validate(Shapes.Shape.class));
        assertThrows(HarnessException.class, () -> RecordMappingTest.PERMITS.validate(Shapes.Shape.class));
        // Entries may name only classes of the program, never other classes the loader can see (review F48).
        assertThrows(HarnessException.class, () -> RecordMappingTest.UNEQUAL.validate(Shapes.Point.class));
        assertDoesNotThrow(() -> RecordMappingTest.UNEQUAL.validate(List.of(Shapes.Point.class, TwoParents.class)));
        // A parent that is not a sealed interface, and names that are not in the program.
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Shapes.Circle.class, Shapes.Point.class, 0)).validate(Shapes.class));
        assertThrows(HarnessException.class, () -> TagTable.of(new TagTable.Entry("NoSuch$A", Shapes.Shape.class.getName(), 0))
                .validate(Shapes.class));
    }

    /** A sealed interface that permits another sealed interface: a nested hierarchy, with no Data encoding. */
    static final class Nested {
        sealed interface S permits A, U {}
        record A(BigInteger v) implements S {}
        sealed interface U extends S permits B, C {}
        record B(BigInteger v) implements U {}
        record C(BigInteger v) implements U {}
    }

    /**
     * A program may declare a nested sealed hierarchy without tags, since JuLC rejects it and no value of it crosses
     * the boundary; a tag naming one is an error, and mapping one of its values fails, so the harness never compares
     * a value it cannot encode (ADR-067's nested-hierarchy cases, after merging ADR-066).
     */
    @Test
    void aNestedSealedHierarchyTakesNoTagsAndCannotBeMapped() {
        assertDoesNotThrow(() -> TagTable.EMPTY.validate(Nested.class));
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Nested.A.class, Nested.S.class, 0)).validate(Nested.class));
        assertThrows(HarnessException.class, () -> TagTable.of(entry(Nested.B.class, Nested.U.class, 0),
                entry(Nested.C.class, Nested.U.class, 1)).validate(Nested.class));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new Nested.A(BigInteger.ONE), Nested.S.class,
                TagTable.EMPTY));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(new Nested.B(BigInteger.ONE), Nested.U.class,
                TagTable.EMPTY));
    }
}
