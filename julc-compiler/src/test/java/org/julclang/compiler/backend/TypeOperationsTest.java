package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.backend.LibraryType.Operation;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.julclang.compiler.backend.FunctionProgramTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Approved imported-type operations and codecs (#183). */
class TypeOperationsTest {
    static final String MARKET = """
            package demo;
            import java.math.BigInteger;
            import org.julclang.ledger.Value;
            @OnchainLibrary
            public class Market {
                public record Quote(BigInteger amount, boolean approved) {}
                @NewType public record Price(BigInteger value) {}
                @NewType public record Quantity(BigInteger value) {}
                public sealed interface Action {
                    record Buy(BigInteger amount) implements Action {}
                    record Cancel() implements Action {}
                }
                public record Listing(BigInteger id, Value price) {}
                public static BigInteger actionAmount(Action action) {
                    return switch (action) {
                        case Action.Buy b -> b.amount();
                        case Action.Cancel c -> BigInteger.ZERO;
                    };
                }
                public static boolean sameQuote(Quote a, Quote b) { return a.equals(b); }
                public static BigInteger quoteAmount(Quote quote) { return quote.amount(); }
                public static boolean quoteApproved(Quote quote) { return quote.approved(); }
            }
            """;
    static final JavaLibraryProvider PROVIDER =
            new JavaLibraryProvider(List.of(MARKET), null, new CompilerOptions());

    static LibraryRequest.Operation op(String type, Operation operation) {
        return new LibraryRequest.Operation(type, operation);
    }

    static LibraryRequest.Operation op(String type, Operation operation, String member) {
        return new LibraryRequest.Operation(type, operation, member);
    }

    /** Evaluate a closed Data- or scalar-valued function program over imports. */
    static EvalResult run(PirTerm term, PirType type, LibraryImports group) {
        return CompilerTestVm.pv11().evaluate(FunctionProgramTest.compile(
                program(term, type, new LinkedHashMap<>(), List.of(group))).program());
    }

    static Term result(PirTerm term, PirType type, LibraryImports group) {
        return assertInstanceOf(EvalResult.Success.class, run(term, type, group)).resultTerm();
    }

    static PirTerm ref(LibraryImports group, LibraryRequest request) {
        var binding = group.binding(request);
        return new PirTerm.Var(binding.name(), binding.type());
    }

    static PirTerm data(PlutusData value) {
        return new PirTerm.Const(Constant.data(value));
    }

    @Test
    void approvedOperationsFollowVerifiedRepresentations() {
        var types = PROVIDER.types();
        assertEquals(EnumSet.allOf(Operation.class), types.get("demo.Market.Quote").operations());
        assertEquals(EnumSet.complementOf(EnumSet.of(Operation.PROJECT)),
                types.get("demo.Market.Action").operations());
        var newtype = EnumSet.complementOf(EnumSet.of(Operation.MATCH));
        assertEquals(newtype, types.get("demo.Market.Price").operations());
        assertEquals(newtype, types.get("org.julclang.ledger.PubKeyHash").operations());
        // Value's runtime encoding is a map: no operations, and records containing it have
        // no equality or strict codec.
        assertEquals(Set.of(), types.get("org.julclang.ledger.Value").operations());
        var containsValue = EnumSet.of(Operation.CONSTRUCT, Operation.PROJECT, Operation.MATCH, Operation.ENCODE);
        assertEquals(containsValue, types.get("demo.Market.Listing").operations());
        assertEquals(containsValue, types.get("org.julclang.ledger.TxOut").operations());
        assertEquals(EnumSet.complementOf(EnumSet.of(Operation.PROJECT)),
                types.get("org.julclang.ledger.Credential").operations());
    }

    @Test
    void recordsRoundTripThroughApprovedOperations() {
        String quote = "demo.Market.Quote";
        var construct = op(quote, Operation.CONSTRUCT);
        var amount = op(quote, Operation.PROJECT, "amount");
        var approved = op(quote, Operation.PROJECT, "approved");
        var encode = op(quote, Operation.ENCODE);
        var decode = op(quote, Operation.DECODE_STRICT);
        var equals = op(quote, Operation.EQUALS);
        var group = PROVIDER.materialize(List.of(construct, amount, approved, encode, decode, equals));
        var built = apply(ref(group, construct), integer(42), new PirTerm.Const(Constant.bool(true)));
        assertEquals(new Term.Const(Constant.integer(42)), result(apply(ref(group, amount), built), INT, group));
        assertEquals(new Term.Const(Constant.bool(true)), result(apply(ref(group, approved), built), BOOL, group));
        // Fixed encoding: Quote(5, true) is Constr 0 [I 5, Constr 1 []].
        var fixed = apply(ref(group, encode), apply(ref(group, construct), integer(5),
                new PirTerm.Const(Constant.bool(true))));
        assertEquals(new Term.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(5), PlutusData.constr(1)))),
                result(fixed, DATA, group));
        var roundTrip = apply(ref(group, equals), apply(ref(group, decode), apply(ref(group, encode), built)), built);
        assertEquals(new Term.Const(Constant.bool(true)), result(roundTrip, BOOL, group));
    }

    @Test
    void strictDecodingRejectsWrongTagsArityAndNestedKinds() {
        var decode = op("demo.Market.Quote", Operation.DECODE_STRICT);
        var amount = op("demo.Market.Quote", Operation.PROJECT, "amount");
        var group = PROVIDER.materialize(List.of(decode, amount));
        var valid = PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(0));
        assertEquals(new Term.Const(Constant.integer(1)),
                result(apply(ref(group, amount), apply(ref(group, decode), data(valid))), INT, group));
        for (var malformed : List.of(
                PlutusData.constr(1, PlutusData.integer(1), PlutusData.constr(0)),
                PlutusData.constr(0, PlutusData.integer(1)),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(0), PlutusData.integer(2)),
                PlutusData.constr(0, PlutusData.bytes(new byte[0]), PlutusData.constr(0)),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(5)),
                PlutusData.integer(1)))
            assertInstanceOf(EvalResult.Failure.class,
                    run(apply(ref(group, amount), apply(ref(group, decode), data(malformed))), INT, group),
                    malformed.toString());
    }

    @Test
    void sumsAreConstructedMatchedAndDecodedByTag() {
        String action = "demo.Market.Action";
        var buy = op(action, Operation.CONSTRUCT, "Buy");
        var cancel = op(action, Operation.CONSTRUCT, "Cancel");
        var decode = op(action, Operation.DECODE_STRICT);
        var amountExport = new LibraryRequest.Export("demo.Market.actionAmount");
        var group = PROVIDER.materialize(List.of(buy, cancel, decode, amountExport));
        var sum = (PirType.SumType) PROVIDER.types().get(action).representation();
        var match = new PirTerm.DataMatch(apply(ref(group, buy), integer(5)), List.of(
                new PirTerm.MatchBranch("Buy", List.of("n"), List.of(INT), var("n", INT)),
                new PirTerm.MatchBranch("Cancel", List.of(), List.of(), integer(0))));
        assertEquals(new Term.Const(Constant.integer(5)), result(match, INT, group));
        // The Java library agrees with the neutral constructor.
        assertEquals(new Term.Const(Constant.integer(0)),
                result(apply(ref(group, amountExport), ref(group, cancel)), INT, group));
        assertEquals(new Term.Const(Constant.integer(9)), result(apply(ref(group, amountExport),
                apply(ref(group, decode), data(PlutusData.constr(0, PlutusData.integer(9))))), INT, group));
        for (var malformed : List.of(PlutusData.constr(2), PlutusData.constr(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[0])), PlutusData.constr(1, PlutusData.integer(1))))
            assertInstanceOf(EvalResult.Failure.class, run(apply(ref(group, amountExport),
                    apply(ref(group, decode), data(malformed))), INT, group), malformed.toString());
        assertEquals(List.of("Buy", "Cancel"), sum.constructors().stream().map(PirType.Constructor::name).toList());
    }

    @Test
    void equalityAndProjectionAgreeWithTheJavaLibrary() {
        String quote = "demo.Market.Quote";
        var construct = op(quote, Operation.CONSTRUCT);
        var equals = op(quote, Operation.EQUALS);
        var amount = op(quote, Operation.PROJECT, "amount");
        var javaSame = new LibraryRequest.Export("demo.Market.sameQuote");
        var javaAmount = new LibraryRequest.Export("demo.Market.quoteAmount");
        var group = PROVIDER.materialize(List.of(construct, equals, amount, javaSame, javaAmount));
        long[][] pairs = {{1, 1}, {1, 2}, {0, 0}, {-3, -3}};
        for (var pair : pairs)
            for (boolean flag : new boolean[] {true, false}) {
                var a = apply(ref(group, construct), integer(pair[0]), new PirTerm.Const(Constant.bool(true)));
                var b = apply(ref(group, construct), integer(pair[1]), new PirTerm.Const(Constant.bool(flag)));
                assertEquals(result(apply(ref(group, javaSame), a, b), BOOL, group),
                        result(apply(ref(group, equals), a, b), BOOL, group));
                assertEquals(result(apply(ref(group, javaAmount), b), INT, group),
                        result(apply(ref(group, amount), b), INT, group));
            }
    }

    @Test
    void ledgerSumsEncodeLikeTheLedgerApi() {
        String credential = "org.julclang.ledger.Credential";
        var pubKey = op(credential, Operation.CONSTRUCT, "PubKeyCredential");
        var encode = op(credential, Operation.ENCODE);
        var group = PROVIDER.materialize(List.of(pubKey, encode));
        var hash = new byte[28];
        hash[0] = 7;
        var built = apply(ref(group, encode), apply(ref(group, pubKey),
                new PirTerm.Const(Constant.byteString(hash))));
        assertEquals(new Term.Const(Constant.data(new Credential.PubKeyCredential(new PubKeyHash(hash)).toPlutusData())),
                result(built, DATA, group));
    }

    @Test
    void containerCodecsConvertBetweenComputationalAndDataForms() {
        var quote = new PirType.NamedTypeRef("demo.Market.Quote", "Quote", PirType.NamedKind.RECORD);
        var quotes = new PirType.ListType(quote);
        var encode = new LibraryRequest.Codec(quotes, LibraryRequest.Codec.Direction.ENCODE);
        var decode = new LibraryRequest.Codec(quotes, LibraryRequest.Codec.Direction.DECODE_STRICT);
        var byName = new PirType.MapType(BYTES, INT);
        var mapDecode = new LibraryRequest.Codec(byName, LibraryRequest.Codec.Direction.DECODE_STRICT);
        var group = PROVIDER.materialize(List.of(encode, decode, mapDecode));
        var items = PlutusData.list(PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.integer(2), PlutusData.constr(0)));
        // Decoding yields the computational builtin list; encoding restores the same Data.
        var roundTrip = apply(ref(group, encode), apply(ref(group, decode), data(items)));
        assertEquals(new Term.Const(Constant.data(items)), result(roundTrip, DATA, group));
        var length = call(DefaultFun.NullList, apply(ref(group, decode), data(items)));
        assertEquals(new Term.Const(Constant.bool(false)), result(length, BOOL, group));
        assertInstanceOf(EvalResult.Failure.class, run(apply(ref(group, decode),
                data(PlutusData.list(PlutusData.constr(0, PlutusData.integer(1))))), quotes, group));
        var goodMap = PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[1]), PlutusData.integer(3)));
        var badMap = PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[1]), PlutusData.bytes(new byte[1])));
        assertInstanceOf(EvalResult.Success.class, run(apply(ref(group, mapDecode), data(goodMap)), byName, group));
        assertInstanceOf(EvalResult.Failure.class, run(apply(ref(group, mapDecode), data(badMap)), byName, group));
    }

    @Test
    void specialLayoutsStayOpaque() {
        for (var request : List.of(op("org.julclang.ledger.Value", Operation.PROJECT, "inner"),
                op("org.julclang.ledger.Value", Operation.EQUALS),
                op("org.julclang.ledger.Value", Operation.DECODE_STRICT),
                op("demo.Market.Listing", Operation.EQUALS),
                op("demo.Market.Listing", Operation.DECODE_STRICT),
                op("demo.Market.Price", Operation.MATCH),
                op("demo.Market.Missing", Operation.CONSTRUCT),
                op("demo.Market.Quote", Operation.PROJECT, "missing"),
                op("demo.Market.Quote", Operation.ENCODE, "amount"),
                op("demo.Market.Action", Operation.CONSTRUCT)))
            assertEquals("JULC0051", assertThrows(BackendException.class,
                    () -> PROVIDER.materialize(List.of(request))).code(), request.describe());
        var listing = new PirType.NamedTypeRef("demo.Market.Listing", "Listing", PirType.NamedKind.RECORD);
        assertEquals("JULC0051", assertThrows(BackendException.class, () -> PROVIDER.materialize(List.of(
                new LibraryRequest.Codec(new PirType.ListType(listing), LibraryRequest.Codec.Direction.DECODE_STRICT))))
                .code());
        // Producer PIR may neither match nor construct the map-encoded Value.
        var group = PROVIDER.materialize(List.of(op("demo.Market.Quote", Operation.CONSTRUCT)));
        var value = PROVIDER.types().get("org.julclang.ledger.Value").representation();
        rejects("JULC0050", program(new PirTerm.DataMatch(new PirTerm.Error(value), List.of(
                new PirTerm.MatchBranch("Value", List.of(), List.of(), integer(0)))), INT,
                new LinkedHashMap<>(), List.of(group)));
        rejects("JULC0050", program(new PirTerm.DataConstr(0, value, List.of(new PirTerm.Error(
                ((PirType.RecordType) value).fields().getFirst().type()))), value, new LinkedHashMap<>(), List.of(group)));
    }

    @Test
    void boundariesRejectTypesWhoseEncodingTheCheckerCannotValidate() {
        var listing = PROVIDER.types().get("demo.Market.Listing").representation();
        var group = PROVIDER.materialize(List.of(op("demo.Market.Quote", Operation.CONSTRUCT)));
        var handler = ValidatorProgram.Handler.of(Purpose.MINT, "m", new PirTerm.Lam("l", listing,
                new PirTerm.Lam("c", DATA, new PirTerm.Const(Constant.bool(true)))),
                ValidatorProgramTest.fn(listing, DATA, BOOL));
        var program = new ValidatorProgram(2, Set.of(), "v", CompilerTarget.PLUTUS_V3_PV11,
                BackendContract.STRICT_BOUNDARY_V1, Map.of(), List.of(group), new LinkedHashMap<>(), List.of(),
                List.of(handler));
        var error = assertThrows(BackendException.class,
                () -> new CompilerBackend().compile(program, new CompilerOptions()));
        assertEquals("JULC0047", error.code());
        assertTrue(error.getMessage().contains("record Listing"), error.getMessage());
        // A producer's own record with a ledger Value field is rejected without any import.
        var value = PROVIDER.types().get("org.julclang.ledger.Value").representation();
        var order = new PirType.RecordType("Order", List.of(new PirType.Field("price", value)));
        var own = new ValidatorProgram(2, Set.of(), "v", CompilerTarget.PLUTUS_V3_PV11,
                BackendContract.STRICT_BOUNDARY_V1, Map.of(), List.of(), new LinkedHashMap<>(), List.of(),
                List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m", new PirTerm.Lam("o", order,
                        new PirTerm.Lam("c", DATA, new PirTerm.Const(Constant.bool(true)))),
                        ValidatorProgramTest.fn(order, DATA, BOOL))));
        var ownError = assertThrows(BackendException.class,
                () -> new CompilerBackend().compile(own, new CompilerOptions()));
        assertTrue(ownError.getMessage().contains("record Value"), ownError.getMessage());
        assertEquals("JULC0047", assertThrows(BackendException.class,
                () -> BoundaryPrograms.check(order, Map.of(), new CompilerOptions())).code());
    }

    @Test
    void incompatibleNominalLayoutsAreRejected() {
        var project = op("demo.Market.Quote", Operation.PROJECT, "amount");
        var buy = op("demo.Market.Action", Operation.CONSTRUCT, "Buy");
        var group = PROVIDER.materialize(List.of(project, buy));
        rejects("JULC0049", program(apply(ref(group, project), apply(ref(group, buy), integer(1))), INT,
                new LinkedHashMap<>(), List.of(group)));
    }

    @Test
    void repeatedRequestsInSeparateGroupsAreLinkedOnce() {
        var construct = op("demo.Market.Quote", Operation.CONSTRUCT);
        var amount = op("demo.Market.Quote", Operation.PROJECT, "amount");
        var first = PROVIDER.materialize(List.of(construct, amount));
        var second = PROVIDER.materialize(List.of(construct));
        var term = apply(ref(first, amount), apply(ref(second, construct), integer(3),
                new PirTerm.Const(Constant.bool(false))));
        evaluatesTo(program(term, INT, new LinkedHashMap<>(), List.of(first, second)), Constant.integer(3));
    }
}
