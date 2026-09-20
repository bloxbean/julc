package org.julclang.compiler;

import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryDescriptionsTest {
    private JavaLibraryProvider provider(String source) {
        return new JavaLibraryProvider(List.of(source), null, new CompilerOptions());
    }
    @Test void retainsNominalTypesWhenPirRepresentationsAreIdentical() {
        var p=provider("""
            package sample;
            import java.math.BigInteger;
            @OnchainLibrary public class Money {
                @NewType public record Price(BigInteger value) {}
                @NewType public record Quantity(BigInteger value) {}
                public static Price price(BigInteger x) { return new Price(x); }
                public static Quantity quantity(BigInteger x) { return new Quantity(x); }
            }
            """);
        var price=p.describe("sample.Money").get(0);
        var quantity=p.describe("sample.Money").get(1);
        assertEquals(price.type(),quantity.type());
        assertNotEquals(price.result(),quantity.result());
        assertEquals("sample.Money.Price",price.result().name());
        assertEquals(new PirType.IntegerType(),p.types().get(price.result().name()).representation());
    }
    @Test void describesRecursiveReferencesAndConstructorTags() {
        var p=provider("""
            package sample;
            import java.math.BigInteger;
            @OnchainLibrary public class Trees {
                public sealed interface Tree {
                    record Leaf(BigInteger value) implements Tree {}
                    record Branch(Tree left, Tree right) implements Tree {}
                }
                public static Tree leaf(BigInteger x) { return new Tree.Leaf(x); }
            }
            """);
        var tree=p.types().get("sample.Trees.Tree");
        assertEquals(List.of(0,1),tree.constructors().stream().map(LibraryType.Constructor::tag).toList());
        assertEquals("sample.Trees.Tree",tree.constructors().get(1).fields().get(0).type().name());
        assertTrue(p.namedDefinitions().containsKey("sample.Trees.Tree"));
        assertFalse(p.types().containsKey("sample.Trees.Tree.Branch"));
    }
    @Test void specialLedgerLayoutsDoNotAdvertiseRecordProjections() {
        var p=provider("@OnchainLibrary class Test { public static int identity(int x) { return x; } }");
        var value=p.types().get("org.julclang.ledger.Value");
        assertNotNull(value);
        assertTrue(value.fields().isEmpty());
    }
    @Test void rawContainersAreDescribedAsUnsupportedRatherThanErased() {
        var p=provider("""
            import java.util.List;
            @OnchainLibrary public class Raw {
                public static List identity(List list) { return list; }
            }
            """);
        assertTrue(p.describe("Raw").isEmpty());
        assertTrue(p.unsupportedExports().get("Raw.identity").contains("Raw or invalid container"));
    }
    @Test void duplicateSourceOwnershipRejected() {
        String source="@OnchainLibrary class Test { public record Item(int x) {} public static int value(int x) { return x; } }";
        assertThrows(Exception.class,()->new JavaLibraryProvider(List.of(source,source),null,new CompilerOptions()));
    }
}
