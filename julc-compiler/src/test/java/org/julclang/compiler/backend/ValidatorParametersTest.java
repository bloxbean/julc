package org.julclang.compiler.backend;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.julclang.compiler.backend.ValidatorProgramTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deployment parameters, spending datum profiles and standalone boundary checks (#184). */
class ValidatorParametersTest {
    static final PirType BYTES = new PirType.ByteStringType();
    static final PirType OPTIONAL_INT = new PirType.OptionalType(INT);

    static ValidatorProgram validator(List<Parameter> parameters, List<ValidatorProgram.Handler> handlers) {
        return new ValidatorProgram(BackendContract.REVISION, Set.of(), "test.parameterized",
                CompilerTarget.PLUTUS_V3_PV11, BackendContract.STRICT_BOUNDARY_V1, Map.of("Action", ACTION),
                List.of(), new LinkedHashMap<>(), parameters, handlers);
    }

    static boolean runs(Program program, PlutusData context) {
        return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(context)) instanceof EvalResult.Success;
    }

    @Nested
    class Parameters {
        static final String JAVA = """
                import java.math.BigInteger;
                import org.julclang.stdlib.Builtins;
                @MintingValidator
                class Threshold {
                    @Param BigInteger threshold;
                    @Param byte[] owner;
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        return redeemer.compareTo(threshold) >= 0 && Builtins.lengthOfByteString(owner) == 28;
                    }
                }
                """;

        /** {@code \threshold \owner \redeemer \ctx -> threshold <= redeemer && length owner == 28}. */
        static ValidatorProgram neutral() {
            var body = new PirTerm.IfThenElse(
                    call(DefaultFun.LessThanEqualsInteger, new PirTerm.Var("threshold", INT),
                            new PirTerm.Var("redeemer", INT)),
                    call(DefaultFun.EqualsInteger,
                            call(DefaultFun.LengthOfByteString, new PirTerm.Var("owner", BYTES)), integer(28)),
                    new PirTerm.Const(Constant.bool(false)));
            var term = new PirTerm.Lam("threshold", INT, new PirTerm.Lam("owner", BYTES,
                    new PirTerm.Lam("redeemer", INT, new PirTerm.Lam("ctx", DATA, body))));
            return validator(List.of(new Parameter("threshold", INT), new Parameter("owner", BYTES)),
                    List.of(ValidatorProgram.Handler.of(Purpose.MINT, "threshold.mint", term,
                            fn(INT, BYTES, INT, DATA, BOOL))));
        }

        @Test
        void appliedParametersMatchJavaParamFields() {
            var java = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(JAVA);
            assertFalse(java.hasErrors(), java::toString);
            assertEquals(List.of("threshold", "owner"),
                    java.params().stream().map(p -> p.name()).toList());
            var neutral = new CompilerBackend().compile(neutral(), new CompilerOptions());
            assertEquals(List.of("threshold", "owner"),
                    neutral.abi().parameters().stream().map(Parameter::name).toList());
            var owner = PlutusData.bytes(new byte[28]);
            var applications = List.of(
                    List.of(PlutusData.integer(5), owner),
                    List.of(PlutusData.integer(5), PlutusData.bytes(new byte[3])),
                    // Parameters in the wrong order fail to decode.
                    List.of(owner, PlutusData.integer(5)));
            int accepted = 0;
            for (var parameters : applications) {
                var javaApplied = java.program().applyParams(parameters);
                var neutralApplied = neutral.program().applyParams(parameters);
                for (long redeemer : new long[] {4, 5, 6}) {
                    var context = context(Purpose.MINT, PlutusData.integer(redeemer), null);
                    boolean result = runs(neutralApplied, context);
                    assertEquals(runs(javaApplied, context), result, parameters + " redeemer " + redeemer);
                    if (result) accepted++;
                }
            }
            assertEquals(2, accepted);
        }

        @Test
        void appliedScriptHashesAreDeterministicAndParameterSensitive() {
            var firstResult = new CompilerBackend().compile(neutral(), new CompilerOptions());
            var secondResult = new CompilerBackend().compile(neutral(), new CompilerOptions());
            assertEquals(firstResult.abi(), secondResult.abi());
            var first = firstResult.program();
            var second = secondResult.program();
            var owner = PlutusData.bytes(new byte[28]);
            String unapplied = JulcScriptAdapter.scriptHash(first);
            String applied = JulcScriptAdapter.scriptHash(first.applyParams(PlutusData.integer(5), owner));
            assertEquals(applied, JulcScriptAdapter.scriptHash(second.applyParams(PlutusData.integer(5), owner)));
            assertNotEquals(unapplied, applied);
            assertNotEquals(applied,
                    JulcScriptAdapter.scriptHash(first.applyParams(PlutusData.integer(6), owner)));
            assertNotEquals(applied,
                    JulcScriptAdapter.scriptHash(first.applyParams(owner, PlutusData.integer(5))));
        }

        @Test
        void everyHandlerReceivesTheParameters() {
            var mintTerm = new PirTerm.Lam("k", INT, new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA,
                    call(DefaultFun.EqualsInteger, new PirTerm.Var("k", INT), new PirTerm.Var("r", INT)))));
            var spendTerm = new PirTerm.Lam("k", INT, new PirTerm.Lam("d", INT, new PirTerm.Lam("r", INT,
                    new PirTerm.Lam("c", DATA, call(DefaultFun.EqualsInteger,
                            call(DefaultFun.AddInteger, new PirTerm.Var("k", INT), new PirTerm.Var("d", INT)),
                            new PirTerm.Var("r", INT))))));
            var program = new CompilerBackend().compile(validator(List.of(new Parameter("k", INT)), List.of(
                    ValidatorProgram.Handler.of(Purpose.MINT, "m", mintTerm, fn(INT, INT, DATA, BOOL)),
                    ValidatorProgram.Handler.spending("s", spendTerm, fn(INT, INT, INT, DATA, BOOL)))),
                    new CompilerOptions()).program().applyParams(PlutusData.integer(7));
            assertTrue(runs(program, context(Purpose.MINT, PlutusData.integer(7), null)));
            assertFalse(runs(program, context(Purpose.MINT, PlutusData.integer(8), null)));
            assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(10), PlutusData.integer(3))));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(10), PlutusData.integer(4))));
        }

        @Test
        void invalidParametersAreRejected() {
            var handler = ValidatorProgram.Handler.of(Purpose.MINT, "m",
                    new PirTerm.Lam("p", INT, new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA,
                            new PirTerm.Const(Constant.bool(true))))), fn(INT, INT, DATA, BOOL));
            rejects("JULC0047", validator(List.of(new Parameter("p", INT), new Parameter("p", INT)),
                    List.of(handler)));
            rejects("JULC0047", validator(List.of(new Parameter(" ", INT)), List.of(handler)));
            for (var type : List.of(UNIT, new PirType.NativeValueType(), fn(INT, INT),
                    new PirType.PairType(INT, INT)))
                rejects("JULC0047", validator(List.of(new Parameter("p", type)), List.of(
                        ValidatorProgram.Handler.of(Purpose.MINT, "m", new PirTerm.Lam("p", type,
                                new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA,
                                        new PirTerm.Const(Constant.bool(true))))), fn(type, INT, DATA, BOOL)))));
            // The handler's leading argument must have the parameter's type.
            rejects("JULC0047", validator(List.of(new Parameter("p", BYTES)), List.of(handler)));
            // Too few leading arguments for the declared parameters.
            rejects("JULC0047", validator(List.of(new Parameter("p", INT), new Parameter("q", INT)),
                    List.of(handler)));
        }
    }

    @Nested
    class DatumProfiles {
        /** {@code \d:Optional Int -> \r -> \c -> case d of Some v -> v <= r; None -> r == 0}. */
        static ValidatorProgram.Handler optional() {
            var d = new PirTerm.Var("d", OPTIONAL_INT);
            var some = call(DefaultFun.EqualsInteger, call(DefaultFun.FstPair, call(DefaultFun.UnConstrData, d)),
                    integer(0));
            var payload = call(DefaultFun.UnIData, call(DefaultFun.HeadList,
                    call(DefaultFun.SndPair, call(DefaultFun.UnConstrData, d))));
            var body = new PirTerm.IfThenElse(some,
                    call(DefaultFun.LessThanEqualsInteger, payload, new PirTerm.Var("r", INT)),
                    call(DefaultFun.EqualsInteger, new PirTerm.Var("r", INT), integer(0)));
            return new ValidatorProgram.Handler(Purpose.SPEND, "optional", new PirTerm.Lam("d", OPTIONAL_INT,
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA, body))),
                    fn(OPTIONAL_INT, INT, DATA, BOOL), DatumProfile.OPTIONAL);
        }

        static Program compile(ValidatorProgram.Handler... handlers) {
            return new CompilerBackend().compile(validator(List.of(), List.of(handlers)), new CompilerOptions())
                    .program();
        }

        @Test
        void optionalDatumsArePresentOrAbsentAndStrictlyChecked() {
            var program = compile(optional());
            assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(5), PlutusData.integer(3))));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(2), PlutusData.integer(3))));
            assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(0), null)));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(1), null)));
            // A present datum must be an integer; a malformed one is rejected, not treated as absent.
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(0),
                    PlutusData.bytes(new byte[0]))));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.bytes(new byte[0]), PlutusData.integer(3))));
        }

        @Test
        void absentDatumsAreNeverInspected() {
            var handler = new ValidatorProgram.Handler(Purpose.SPEND, "absent",
                    new PirTerm.Lam("r", INT, new PirTerm.Lam("c", DATA,
                            call(DefaultFun.EqualsInteger, new PirTerm.Var("r", INT), integer(7)))),
                    fn(INT, DATA, BOOL), DatumProfile.ABSENT);
            var program = compile(handler);
            for (var datum : Arrays.asList(PlutusData.integer(1), null, PlutusData.bytes(new byte[9]),
                    PlutusData.constr(5, PlutusData.list())))
                assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(7), datum)), "datum " + datum);
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(8), null)));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.bytes(new byte[0]), null)));
        }

        @Test
        void requiredDatumsStillFailWhenMissing() {
            var program = compile(spendHandler());
            assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(3), PlutusData.integer(3))));
            assertFalse(runs(program, context(Purpose.SPEND, PlutusData.integer(3), null)));
        }

        @Test
        void inactiveHandlersDoNotDecodeTheirArguments() {
            var program = compile(optional(), equalsHandler(Purpose.MINT, 10));
            // A minting transaction whose context carries no datum is unaffected by the
            // spending handler's Optional Int schema, and vice versa.
            assertTrue(runs(program, context(Purpose.MINT, PlutusData.integer(10), null)));
            assertTrue(runs(program, context(Purpose.SPEND, PlutusData.integer(0), null)));
            assertFalse(runs(program, context(Purpose.MINT, PlutusData.integer(0), null)));
        }

        @Test
        void abiRecordsTheProfileExplicitly() {
            var abi = new CompilerBackend().compile(validator(List.of(), List.of(optional())),
                    new CompilerOptions()).abi();
            var spend = abi.handlers().getFirst();
            assertEquals(DatumProfile.OPTIONAL, spend.datum());
            assertEquals(OPTIONAL_INT, spend.datumType());
        }

        @Test
        void profilesMustMatchTheHandlerSignature() {
            var required = spendHandler();
            // OPTIONAL needs an Optional datum argument.
            rejects("JULC0047", validator(List.of(), List.of(new ValidatorProgram.Handler(Purpose.SPEND, "s",
                    required.term(), required.type(), DatumProfile.OPTIONAL))));
            // ABSENT has no datum argument.
            rejects("JULC0047", validator(List.of(), List.of(new ValidatorProgram.Handler(Purpose.SPEND, "s",
                    required.term(), required.type(), DatumProfile.ABSENT))));
            // Only spending handlers take datums.
            var mint = equalsHandler(Purpose.MINT, 1);
            rejects("JULC0047", validator(List.of(), List.of(new ValidatorProgram.Handler(Purpose.MINT, "m",
                    mint.term(), mint.type(), DatumProfile.OPTIONAL))));
        }
    }

    @Nested
    class CheckPrograms {
        static boolean valid(PirType type, PlutusData data) {
            var program = BoundaryPrograms.check(type, Map.of("Action", ACTION), new CompilerOptions());
            return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(data)) instanceof EvalResult.Success;
        }

        @Test
        void checkProgramsValidateParameterData() {
            assertTrue(valid(INT, PlutusData.integer(5)));
            assertFalse(valid(INT, PlutusData.bytes(new byte[1])));
            assertTrue(valid(BYTES, PlutusData.bytes(new byte[28])));
            assertFalse(valid(BYTES, PlutusData.integer(1)));
            assertTrue(valid(BOOL, PlutusData.constr(1)));
            assertFalse(valid(BOOL, PlutusData.constr(5)));
            var action = new PirType.NamedTypeRef("Action", "Action", PirType.NamedKind.RECORD);
            assertTrue(valid(action, PlutusData.constr(0, PlutusData.integer(1))));
            assertFalse(valid(action, PlutusData.constr(0)));
            assertFalse(valid(action, PlutusData.constr(1, PlutusData.integer(1))));
            var list = new PirType.ListType(INT);
            assertTrue(valid(list, PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))));
            assertFalse(valid(list, PlutusData.list(PlutusData.integer(1), PlutusData.bytes(new byte[0]))));
            assertTrue(valid(OPTIONAL_INT, PlutusData.constr(1)));
            assertFalse(valid(OPTIONAL_INT, PlutusData.constr(0, PlutusData.bytes(new byte[0]))));
        }

        @Test
        void checkProgramsRejectTypesWithoutADataDecoding() {
            for (var type : List.of(UNIT, new PirType.NativeValueType(), fn(INT, INT)))
                assertEquals("JULC0047", assertThrows(BackendException.class,
                        () -> BoundaryPrograms.check(type, Map.of(), new CompilerOptions())).code());
        }
    }
}
