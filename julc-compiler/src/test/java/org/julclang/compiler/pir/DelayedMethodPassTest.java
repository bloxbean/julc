package org.julclang.compiler.pir;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.source.SourceLocation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.julclang.compiler.pir.DelayedMethodPass.mayFail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayedMethodPassTest {

    private static final PirType INTEGER = new PirType.IntegerType();
    private static final PirType BYTES = new PirType.ByteStringType();

    private static PirTerm integer(long value) {
        return new PirTerm.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    private static PirTerm bytes(int size) {
        return new PirTerm.Const(Constant.byteString(new byte[size]));
    }

    private static PirTerm text(String value) {
        return new PirTerm.Const(Constant.string(value));
    }

    private static PirTerm variable(String name) {
        return new PirTerm.Var(name, INTEGER);
    }

    private static PirTerm builtin(DefaultFun fun, PirTerm... arguments) {
        PirTerm term = new PirTerm.Builtin(fun);
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    private static PirTerm call(String function, PirTerm... arguments) {
        PirTerm term = new PirTerm.Var(function, new PirType.FunType(INTEGER, BYTES));
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    /** {@code ByteStringLib.zeros}: {@code replicateByte(n, 0)}. */
    private static final PirTerm ZEROS = new PirTerm.Lam("n", INTEGER, builtin(DefaultFun.ReplicateByte, variable("n"), integer(0)));

    private static PirTerm zeros(PirTerm argument) {
        return new PirTerm.Let("zeros", ZEROS, call("zeros", argument));
    }

    private static PirTerm unit() {
        return new PirTerm.Const(Constant.unit());
    }

    @Nested
    class Total {

        @Test
        void aValue() {
            assertFalse(mayFail(integer(1)));
            assertFalse(mayFail(bytes(3)));
            assertFalse(mayFail(text("a")));
            assertFalse(mayFail(new PirTerm.Const(Constant.bool(true))));
            assertFalse(mayFail(new PirTerm.Const(Constant.data(new PlutusData.IntData(BigInteger.ONE)))));
            assertFalse(mayFail(ZEROS));
            assertFalse(mayFail(new PirTerm.Builtin(DefaultFun.DivideInteger)));
        }

        @Test
        void aVariableBoundToATotalTerm() {
            assertFalse(mayFail(new PirTerm.Let("x", integer(1), variable("x"))));
            assertFalse(mayFail(new PirTerm.Let("x", integer(1), builtin(DefaultFun.AddInteger, variable("x"), variable("x")))));
        }

        @Test
        void aListedBuiltinOnArgumentsOfItsKinds() {
            assertFalse(mayFail(builtin(DefaultFun.AddInteger, integer(1), integer(2))));
            assertFalse(mayFail(builtin(DefaultFun.MultiplyInteger, integer(1), builtin(DefaultFun.SubtractInteger, integer(2), integer(3)))));
            assertFalse(mayFail(builtin(DefaultFun.LessThanInteger, integer(1), integer(2))));
            assertFalse(mayFail(builtin(DefaultFun.Blake2b_256, builtin(DefaultFun.AppendByteString, bytes(32), bytes(32)))));
            assertFalse(mayFail(builtin(DefaultFun.EncodeUtf8, builtin(DefaultFun.AppendString, text("a"), text("b")))));
            assertFalse(mayFail(builtin(DefaultFun.BData, bytes(1))));
            assertFalse(mayFail(builtin(DefaultFun.ConstrData, integer(0), builtin(DefaultFun.MkCons, builtin(DefaultFun.IData, integer(1)),
                    builtin(DefaultFun.MkNilData, unit())))));
            assertFalse(mayFail(builtin(DefaultFun.MapData, builtin(DefaultFun.MkNilPairData, unit()))));
        }

        @Test
        void aBranchOfTotalTerms() {
            assertFalse(mayFail(new PirTerm.IfThenElse(builtin(DefaultFun.EqualsInteger, integer(1), integer(1)), integer(1), bytes(1))));
        }

        @Test
        void aConstructorOfItsFields() {
            var type = new PirType.RecordType("R", List.of(new PirType.Field("a", INTEGER), new PirType.Field("b", new PirType.BoolType())));
            assertFalse(mayFail(new PirTerm.DataConstr(0, type, List.of(integer(1), new PirTerm.Const(Constant.bool(true))))));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 32, 8192})
        void aReplicationInItsRange(long length) {
            assertFalse(mayFail(zeros(integer(length))));
            assertFalse(mayFail(builtin(DefaultFun.ReplicateByte, variable("n"), integer(255)), Map.of("n", Constant.integer(BigInteger.valueOf(length)))));
        }

        @Test
        void aCallThroughBoundFunctions() {
            var combine = new PirTerm.Lam("l", BYTES, new PirTerm.Lam("r", BYTES,
                    builtin(DefaultFun.Blake2b_256, builtin(DefaultFun.AppendByteString, variable("l"), variable("r")))));
            assertFalse(mayFail(new PirTerm.Let("zeros", ZEROS, new PirTerm.Let("combine", combine,
                    new PirTerm.Let("nullHash", call("zeros", integer(32)),
                            call("combine", variable("nullHash"), variable("nullHash")))))));
        }

        @Test
        void aFunctionWithItsOwnScope() {
            // The body's x is the one in scope where it was defined, not the caller's.
            assertFalse(mayFail(new PirTerm.Let("x", integer(1), new PirTerm.Let("f", new PirTerm.Lam("a", INTEGER, builtin(DefaultFun.AddInteger, variable("x"), variable("a"))),
                    new PirTerm.Let("x", bytes(1), call("f", integer(2)))))));
        }

        @Test
        void anArgumentAtTheLimit() {
            assertFalse(mayFail(builtin(DefaultFun.Blake2b_256, bytes(32768))));
        }
    }

    @Nested
    class MayFail {

        @Test
        void aFailureOrATrace() {
            assertTrue(mayFail(new PirTerm.Error(INTEGER)));
            assertTrue(mayFail(new PirTerm.Trace(text("a"), integer(1))));
            assertTrue(mayFail(new PirTerm.Let("x", new PirTerm.Error(INTEGER), integer(1))));
            assertTrue(mayFail(new PirTerm.IfThenElse(new PirTerm.Const(Constant.bool(true)), integer(1), new PirTerm.Error(INTEGER))));
        }

        @Test
        void aVariableNothingBinds() {
            assertTrue(mayFail(variable("x")));
            assertTrue(mayFail(new PirTerm.Let("y", variable("x"), integer(1))));
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, 8193, 1L << 32, Long.MIN_VALUE, Long.MAX_VALUE})
        void aReplicationPastItsRange(long length) {
            assertTrue(mayFail(zeros(integer(length))));
            assertTrue(mayFail(builtin(DefaultFun.ReplicateByte, variable("n"), integer(0)), Map.of("n", Constant.integer(BigInteger.valueOf(length)))));
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, 256})
        void aReplicationOfNoByte(long value) {
            assertTrue(mayFail(builtin(DefaultFun.ReplicateByte, integer(1), integer(value))));
        }

        @Test
        void aReplicationOfAnUnknownLength() {
            assertTrue(mayFail(zeros(variable("unknown"))));
            assertTrue(mayFail(new PirTerm.Let("n", builtin(DefaultFun.AddInteger, integer(1), integer(2)), zeros(variable("n")))));
        }

        @Test
        void aBuiltinThatIsNotListed() {
            assertTrue(mayFail(builtin(DefaultFun.DivideInteger, integer(1), integer(2))));
            assertTrue(mayFail(builtin(DefaultFun.DecodeUtf8, bytes(1))));
            assertTrue(mayFail(builtin(DefaultFun.UnBData, new PirTerm.Const(Constant.data(new PlutusData.BytesData(new byte[0]))))));
            assertTrue(mayFail(builtin(DefaultFun.HeadList, builtin(DefaultFun.MkNilData, unit()))));
            assertTrue(mayFail(builtin(DefaultFun.ConsByteString, integer(1), bytes(1))));
        }

        @Test
        void aBuiltinOnTheWrongKind() {
            assertTrue(mayFail(builtin(DefaultFun.AddInteger, integer(1), bytes(1))));
            assertTrue(mayFail(builtin(DefaultFun.BData, integer(1))));
            assertTrue(mayFail(new PirTerm.Let("x", integer(1), builtin(DefaultFun.LengthOfByteString, variable("x")))));
            assertTrue(mayFail(new PirTerm.IfThenElse(integer(1), integer(1), integer(2))));
        }

        @Test
        void aBuiltinWithOneArgumentTooFewOrTooMany() {
            assertTrue(mayFail(builtin(DefaultFun.AddInteger, integer(1))));
            assertTrue(mayFail(builtin(DefaultFun.Blake2b_256, bytes(1), bytes(1))));
        }

        @Test
        void aConstructorTagNotShownInRange() {
            var nil = builtin(DefaultFun.MkNilData, unit());
            assertTrue(mayFail(builtin(DefaultFun.ConstrData, integer(-1), nil)));
            assertTrue(mayFail(builtin(DefaultFun.ConstrData, builtin(DefaultFun.AddInteger, integer(1), integer(1)), nil)));
            assertFalse(mayFail(builtin(DefaultFun.ConstrData, integer(Long.MAX_VALUE), nil)));
        }

        @Test
        void aConstructorWithAFieldOfTheWrongKind() {
            var type = new PirType.RecordType("R", List.of(new PirType.Field("a", INTEGER)));
            assertTrue(mayFail(new PirTerm.DataConstr(0, type, List.of(bytes(1)))));
            assertTrue(mayFail(new PirTerm.DataConstr(0, type, List.of(new PirTerm.Error(INTEGER)))));
            assertTrue(mayFail(new PirTerm.DataConstr(0, type, List.of())));
            assertTrue(mayFail(new PirTerm.DataConstr(-1, type, List.of(integer(1)))));
        }

        @Test
        void anArgumentPastTheLimit() {
            assertTrue(mayFail(builtin(DefaultFun.Blake2b_256, bytes(32769))));
            var large = new PirTerm.Const(Constant.integer(BigInteger.ONE.shiftLeft(262144)));
            assertTrue(mayFail(builtin(DefaultFun.AddInteger, large, integer(1))));
        }

        @Test
        void sizesAddUp() {
            PirTerm term = bytes(8192);
            for (int i = 0; i < 2; i++) term = builtin(DefaultFun.AppendByteString, term, term);
            assertFalse(mayFail(term), "32,768 bytes and what the rule adds for each call is no argument yet");
            assertTrue(mayFail(builtin(DefaultFun.Blake2b_256, term)));
        }

        private static PirTerm doubled(PirTerm start, int times) {
            var names = new ArrayList<String>();
            PirTerm read = new PirTerm.Var("a0", BYTES);
            names.add("a0");
            for (int i = 1; i <= times; i++) {
                names.add("a" + i);
                read = new PirTerm.Var("a" + i, BYTES);
            }
            PirTerm term = read;
            for (int i = times; i > 0; i--) {
                var previous = new PirTerm.Var(names.get(i - 1), BYTES);
                term = new PirTerm.Let(names.get(i), builtin(DefaultFun.AppendByteString, previous, previous), term);
            }
            return new PirTerm.Let("a0", start, term);
        }

        private static final List<DefaultFun> HASHES = List.of(DefaultFun.Sha2_256, DefaultFun.Sha3_256, DefaultFun.Blake2b_256,
                DefaultFun.Blake2b_224, DefaultFun.Keccak_256, DefaultFun.Ripemd_160);

        @Test
        void aHashIsItsDigestsSizeOnAnEmptyInput() {
            // 13 doublings reach 262,144 bytes, past what a byte string may be at run time; each hash starts from no bytes.
            for (var hash : HASHES) {
                assertTrue(mayFail(doubled(builtin(hash, bytes(0)), 13)), hash + ": read as total, and fails at script start");
                assertTrue(mayFail(builtin(hash, doubled(builtin(hash, bytes(0)), 12))), hash.name());
            }
        }

        @Test
        void aDoublingOfADigestStaysTotalWithinTheBound() {
            for (var hash : HASHES) assertFalse(mayFail(doubled(builtin(hash, bytes(0)), 9)), hash + ": at most 16,384 bytes");
        }

        @Test
        void eachDigestHasItsOwnSize() {
            // n doublings of size s are read as 2^n * (s + 1) - 1, and no argument passes 32,768 before the 10th of 32 bytes.
            for (var hash : List.of(DefaultFun.Sha2_256, DefaultFun.Sha3_256, DefaultFun.Blake2b_256, DefaultFun.Keccak_256)) {
                assertFalse(mayFail(builtin(DefaultFun.LengthOfByteString, doubled(builtin(hash, bytes(0)), 9))), hash.name());
                assertTrue(mayFail(builtin(DefaultFun.LengthOfByteString, doubled(builtin(hash, bytes(0)), 10))), hash.name());
            }
            assertFalse(mayFail(builtin(DefaultFun.LengthOfByteString, doubled(builtin(DefaultFun.Blake2b_224, bytes(0)), 10))));
            assertFalse(mayFail(builtin(DefaultFun.LengthOfByteString, doubled(builtin(DefaultFun.Ripemd_160, bytes(0)), 10))));
            // 21,503 or 29,695 bytes, with 11,000 more: one is within the bound, and the other is not.
            var padded = bytes(11000);
            assertFalse(mayFail(builtin(DefaultFun.LengthOfByteString,
                    builtin(DefaultFun.AppendByteString, doubled(builtin(DefaultFun.Ripemd_160, bytes(0)), 10), padded))));
            assertTrue(mayFail(builtin(DefaultFun.LengthOfByteString,
                    builtin(DefaultFun.AppendByteString, doubled(builtin(DefaultFun.Blake2b_224, bytes(0)), 10), padded))));
            assertTrue(mayFail(builtin(DefaultFun.LengthOfByteString,
                    builtin(DefaultFun.AppendByteString, doubled(builtin(DefaultFun.Ripemd_160, bytes(0)), 10), bytes(11300)))),
                    "20 bytes, with 11,300 more: past the bound, as a smaller digest would not be");
        }

        @Test
        void aDigestOfALongInputIsNoLonger() {
            var digest = builtin(DefaultFun.Sha2_256, bytes(32768));
            assertFalse(mayFail(builtin(DefaultFun.AppendByteString, digest, digest)));
        }

        /** {@code f_i(x) = f_(i-1)(f_(i-1)(x))} for {@code i} up to {@code depth}, and {@code body} of them. */
        private static PirTerm callTree(int depth, PirTerm first, PirTerm body) {
            PirTerm term = body;
            for (int i = depth; i > 0; i--) {
                var doubled = new PirTerm.Lam("x", INTEGER, call("f" + (i - 1), call("f" + (i - 1), variable("x"))));
                term = new PirTerm.Let("f" + i, doubled, term);
            }
            return new PirTerm.Let("f0", first, term);
        }

        @Test
        void aCallTreeIsReadInLinearTime() {
            var identity = new PirTerm.Lam("x", INTEGER, variable("x"));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertFalse(mayFail(callTree(64, identity, call("f64", integer(1))))));
        }

        @Test
        void aFailureAtTheLeavesOfACallTreeIsFound() {
            var fails = new PirTerm.Lam("x", INTEGER, new PirTerm.Error(INTEGER));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertTrue(mayFail(callTree(64, fails, call("f64", integer(1))))));
        }

        @Test
        void aCallTreeThatGrowsItsArgumentReachesTheBound() {
            var grows = new PirTerm.Lam("x", INTEGER, builtin(DefaultFun.AddInteger, variable("x"), integer(1)));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertTrue(mayFail(callTree(64, grows, call("f64", integer(1))))));
        }

        @Test
        void aFunctionReadTwiceIsReadOnEachArgument() {
            var reads = builtin(DefaultFun.AppendByteString, call("zeros", integer(1)), call("zeros", integer(8193)));
            assertTrue(mayFail(new PirTerm.Let("zeros", ZEROS, reads)));
            var bothFine = builtin(DefaultFun.AppendByteString, call("zeros", integer(1)), call("zeros", integer(8192)));
            assertFalse(mayFail(new PirTerm.Let("zeros", ZEROS, bothFine)));
            var thenTheFailing = new PirTerm.IfThenElse(new PirTerm.Const(Constant.bool(true)), call("zeros", integer(8192)),
                    call("zeros", integer(-1)));
            assertTrue(mayFail(new PirTerm.Let("zeros", ZEROS, thenTheFailing)));
        }

        @Test
        void oneFunctionTermIsReadInEachOfItsScopes() {
            var closes = new PirTerm.Lam("y", INTEGER, builtin(DefaultFun.ReplicateByte, variable("c"), integer(0)));
            var inFirst = new PirTerm.Let("g", closes, call("g", integer(0)));
            var inSecond = new PirTerm.Let("g", closes, call("g", integer(0)));
            var both = new PirTerm.Let("c", integer(1), new PirTerm.Let("a", inFirst,
                    new PirTerm.Let("c", integer(8193), new PirTerm.Let("b", inSecond, variable("a")))));
            assertTrue(mayFail(both));
        }

        @Test
        void aCallOfAnUnknownOrRecursiveFunctionOrAParameter() {
            assertTrue(mayFail(call("unknown", integer(1))));
            assertTrue(mayFail(new PirTerm.LetRec(List.of(new PirTerm.Binding("f", new PirTerm.Lam("a", INTEGER, integer(1)))), call("f", integer(1)))));
            var callsItsParameter = new PirTerm.Lam("g", INTEGER, call("g", integer(1)));
            assertTrue(mayFail(new PirTerm.Let("f", callsItsParameter, new PirTerm.Let("h", ZEROS, call("f", variable("h"))))));
        }

        @Test
        void aCallWithTheWrongNumberOfArguments() {
            var two = new PirTerm.Lam("a", INTEGER, new PirTerm.Lam("b", INTEGER, integer(1)));
            assertTrue(mayFail(new PirTerm.Let("f", two, call("f", integer(1)))));
            assertTrue(mayFail(new PirTerm.Let("f", two, call("f", integer(1), integer(2), integer(3)))));
            assertFalse(mayFail(new PirTerm.Let("f", two, call("f", integer(1), integer(2)))));
        }

        @Test
        void aCycleMeetsItsOwnName() {
            // The inner f reads the outer f by name: a body that calls a name already being read through is not followed.
            var inner = new PirTerm.Let("f", new PirTerm.Lam("a", INTEGER, integer(1)),
                    new PirTerm.Let("f", new PirTerm.Lam("a", INTEGER, call("f", variable("a"))), call("f", integer(1))));
            assertTrue(mayFail(inner));
        }

        @Test
        void aFailingBodyOfACalledFunction() {
            var fails = new PirTerm.Lam("a", INTEGER, new PirTerm.Error(INTEGER));
            assertTrue(mayFail(new PirTerm.Let("f", fails, call("f", integer(1)))));
            assertFalse(mayFail(new PirTerm.Let("f", fails, integer(1))), "a function that is not called does not run");
        }

        @Test
        void aMatch() {
            assertTrue(mayFail(new PirTerm.ListMatch(builtin(DefaultFun.MkNilData, unit()), "h", "t", integer(1), integer(2))));
            assertTrue(mayFail(new PirTerm.IntegerCase(integer(0), List.of(integer(1), integer(2)))));
        }
    }

    @Nested
    class Rewrite {

        private static final PirType.FunType DELAYED = new PirType.FunType(new PirType.UnitType(), INTEGER);

        private static PirTerm lower(PirTerm term, String... methods) {
            return new DelayedMethodPass(Set.of(methods), Set.of(), null, null).lower(term).term();
        }

        private static PirTerm read() {
            return new PirTerm.App(new PirTerm.Var("C.k", DELAYED), unit());
        }

        @Test
        void aTotalMethodStaysShared() {
            var term = new PirTerm.Let("C.k", integer(1), new PirTerm.App(variable("C.k"), variable("C.k")));
            assertSame(term, lower(term, "C.k"));
        }

        @Test
        void aMethodThatMayFailIsDelayedAndEachReadIsACall() {
            var body = new PirTerm.Error(INTEGER);
            var term = new PirTerm.Let("C.k", body, builtin(DefaultFun.AddInteger, variable("C.k"), variable("C.k")));
            assertEquals(new PirTerm.Let("C.k", new PirTerm.Lam("#_", new PirType.UnitType(), body), builtin(DefaultFun.AddInteger, read(), read())),
                    lower(term, "C.k"));
        }

        @Test
        void aReadUnderABinderIsACall() {
            var term = new PirTerm.Let("C.k", new PirTerm.Error(INTEGER), new PirTerm.Lam("x", INTEGER, variable("C.k")));
            var lowered = assertInstanceOf(PirTerm.Let.class, lower(term, "C.k"));
            assertEquals(new PirTerm.Lam("x", INTEGER, read()), lowered.body());
        }

        @Test
        void aMethodThatReadsADelayedOneIsDelayed() {
            var term = new PirTerm.Let("C.k", new PirTerm.Error(INTEGER), new PirTerm.Let("C.j", variable("C.k"), variable("C.j")));
            var outer = assertInstanceOf(PirTerm.Let.class, lower(term, "C.k", "C.j"));
            var inner = assertInstanceOf(PirTerm.Let.class, outer.body());
            assertEquals(new PirTerm.Lam("#_", new PirType.UnitType(), read()), inner.value());
        }

        @Test
        void aMethodReadingATotalOneIsNot() {
            var term = new PirTerm.Let("C.k", integer(1), new PirTerm.Let("C.j", variable("C.k"), variable("C.j")));
            assertSame(term, lower(term, "C.k", "C.j"));
        }

        @Test
        void aMethodWithParametersIsNot() {
            var term = new PirTerm.Let("C.f", new PirTerm.Lam("x", INTEGER, new PirTerm.Error(INTEGER)), call("C.f", integer(1)));
            assertSame(term, lower(term, "C.f"));
        }

        @Test
        void aLocalIsNot() {
            var term = new PirTerm.Let("x", new PirTerm.Error(INTEGER), variable("x"));
            assertSame(term, lower(term, "C.k"));
        }

        @Test
        void aFieldThatIsTotalIsReadByAMethod() {
            var term = new PirTerm.Let("F", integer(1), new PirTerm.Let("C.k", variable("F"), variable("C.k")));
            assertSame(term, new DelayedMethodPass(Set.of("C.k"), Set.of("F"), null, null).lower(term).term());
        }

        @Test
        void aFieldThatMayFailIsNotDelayedAndItsReaderIs() {
            var field = new PirTerm.Error(INTEGER);
            var term = new PirTerm.Let("F", field, new PirTerm.Let("C.k", variable("F"), variable("C.k")));
            var lowered = assertInstanceOf(PirTerm.Let.class, new DelayedMethodPass(Set.of("C.k"), Set.of("F"), null, null).lower(term).term());
            assertSame(field, lowered.value());
            assertInstanceOf(PirTerm.Lam.class, assertInstanceOf(PirTerm.Let.class, lowered.body()).value());
        }

        @Test
        void aFieldBoundAgainToATermThatMayFailIsNotTheTotalOne() {
            var term = new PirTerm.Let("F", integer(1), new PirTerm.Let("F", new PirTerm.Error(INTEGER), new PirTerm.Let("C.k", variable("F"), variable("C.k"))));
            var outer = assertInstanceOf(PirTerm.Let.class, new DelayedMethodPass(Set.of("C.k"), Set.of("F"), null, null).lower(term).term());
            var method = assertInstanceOf(PirTerm.Let.class, assertInstanceOf(PirTerm.Let.class, outer.body()).body());
            assertInstanceOf(PirTerm.Lam.class, method.value());
        }

        @Test
        void aMethodThatReadsItselfIsAFunctionOfAUnit() {
            var term = new PirTerm.LetRec(List.of(new PirTerm.Binding("C.k", variable("C.k"))), variable("C.k"));
            assertEquals(new PirTerm.LetRec(List.of(new PirTerm.Binding("C.k", new PirTerm.Lam("#_", new PirType.UnitType(), read()))), read()),
                    lower(term, "C.k"));
        }

        @Test
        void aRecursiveFunctionStaysAsItIs() {
            var term = new PirTerm.LetRec(List.of(new PirTerm.Binding("C.f", new PirTerm.Lam("x", INTEGER, call("C.f", variable("x"))))), integer(1));
            assertSame(term, lower(term, "C.f"));
        }

        @Test
        void aPositionMovesWithItsTerm() {
            var read = variable("C.k");
            var sum = builtin(DefaultFun.AddInteger, read, integer(1));
            var term = new PirTerm.Let("C.k", new PirTerm.Error(INTEGER), sum);
            var location = new SourceLocation("A.java", 1, 2, null);
            var positions = new IdentityHashMap<PirTerm, SourceLocation>(Map.of(read, location, sum, location));
            var result = new DelayedMethodPass(Set.of("C.k"), Set.of(), positions, null).lower(term);
            var lowered = assertInstanceOf(PirTerm.Let.class, result.term());
            assertEquals(location, result.positions().get(lowered.body()));
            var moved = new ArrayList<SourceLocation>();
            result.positions().forEach((key, value) -> {
                if (key instanceof PirTerm.App app && app.argument() instanceof PirTerm.Const c && c.value() instanceof Constant.UnitConst) moved.add(value);
            });
            assertEquals(List.of(location), moved);
        }
    }

    @Test
    void aMethodReadIsNotAVariableRead() {
        assertTrue(PirHelpers.isMethodRead(variable("C.k")));
        assertTrue(PirHelpers.isMethodRead(variable("org.example.Lib.k")));
        assertFalse(PirHelpers.isMethodRead(variable("k")));
        assertFalse(PirHelpers.isMethodRead(variable("#k")));
        assertFalse(PirHelpers.isMethodRead(integer(1)));
    }
}
