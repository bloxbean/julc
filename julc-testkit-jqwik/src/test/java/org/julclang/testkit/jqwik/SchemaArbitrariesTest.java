package org.julclang.testkit.jqwik;

import net.jqwik.api.*;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.backend.DatumProfile;
import org.julclang.compiler.backend.Parameter;
import org.julclang.compiler.backend.PirBackend;
import org.julclang.compiler.backend.ValidatorAbi;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Values from {@link SchemaArbitraries} are valid Data encodings of their types. */
class SchemaArbitrariesTest {
    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTES = new PirType.ByteStringType();
    static final PirType STRING = new PirType.StringType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();

    static final PirType.NamedTypeRef TREE = new PirType.NamedTypeRef("test.Tree", "Tree", PirType.NamedKind.SUM);
    static final Map<String, PirType> NAMED = Map.of(TREE.stableId(), new PirType.SumType("Tree", List.of(
            new PirType.Constructor("Leaf", 0, List.of(field("value", INT))),
            new PirType.Constructor("Node", 1, List.of(field("left", TREE), field("children", new PirType.ListType(TREE)))))));
    static final PirType ACTION = new PirType.SumType("Action", List.of(
            new PirType.Constructor("Close", 0, List.of()),
            new PirType.Constructor("Deposit", 1, List.of(field("amount", INT))),
            new PirType.Constructor("Pay", 3, List.of(field("to", BYTES), field("memo", new PirType.OptionalType(STRING))))));
    static final PirType ORDER = new PirType.RecordType("Order", List.of(
            field("owner", BYTES), field("amounts", new PirType.ListType(INT)), field("open", BOOL), field("action", ACTION),
            field("limits", new PirType.MapType(BOOL, new PirType.ListType(new PirType.OptionalType(INT)))),
            field("extra", DATA), field("tree", TREE), field("unit", new PirType.UnitType())));

    static final ValidatorAbi ABI = new ValidatorAbi("test.escrow", CompilerTarget.PLUTUS_V3_PV11, "strict",
            List.of(new Parameter("owner", BYTES), new Parameter("limit", INT)),
            List.of(new ValidatorAbi.HandlerAbi(ContractSchema.Purpose.SPEND, 1, "spend", DatumProfile.REQUIRED, ORDER, ACTION),
                    new ValidatorAbi.HandlerAbi(ContractSchema.Purpose.MINT, 0, "mint", DatumProfile.ABSENT, null, TREE)),
            NAMED);

    static PirType.Field field(String name, PirType type) {
        return new PirType.Field(name, type);
    }

    static final Map<PirType, Program> CHECKS = new HashMap<>();

    /** The backend's strict boundary check for {@code type}, as a function {@code Data -> Bool}. */
    static boolean conforms(PirType type, PlutusData value) {
        var check = CHECKS.computeIfAbsent(type, t -> PirBackend.lower(new PirTerm.Lam("candidate", DATA,
                        new StrictBoundaryGenerator(NAMED).check(new PirTerm.Var("candidate", DATA), t)),
                CompilationContext.resolve(new CompilerOptions()), null, true).program());
        var result = JulcVm.create().evaluate(check.applyParams(value));
        var success = assertInstanceOf(EvalResult.Success.class, result, result::toString);
        return success.resultTerm() instanceof Term.Const(Constant.BoolConst(boolean ok)) && ok;
    }

    @Property(tries = 150)
    void ordersAreValid(@ForAll("orders") PlutusData order) {
        assertTrue(conforms(ORDER, order), order::toString);
    }

    @Provide
    Arbitrary<PlutusData> orders() {
        return SchemaArbitraries.forType(ORDER, NAMED);
    }

    @Property(tries = 150)
    void recursiveValuesAreValidAndBounded(@ForAll("trees") PlutusData tree) {
        assertTrue(conforms(TREE, tree), tree::toString);
        assertTrue(depth(tree) <= SchemaArbitraries.DEFAULT_DEPTH + 2, tree::toString);
    }

    @Provide
    Arbitrary<PlutusData> trees() {
        return SchemaArbitraries.forType(TREE, NAMED);
    }

    static int depth(PlutusData value) {
        return switch (value) {
            case PlutusData.ConstrData constr -> 1 + constr.fields().stream().mapToInt(SchemaArbitrariesTest::depth).max().orElse(0);
            case PlutusData.ListData list -> list.items().stream().mapToInt(SchemaArbitrariesTest::depth).max().orElse(0);
            default -> 0;
        };
    }

    @Property(tries = 100)
    void mapKeysAreDistinct(@ForAll("boolMaps") PlutusData map) {
        var entries = ((PlutusData.MapData) map).entries();
        assertTrue(entries.size() <= 2);
        assertEquals(entries.size(), entries.stream().map(PlutusData.Pair::key).distinct().count());
    }

    @Provide
    Arbitrary<PlutusData> boolMaps() {
        return SchemaArbitraries.forType(new PirType.MapType(BOOL, INT));
    }

    @Property(tries = 100)
    void abiBoundariesAreValid(@ForAll("redeemers") PlutusData redeemer, @ForAll("datums") PlutusData datum,
                               @ForAll("mintRedeemers") PlutusData mint, @ForAll("parameters") List<PlutusData> parameters) {
        assertTrue(conforms(ACTION, redeemer));
        assertTrue(conforms(ORDER, datum));
        assertTrue(conforms(TREE, mint));
        assertEquals(2, parameters.size());
        assertInstanceOf(PlutusData.BytesData.class, parameters.get(0));
        assertInstanceOf(PlutusData.IntData.class, parameters.get(1));
    }

    @Provide
    Arbitrary<PlutusData> redeemers() {
        return SchemaArbitraries.redeemer(ABI, ContractSchema.Purpose.SPEND);
    }

    @Provide
    Arbitrary<PlutusData> datums() {
        return SchemaArbitraries.datum(ABI);
    }

    @Provide
    Arbitrary<PlutusData> mintRedeemers() {
        return SchemaArbitraries.redeemer(ABI, ContractSchema.Purpose.MINT);
    }

    @Provide
    Arbitrary<List<PlutusData>> parameters() {
        return SchemaArbitraries.parameters(ABI);
    }

    @Property(tries = 50)
    void overridesReplaceNamedTypes(@ForAll("hashedOrders") PlutusData order) {
        var owner = ((PlutusData.ConstrData) order).fields().getFirst();
        assertEquals(28, ((PlutusData.BytesData) owner).size());
    }

    @Provide
    Arbitrary<PlutusData> hashedOrders() {
        var keyHash = new PirType.NamedTypeRef("test.KeyHash", "KeyHash", PirType.NamedKind.RECORD);
        var order = new PirType.RecordType("Order", List.of(field("owner", keyHash), field("amount", INT)));
        var named = Map.<String, PirType>of(keyHash.stableId(), new PirType.RecordType("KeyHash", List.of(field("hash", BYTES))));
        return SchemaArbitraries.forType(order, named, Map.of("KeyHash",
                Arbitraries.bytes().array(byte[].class).ofSize(28).map(PlutusData.BytesData::new)));
    }

    @Example
    void unsupportedTypesAreRejected() {
        for (var type : List.of(new PirType.PairType(INT, INT), new PirType.ArrayType(INT), new PirType.FunType(INT, INT),
                new PirType.ListType(new PirType.PairType(INT, INT))))
            assertThrows(IllegalArgumentException.class, () -> SchemaArbitraries.forType(type), type::toString);
        assertThrows(IllegalArgumentException.class, () -> SchemaArbitraries.datum(
                new ValidatorAbi("test.policy", CompilerTarget.PLUTUS_V3_PV11, "strict", List.of(),
                        List.of(ABI.handlers().get(1)), NAMED)));
    }
}
