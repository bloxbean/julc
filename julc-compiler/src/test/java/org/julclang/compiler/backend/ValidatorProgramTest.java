package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.ledger.*;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Revision-2 validators: purpose dispatch, boundaries, parity with Java and rejections. */
class ValidatorProgramTest {
    static final PirType INT = new PirType.IntegerType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();
    static final PirType UNIT = new PirType.UnitType();
    static final PirType.RecordType ACTION = new PirType.RecordType("Action",
            List.of(new PirType.Field("amount", INT)));
    static final Map<String, Integer> EXPECTED = Map.of(
            "MINT", 10, "SPEND", 11, "WITHDRAW", 12, "CERTIFY", 13, "VOTE", 14, "PROPOSE", 15);

    // ---- PIR helpers ----

    static PirTerm integer(long n) {
        return new PirTerm.Const(Constant.integer(n));
    }

    static PirTerm call(DefaultFun fun, PirTerm... arguments) {
        PirTerm term = new PirTerm.Builtin(fun);
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    static PirType fn(PirType... types) {
        PirType result = types[types.length - 1];
        for (int i = types.length - 2; i >= 0; i--) result = new PirType.FunType(types[i], result);
        return result;
    }

    /** {@code \redeemer:Int -> \ctx:Data -> redeemer == expected}. */
    static ValidatorProgram.Handler equalsHandler(Purpose purpose, long expected) {
        var term = new PirTerm.Lam("redeemer", INT, new PirTerm.Lam("ctx", DATA,
                call(DefaultFun.EqualsInteger, new PirTerm.Var("redeemer", INT), integer(expected))));
        return ValidatorProgram.Handler.of(purpose, "test." + purpose.name().toLowerCase(), term,
                fn(INT, DATA, BOOL));
    }

    /** {@code \datum:Int -> \redeemer:Int -> \ctx -> datum <= redeemer}. */
    static ValidatorProgram.Handler spendHandler() {
        var term = new PirTerm.Lam("datum", INT, new PirTerm.Lam("redeemer", INT, new PirTerm.Lam("ctx", DATA,
                call(DefaultFun.LessThanEqualsInteger, new PirTerm.Var("datum", INT),
                        new PirTerm.Var("redeemer", INT)))));
        return ValidatorProgram.Handler.spending("test.spend", term, fn(INT, INT, DATA, BOOL));
    }

    static ValidatorProgram program(List<ValidatorProgram.Handler> handlers) {
        return program(Map.of(), handlers);
    }

    static ValidatorProgram program(Map<String, PirType> namedTypes, List<ValidatorProgram.Handler> handlers) {
        return new ValidatorProgram(BackendContract.REVISION, Set.of(), "test.validator",
                CompilerTarget.PLUTUS_V3_PV11, BackendContract.STRICT_BOUNDARY_V1, namedTypes, List.of(),
                new LinkedHashMap<>(), List.of(), handlers);
    }

    static ValidatorResult compile(ValidatorProgram program) {
        return new CompilerBackend().compile(program, new CompilerOptions());
    }

    static BackendException rejects(String code, ValidatorProgram program) {
        var error = assertThrows(BackendException.class, () -> compile(program));
        assertEquals(code, error.code(), error.getMessage());
        return error;
    }

    // ---- ledger contexts ----

    static final TxOutRef REF = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    static final Credential CREDENTIAL = new Credential.ScriptCredential(new ScriptHash(new byte[28]));

    static ScriptContextBuilder builder(Purpose purpose, PlutusData datum) {
        return switch (purpose) {
            case SPEND -> datum == null ? ScriptContextBuilder.spending(REF)
                    : ScriptContextBuilder.spending(REF, datum);
            case MINT -> ScriptContextBuilder.minting(new PolicyId(new byte[28]));
            case WITHDRAW -> ScriptContextBuilder.rewarding(CREDENTIAL);
            case CERTIFY -> ScriptContextBuilder.certifying(BigInteger.ZERO,
                    new TxCert.RegStaking(CREDENTIAL, Optional.empty()));
            case VOTE -> ScriptContextBuilder.voting(new Voter.DRepVoter(CREDENTIAL));
            case PROPOSE -> ScriptContextBuilder.proposing(BigInteger.ZERO,
                    new ProposalProcedure(BigInteger.TWO, CREDENTIAL, new GovernanceAction.InfoAction()));
        };
    }

    static PlutusData context(Purpose purpose, PlutusData redeemer, PlutusData datum) {
        return builder(purpose, datum).redeemer(redeemer).build().toPlutusData();
    }

    static boolean accepts(Program program, PlutusData context) {
        return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(context)) instanceof EvalResult.Success;
    }

    static PlutusData integerData(long n) {
        return PlutusData.integer(n);
    }

    @Nested
    class Dispatch {
        @Test
        void everyPurposeDispatchesToItsHandlerAlone() {
            for (var purpose : Purpose.values()) {
                if (purpose == Purpose.SPEND) continue;
                var program = compile(program(List.of(equalsHandler(purpose, EXPECTED.get(purpose.name())))))
                        .program();
                long expected = EXPECTED.get(purpose.name());
                assertTrue(accepts(program, context(purpose, integerData(expected), null)), purpose.name());
                assertFalse(accepts(program, context(purpose, integerData(expected + 1), null)), purpose.name());
                assertFalse(accepts(program, context(purpose, PlutusData.bytes(new byte[1]), null)), purpose.name());
                // A purpose without a handler fails even when its redeemer would satisfy this one.
                for (var other : Purpose.values())
                    if (other != purpose)
                        assertFalse(accepts(program, context(other, integerData(expected), integerData(0))),
                                purpose + " script must reject a " + other + " context");
            }
        }

        @Test
        void spendingRequiresAWellFormedDatum() {
            var program = compile(program(List.of(spendHandler()))).program();
            assertTrue(accepts(program, context(Purpose.SPEND, integerData(5), integerData(4))));
            assertFalse(accepts(program, context(Purpose.SPEND, integerData(3), integerData(4))));
            assertFalse(accepts(program, context(Purpose.SPEND, integerData(5), null)), "missing datum");
            assertFalse(accepts(program, context(Purpose.SPEND, integerData(5), PlutusData.bytes(new byte[0]))));
            assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.list(), integerData(4))));
            assertFalse(accepts(program, context(Purpose.MINT, integerData(5), null)));
        }

        @Test
        void spendingWithMintingAndWithdrawalShareOneScript() {
            for (var second : List.of(Purpose.MINT, Purpose.WITHDRAW)) {
                long expected = EXPECTED.get(second.name());
                var program = compile(program(List.of(spendHandler(), equalsHandler(second, expected))))
                        .program();
                assertTrue(accepts(program, context(Purpose.SPEND, integerData(9), integerData(1))));
                assertFalse(accepts(program, context(Purpose.SPEND, integerData(0), integerData(1))));
                assertTrue(accepts(program, context(second, integerData(expected), null)));
                assertFalse(accepts(program, context(second, integerData(expected + 1), null)));
                assertFalse(accepts(program, context(Purpose.CERTIFY, integerData(expected), null)));
            }
        }

        @Test
        void onlyTheSelectedHandlerDecodesItsBoundary() {
            // SPEND expects an Action record; WITHDRAW expects an integer. Neither schema
            // may reject a transaction for the other purpose.
            var spend = ValidatorProgram.Handler.spending("test.spend",
                    new PirTerm.Lam("datum", INT, new PirTerm.Lam("action", ACTION,
                            new PirTerm.Lam("ctx", DATA, new PirTerm.Const(Constant.bool(true))))),
                    fn(INT, ACTION, DATA, BOOL));
            var program = compile(program(List.of(spend, equalsHandler(Purpose.WITHDRAW, 12)))).program();
            assertTrue(accepts(program, context(Purpose.WITHDRAW, integerData(12), null)));
            var action = PlutusData.constr(0, integerData(1));
            assertTrue(accepts(program, context(Purpose.SPEND, action, integerData(0))));
            assertFalse(accepts(program, context(Purpose.SPEND, integerData(12), integerData(0))));
            assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.constr(1, integerData(1)), integerData(0))));
            assertFalse(accepts(program, context(Purpose.SPEND, PlutusData.constr(0), integerData(0))));
            assertFalse(accepts(program, context(Purpose.WITHDRAW, action, null)));
        }

        @Test
        void handlerOrderDoesNotChangeTheScript() {
            var handlers = new ArrayList<>(List.of(spendHandler(), equalsHandler(Purpose.MINT, 10),
                    equalsHandler(Purpose.VOTE, 14)));
            var first = UplcFlatEncoder.encodeProgram(compile(program(handlers)).program());
            Collections.reverse(handlers);
            var second = UplcFlatEncoder.encodeProgram(compile(program(handlers)).program());
            assertArrayEquals(first, second);
        }

        @Test
        void unitRedeemersReachTheHandlerAsNativeUnit() {
            var term = new PirTerm.Lam("unit", UNIT, new PirTerm.Lam("ctx", DATA,
                    call(DefaultFun.ChooseUnit, new PirTerm.Var("unit", UNIT), new PirTerm.Const(Constant.bool(true)))));
            var program = compile(program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "test.unit", term,
                    fn(UNIT, DATA, BOOL))))).program();
            assertTrue(accepts(program, context(Purpose.MINT, PlutusData.constr(0), null)));
            assertFalse(accepts(program, context(Purpose.MINT, PlutusData.constr(1), null)));
            assertFalse(accepts(program, context(Purpose.MINT, integerData(0), null)));
        }

        @Test
        void contextMayBeTypedAsTheLedgerScriptContext() {
            var contextType = new LedgerTypeProvider().types().get("org.julclang.ledger.ScriptContext")
                    .representation();
            // Read the redeemer (ScriptContext field 1) from the typed context.
            var redeemer = call(DefaultFun.UnIData, call(DefaultFun.HeadList, call(DefaultFun.TailList,
                    call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, new PirTerm.Var("ctx", contextType))))));
            var term = new PirTerm.Lam("r", INT, new PirTerm.Lam("ctx", contextType,
                    call(DefaultFun.EqualsInteger, redeemer, integer(10))));
            var program = compile(program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "test.typed", term,
                    fn(INT, contextType, BOOL))))).program();
            assertTrue(accepts(program, context(Purpose.MINT, integerData(10), null)));
            assertFalse(accepts(program, context(Purpose.MINT, integerData(11), null)));
        }

        @Test
        void abiRecordsRolesInTagOrder() {
            var result = compile(program(List.of(equalsHandler(Purpose.VOTE, 14), spendHandler(),
                    equalsHandler(Purpose.MINT, 10))));
            var abi = result.abi();
            assertEquals("test.validator", abi.identity());
            assertEquals(CompilerTarget.PLUTUS_V3_PV11, abi.target());
            assertEquals(List.of(Purpose.MINT, Purpose.SPEND, Purpose.VOTE),
                    abi.handlers().stream().map(ValidatorAbi.HandlerAbi::purpose).toList());
            assertEquals(List.of(0, 1, 4), abi.handlers().stream().map(ValidatorAbi.HandlerAbi::tag).toList());
            var spend = abi.handlers().get(1);
            assertEquals(DatumProfile.REQUIRED, spend.datum());
            assertEquals(INT, spend.datumType());
            assertEquals(INT, spend.redeemerType());
            assertNull(abi.handlers().get(0).datumType());
            assertTrue(abi.parameters().isEmpty());
        }

        @Test
        void ledgerTagsMatchScriptInfoEncodings() {
            for (var purpose : Purpose.values()) {
                var scriptInfo = (PlutusData.ConstrData) ((PlutusData.ConstrData) context(purpose,
                        integerData(0), integerData(0))).fields().get(2);
                assertEquals(scriptInfo.tag(), BackendContract.ledgerTag(purpose), purpose.name());
            }
        }
    }

    @Nested
    class JavaParity {
        static final String SOURCE = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;
                @MultiValidator
                class Parity {
                    record Action(BigInteger amount) {}
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(BigInteger datum, Action action, ScriptContext ctx) {
                        return datum.compareTo(action.amount()) <= 0;
                    }
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(BigInteger redeemer, ScriptContext ctx) {
                        return redeemer.equals(BigInteger.TEN);
                    }
                    @Entrypoint(purpose = Purpose.WITHDRAW)
                    static boolean withdraw(BigInteger redeemer, ScriptContext ctx) {
                        return redeemer.signum() > 0;
                    }
                }
                """;

        static ValidatorProgram neutral() {
            var amount = call(DefaultFun.UnIData, call(DefaultFun.HeadList,
                    call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, new PirTerm.Var("action", ACTION)))));
            var spend = ValidatorProgram.Handler.spending("parity.spend",
                    new PirTerm.Lam("datum", INT, new PirTerm.Lam("action", ACTION, new PirTerm.Lam("ctx", DATA,
                            call(DefaultFun.LessThanEqualsInteger, new PirTerm.Var("datum", INT), amount)))),
                    fn(INT, ACTION, DATA, BOOL));
            var withdraw = ValidatorProgram.Handler.of(Purpose.WITHDRAW, "parity.withdraw",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("ctx", DATA,
                            call(DefaultFun.LessThanInteger, integer(0), new PirTerm.Var("r", INT)))),
                    fn(INT, DATA, BOOL));
            return program(List.of(spend, equalsHandler(Purpose.MINT, 10), withdraw));
        }

        @Test
        void outcomesAgreeWithTheJavaMultiValidator() {
            var java = new JulcCompiler().compile(SOURCE);
            assertFalse(java.hasErrors(), java::toString);
            var neutral = compile(neutral()).program();
            var redeemers = List.of(integerData(10), integerData(11), integerData(-1),
                    PlutusData.constr(0, integerData(5)), PlutusData.constr(0, integerData(1)),
                    PlutusData.constr(1, integerData(5)), PlutusData.constr(0),
                    PlutusData.constr(0, integerData(5), integerData(6)), PlutusData.bytes(new byte[2]));
            var datums = Arrays.asList(integerData(3), integerData(7), null, PlutusData.bytes(new byte[0]));
            int checked = 0;
            int accepted = 0;
            for (var purpose : Purpose.values())
                for (var redeemer : redeemers)
                    for (var datum : purpose == Purpose.SPEND ? datums : Arrays.asList((PlutusData) null)) {
                        var ctx = context(purpose, redeemer, datum);
                        boolean neutralAccepts = accepts(neutral, ctx);
                        assertEquals(accepts(java.program(), ctx), neutralAccepts,
                                purpose + " redeemer=" + redeemer + " datum=" + datum);
                        checked++;
                        if (neutralAccepts) accepted++;
                    }
            assertEquals(81, checked);
            // Accepted: SPEND datum 3 with Action(5); MINT 10; WITHDRAW 10 and 11.
            assertEquals(4, accepted, "the matrix must exercise both outcomes");
        }
    }

    @Nested
    class Rejected {
        @Test
        void descriptorShapeErrors() {
            rejects("JULC0047", program(List.of()));
            rejects("JULC0047", program(List.of(equalsHandler(Purpose.MINT, 1), equalsHandler(Purpose.MINT, 2))));
            rejects("JULC0047", new ValidatorProgram(2, Set.of(), " ", CompilerTarget.PLUTUS_V3_PV11,
                    BackendContract.STRICT_BOUNDARY_V1, Map.of(), List.of(), new LinkedHashMap<>(), List.of(),
                    List.of(equalsHandler(Purpose.MINT, 1))));
            rejects("JULC0045", new ValidatorProgram(2, Set.of(), "v", CompilerTarget.PLUTUS_V3_PV11,
                    "julc-lenient", Map.of(), List.of(), new LinkedHashMap<>(), List.of(),
                    List.of(equalsHandler(Purpose.MINT, 1))));
            rejects("JULC0044", new ValidatorProgram(1, Set.of(), "v", CompilerTarget.PLUTUS_V3_PV11,
                    BackendContract.STRICT_BOUNDARY_V1, Map.of(), List.of(), new LinkedHashMap<>(), List.of(),
                    List.of(equalsHandler(Purpose.MINT, 1))));
        }

        @Test
        void signaturesMustMatchTheirRole() {
            var mint = equalsHandler(Purpose.MINT, 1);
            // A minting handler with a datum argument.
            rejects("JULC0047", program(List.of(new ValidatorProgram.Handler(Purpose.MINT, "m",
                    new PirTerm.Lam("d", INT, mint.term()), fn(INT, INT, DATA, BOOL), DatumProfile.REQUIRED))));
            // Wrong arity, non-Bool result, non-Data context.
            rejects("JULC0047", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    mint.term(), fn(INT, INT, DATA, BOOL)))));
            rejects("JULC0047", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA, integer(1))), fn(INT, DATA, INT)))));
            rejects("JULC0047", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", INT, new PirTerm.Const(Constant.bool(true)))),
                    fn(INT, INT, BOOL)))));
            // The declared type disagrees with the term.
            rejects("JULC0049", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    mint.term(), fn(new PirType.ByteStringType(), DATA, BOOL)))));
        }

        @Test
        void boundaryTypesMustHaveAStrictDataCodec() {
            var nativeValue = new PirType.NativeValueType();
            rejects("JULC0047", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("v", nativeValue, new PirTerm.Lam("c", DATA, new PirTerm.Const(Constant.bool(true)))),
                    fn(nativeValue, DATA, BOOL)))));
            var function = fn(INT, INT);
            rejects("JULC0047", program(List.of(ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("f", function, new PirTerm.Lam("c", DATA, new PirTerm.Const(Constant.bool(true)))),
                    fn(function, DATA, BOOL)))));
        }

        @Test
        void unavailableFeaturesNeedCapabilities() {
            var withParameter = new ValidatorProgram(2, Set.of(), "v", CompilerTarget.PLUTUS_V3_PV11,
                    BackendContract.STRICT_BOUNDARY_V1, Map.of(), List.of(), new LinkedHashMap<>(),
                    List.of(new Parameter("owner", INT)), List.of(equalsHandler(Purpose.MINT, 1)));
            assertTrue(rejects("JULC0045", withParameter).getMessage().contains("validator.parameters"));
            var optional = new ValidatorProgram.Handler(Purpose.SPEND, "s", spendHandler().term(),
                    spendHandler().type(), DatumProfile.OPTIONAL);
            assertTrue(rejects("JULC0045", program(List.of(optional))).getMessage()
                    .contains("spend.datum.optional"));
        }

        @Test
        void handlerTermsAreVerified() {
            var unbound = ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA, new PirTerm.Var("missing", BOOL))),
                    fn(INT, DATA, BOOL));
            var error = rejects("JULC0048", program(List.of(unbound)));
            assertEquals("m", error.subject());
            var condition = ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA, new PirTerm.IfThenElse(
                            new PirTerm.Var("r", INT), new PirTerm.Const(Constant.bool(true)),
                            new PirTerm.Const(Constant.bool(false))))),
                    fn(INT, DATA, BOOL));
            rejects("JULC0049", program(List.of(condition)));
        }
    }
}
