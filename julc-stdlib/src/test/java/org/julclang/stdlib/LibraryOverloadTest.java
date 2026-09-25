package org.julclang.stdlib;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.ledger.ScriptContextBuilder;
import org.julclang.ledger.PolicyId;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Typed map templates specialized at key and value types (ADR-059 generic exports). */
/** Library overloads: identical signatures export once; conflicts disable only their class. */
class LibraryOverloadTest {
    static final String BYTES = "org.julclang.stdlib.lib.ByteStringLib";
    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTESTRING = new PirType.ByteStringType();

    static JavaLibraryProvider provider(String... owners) {
        return TypedMapLibTest.provider(owners);
    }

    static PirTerm ref(LibraryImports group, LibraryRequest request) {
        return TypedMapLibTest.ref(group, request);
    }

    static PirTerm apply(PirTerm function, PirTerm... arguments) {
        return TypedMapLibTest.apply(function, arguments);
    }

    static Term value(PirTerm term, PirType type, LibraryImports... groups) {
        return TypedMapLibTest.value(term, type, groups);
    }

    @Test
    void byteStringLibIsExportedDespiteItsIdenticalOverloads() {
        var provider = provider(BYTES);
        var names = provider.describe(BYTES).stream().map(e -> e.symbol().substring(BYTES.length() + 1)).toList();
        for (var expected : List.of("at", "cons", "slice", "take", "drop", "zeros", "lessThan", "integerToByteString",
                "byteStringToInteger", "toHex", "intToDecimalString"))
            assertTrue(names.contains(expected), expected + " in " + names);
        assertEquals(1, names.stream().filter("integerToByteString"::equals).count());
        var toBytes = new LibraryRequest.Export(BYTES + ".integerToByteString");
        var toInt = new LibraryRequest.Export(BYTES + ".byteStringToInteger");
        var group = provider.materialize(List.of(toBytes, toInt));
        var big = new PirTerm.Const(Constant.bool(true));
        var encoded = apply(ref(group, toBytes), big, new PirTerm.Const(Constant.integer(4)),
                new PirTerm.Const(Constant.integer(258)));
        assertEquals(new Term.Const(Constant.byteString(new byte[] {0, 0, 1, 2})), value(encoded, BYTESTRING, group));
        assertEquals(new Term.Const(Constant.integer(258)), value(apply(ref(group, toInt), big, encoded), INT, group));
    }

    @Test
    void conflictingOverloadsDisableOnlyTheirClass() {
        var conflicting = """
                package demo;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Clash {
                    public static BigInteger pick(BigInteger a) { return a; }
                    public static boolean pick(boolean a) { return a; }
                    public static BigInteger other(BigInteger a) { return a; }
                }
                """;
        var fine = """
                package demo;
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Fine {
                    public static BigInteger twice(BigInteger a) { return a.add(a); }
                }
                """;
        var provider = new JavaLibraryProvider(List.of(conflicting, fine), StdlibRegistry.defaultRegistry(),
                new CompilerOptions());
        assertTrue(provider.describe("demo.Clash").isEmpty());
        assertTrue(provider.unsupportedExports().get("demo.Clash.pick").contains("different signatures"));
        assertTrue(provider.unsupportedExports().containsKey("demo.Clash.other"));
        assertEquals(List.of("demo.Fine.twice"), provider.describe("demo.Fine").stream().map(LibraryExport::symbol).toList());
    }
}
