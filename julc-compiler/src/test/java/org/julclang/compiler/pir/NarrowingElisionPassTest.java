package org.julclang.compiler.pir;

import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ADR-068 r18: each rule of {@link NarrowingElisionPass}, at its boundaries, and every binder form's scope. A wrap
 * stays unless the operand's value is shown to be in {@code int}'s range. The values are in {@code JavacCastsTest} and
 * {@code Adr068NarrowingHarnessTest}, against the JVM.
 */
class NarrowingElisionPassTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final BigInteger MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final PirTerm UNKNOWN = variable("unknown");
    private static final PirTerm BIG = constant(MAX.add(BigInteger.ONE));

    private static PirTerm constant(BigInteger value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    private static PirTerm constant(long value) {
        return constant(BigInteger.valueOf(value));
    }

    private static PirTerm variable(String name) {
        return new PirTerm.Var(name, INT);
    }

    private static PirTerm call(DefaultFun fun, PirTerm a, PirTerm b) {
        return PirHelpers.builtinApp2(fun, a, b);
    }

    private static PirTerm apply(PirTerm function, PirTerm... arguments) {
        for (var argument : arguments) function = new PirTerm.App(function, argument);
        return function;
    }

    private static PirTerm lam(String param, PirTerm body) {
        return new PirTerm.Lam(param, INT, body);
    }

    private static PirTerm let(String name, PirTerm value, PirTerm body) {
        return new PirTerm.Let(name, value, body);
    }

    private static PirTerm wrap(PirTerm value) {
        return NarrowingElisionPass.narrowToInt(value);
    }

    private static PirTerm lower(PirTerm term) {
        return new NarrowingElisionPass(null, null).lower(term).term();
    }

    /** The wrap of {@code operand} is removed. */
    private static void assertElided(PirTerm operand) {
        assertEquals(operand, lower(wrap(operand)));
    }

    /** The wrap of {@code operand} stays. */
    private static void assertKept(PirTerm operand) {
        assertEquals(wrap(operand), lower(wrap(operand)));
    }

    @Test
    void aConstantIsItsValueUpToIntsBounds() {
        assertElided(constant(0));
        assertElided(constant(-1));
        assertElided(constant(MAX));
        assertElided(constant(MIN));
        assertKept(constant(MAX.add(BigInteger.ONE)));
        assertKept(constant(MIN.subtract(BigInteger.ONE)));
    }

    @Test
    void indexByteStringIsAByte() {
        assertElided(call(DefaultFun.IndexByteString, variable("b"), UNKNOWN));
        // Unsaturated, it is a function, so nothing is known.
        assertKept(new PirTerm.App(new PirTerm.Builtin(DefaultFun.IndexByteString), variable("b")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LengthOfByteString", "ConsByteString", "AddInteger", "SubtractInteger", "MultiplyInteger", "QuotientInteger", "DivideInteger"})
    void anotherBuiltinIsUnknown(String name) {
        var fun = DefaultFun.valueOf(name);
        assertKept(name.equals("LengthOfByteString") ? new PirTerm.App(new PirTerm.Builtin(fun), variable("b"))
                : call(fun, constant(1), constant(1)));
    }

    @Test
    void modIntegerIsBelowAPositiveDivisor() {
        assertElided(call(DefaultFun.ModInteger, UNKNOWN, constant(1)));
        assertElided(call(DefaultFun.ModInteger, UNKNOWN, constant(256)));
        assertElided(call(DefaultFun.ModInteger, UNKNOWN, constant(MAX.add(BigInteger.ONE))));
        // k - 1 is 2^31, one past int's range.
        assertKept(call(DefaultFun.ModInteger, UNKNOWN, constant(MAX.add(BigInteger.TWO))));
        // With a negative divisor the result is not in 0..k-1, and with zero the builtin fails.
        assertKept(call(DefaultFun.ModInteger, UNKNOWN, constant(-16)));
        assertKept(call(DefaultFun.ModInteger, UNKNOWN, constant(0)));
        assertKept(call(DefaultFun.ModInteger, UNKNOWN, UNKNOWN));
        // The dividend says nothing, and only the divisor is read.
        assertKept(call(DefaultFun.ModInteger, constant(1), UNKNOWN));
    }

    @Test
    void remainderIntegerIsWithinAPositiveDivisor() {
        assertElided(call(DefaultFun.RemainderInteger, UNKNOWN, constant(16)));
        assertElided(call(DefaultFun.RemainderInteger, UNKNOWN, constant(MAX.add(BigInteger.ONE))));
        assertKept(call(DefaultFun.RemainderInteger, UNKNOWN, constant(MAX.add(BigInteger.TWO))));
        assertKept(call(DefaultFun.RemainderInteger, UNKNOWN, constant(-16)));
        assertKept(call(DefaultFun.RemainderInteger, UNKNOWN, UNKNOWN));
    }

    @Test
    void aWrapIsInIntsRange() {
        var inner = wrap(UNKNOWN);
        assertEquals(inner, lower(wrap(inner)));
        // A near miss of the form is no wrap: the offset differs.
        assertKept(call(DefaultFun.SubtractInteger, call(DefaultFun.ModInteger,
                call(DefaultFun.AddInteger, UNKNOWN, constant(1)), constant(BigInteger.ONE.shiftLeft(32))), constant(1)));
    }

    @Test
    void theIdentityHoldsUpToIntsBounds() {
        // The wrap's arithmetic: every value of int's range comes back, and the first value past it does not.
        var range = BigInteger.ONE.shiftLeft(32);
        var offset = BigInteger.ONE.shiftLeft(31);
        for (var x : List.of(MAX, MIN, BigInteger.ZERO, BigInteger.valueOf(255))) {
            assertEquals(x, x.add(offset).mod(range).subtract(offset));
        }
        assertEquals(MIN, MAX.add(BigInteger.ONE).add(offset).mod(range).subtract(offset));
    }

    @Test
    void aFreeVariableAndAParameterAreUnknown() {
        assertKept(UNKNOWN);
        var body = wrap(variable("x"));
        assertEquals(lam("x", body), lower(lam("x", body)));
    }

    @Test
    void aLetIsReadThrough() {
        assertElided(let("x", constant(5), variable("x")));
        assertKept(let("x", BIG, variable("x")));
        var read = wrap(variable("x"));
        assertEquals(let("x", constant(5), variable("x")), lower(let("x", constant(5), read)));
        assertEquals(let("x", BIG, read), lower(let("x", BIG, read)));
    }

    /** A name is the nearest binder's: the later value of a reassigned local is the one read. */
    @Test
    void theNearestLetWins() {
        var read = wrap(variable("x"));
        assertEquals(let("x", constant(1), let("x", BIG, read)), lower(let("x", constant(1), let("x", BIG, read))));
        assertEquals(let("x", BIG, let("x", constant(1), variable("x"))), lower(let("x", BIG, let("x", constant(1), read))));
    }

    /** A {@code let}'s value is read in the scope of the {@code let}, not of the wrap. */
    @Test
    void aValueReadsItsOwnScope() {
        var read = wrap(variable("y"));
        assertEquals(let("k", constant(1), let("y", variable("k"), let("k", BIG, variable("y")))),
                lower(let("k", constant(1), let("y", variable("k"), let("k", BIG, read)))));
        var kept = let("k", BIG, let("y", variable("k"), let("k", constant(1), read)));
        assertEquals(kept, lower(kept));
        // let x = x: the value's x is the outer one.
        var self = let("x", BIG, let("x", variable("x"), wrap(variable("x"))));
        assertEquals(self, lower(self));
    }

    /** Each binder form hides the outer value of its name. A forgotten one would read a wrong value as in range. */
    @Test
    void everyBinderHidesTheOuterName() {
        var read = wrap(variable("x"));
        List<Function<PirTerm, PirTerm>> binders = List.of(
                body -> lam("x", body),
                body -> new PirTerm.LetRec(List.of(new PirTerm.Binding("x", lam("n", variable("n")))), body),
                body -> new PirTerm.LetRec(List.of(new PirTerm.Binding("x", lam("n", body))), constant(0)),
                body -> new PirTerm.PairMatch(UNKNOWN, new PirType.PairType(INT, INT), "x", "s", body),
                body -> new PirTerm.PairMatch(UNKNOWN, new PirType.PairType(INT, INT), "f", "x", body),
                body -> new PirTerm.ListMatch(UNKNOWN, "x", "t", constant(0), body),
                body -> new PirTerm.ListMatch(UNKNOWN, "h", "x", constant(0), body),
                body -> new PirTerm.DataMatch(UNKNOWN, List.of(new PirTerm.MatchBranch("A", List.of("x"), List.of(INT), body))),
                body -> new PirTerm.DataMatch(UNKNOWN, List.of(new PirTerm.MatchBranch("A", List.of(), List.of(), body, "x"))));
        for (var binder : binders) {
            var term = let("x", constant(1), binder.apply(read));
            assertEquals(term, lower(term));
        }
        // The control: a list match's nil branch and a scrutinee are outside the binders, so x is still the let's there.
        assertEquals(let("x", constant(1), new PirTerm.ListMatch(UNKNOWN, "x", "t", variable("x"), constant(0))),
                lower(let("x", constant(1), new PirTerm.ListMatch(UNKNOWN, "x", "t", read, constant(0)))));
        assertEquals(let("x", constant(1), new PirTerm.ListMatch(variable("x"), "x", "t", constant(0), constant(0))),
                lower(let("x", constant(1), new PirTerm.ListMatch(read, "x", "t", constant(0), constant(0)))));
    }

    @Test
    void aSaturatedCallIsItsBody() {
        var at = lam("b", lam("i", call(DefaultFun.IndexByteString, variable("b"), variable("i"))));
        var read = wrap(apply(variable("at"), variable("s"), UNKNOWN));
        var elided = lower(let("at", at, read));
        assertEquals(let("at", at, apply(variable("at"), variable("s"), UNKNOWN)), elided);
        // A body with its own binders, and a call of another function it can see.
        var inner = lam("x", let("y", constant(4), variable("y")));
        var outer = lam("x", apply(variable("inner"), variable("x")));
        var chain = let("inner", inner, let("outer", outer, wrap(apply(variable("outer"), UNKNOWN))));
        assertEquals(let("inner", inner, let("outer", outer, apply(variable("outer"), UNKNOWN))), lower(chain));
    }

    @Test
    void aCallIsKnownOnlyWhenSaturated() {
        var at = lam("b", lam("i", call(DefaultFun.IndexByteString, variable("b"), variable("i"))));
        for (var read : List.of(wrap(apply(variable("at"), variable("s"))),
                wrap(apply(variable("at"), variable("s"), UNKNOWN, UNKNOWN)))) {
            var term = let("at", at, read);
            assertEquals(term, lower(term));
        }
    }

    /** Nothing is known of a parameter, whatever the argument, and it hides an outer value of its name. */
    @Test
    void aFunctionsParameterIsUnknown() {
        var identity = let("id", lam("x", variable("x")), wrap(apply(variable("id"), constant(1))));
        assertEquals(identity, lower(identity));
        var hiding = let("k", constant(1), let("f", lam("k", variable("k")), wrap(apply(variable("f"), BIG))));
        assertEquals(hiding, lower(hiding));
        // A later binder of the function's name is the one called.
        var at = lam("b", lam("i", call(DefaultFun.IndexByteString, variable("b"), variable("i"))));
        var other = lam("b", lam("i", BIG));
        var later = let("at", at, let("at", other, wrap(apply(variable("at"), UNKNOWN, UNKNOWN))));
        assertEquals(later, lower(later));
    }

    /** A recursive group is unknown: it needs no fixpoint here, and reading it would not end. */
    @Test
    void aRecursiveFunctionIsUnknown() {
        var loop = new PirTerm.LetRec(List.of(new PirTerm.Binding("f", lam("n", apply(variable("f"), variable("n"))))),
                wrap(apply(variable("f"), constant(1))));
        assertEquals(loop, lower(loop));
        var literal = new PirTerm.LetRec(List.of(new PirTerm.Binding("f", lam("n", constant(1)))),
                wrap(apply(variable("f"), constant(1))));
        assertEquals(literal, lower(literal));
    }

    @Test
    void aBranchOrAnEffectIsUnknown() {
        assertKept(new PirTerm.IfThenElse(UNKNOWN, constant(1), constant(2)));
        assertKept(new PirTerm.Trace(UNKNOWN, constant(1)));
        assertKept(new PirTerm.Error(INT));
    }

    /** The operand of a removed wrap runs once and in its place, in each form the walk passes through. */
    @Test
    void aWrapIsFoundInEveryForm() {
        var index = call(DefaultFun.IndexByteString, variable("b"), UNKNOWN);
        var read = wrap(index);
        assertEquals(new PirTerm.IfThenElse(index, index, index), lower(new PirTerm.IfThenElse(read, read, read)));
        assertEquals(new PirTerm.Trace(index, index), lower(new PirTerm.Trace(read, read)));
        var type = new PirType.DataType();
        assertEquals(new PirTerm.DataConstr(0, type, List.of(index)), lower(new PirTerm.DataConstr(0, type, List.of(read))));
        assertEquals(new PirTerm.IntegerCase(index, List.of(index, index)), lower(new PirTerm.IntegerCase(read, List.of(read, read))));
        assertEquals(apply(index, index), lower(apply(read, read)));
        assertEquals(wrap(call(DefaultFun.AddInteger, index, BIG)), lower(wrap(call(DefaultFun.AddInteger, read, BIG))));
    }

    @Test
    void aTermWithNoWrapIsTheSameObject() {
        var term = let("x", BIG, lam("y", call(DefaultFun.AddInteger, variable("x"), variable("y"))));
        assertSame(term, lower(term));
    }

    @Test
    void anUntouchedPartOfATermKeepsItsIdentity() {
        var untouched = let("x", BIG, variable("x"));
        var term = apply(untouched, wrap(constant(1)));
        assertSame(untouched, ((PirTerm.App) lower(term)).function());
    }

    @Test
    void positionsAndBindersSurvive() {
        var location = new SourceLocation("C.java", 3, 7, null);
        var operandLocation = new SourceLocation("C.java", 3, 12, null);
        var operand = call(DefaultFun.IndexByteString, variable("b"), UNKNOWN);
        var read = wrap(operand);
        var let = new PirTerm.Let("x", read, variable("x"));
        var positions = new HashMap<PirTerm, SourceLocation>();
        positions.put(read, location);
        positions.put(operand, operandLocation);
        positions.put(let, location);
        var provenance = new PirDebugProvenance();
        provenance.associate(let, new PirDebugProvenance.Association("id", "x", "scope", "type", "layout"));
        var result = new NarrowingElisionPass(positions, provenance).lower(let);
        var lowered = (PirTerm.Let) result.term();
        assertEquals(operand, lowered.value());
        // The operand's own position stands, and the rebuilt binder keeps its position and its debug association.
        assertEquals(operandLocation, result.positions().get(lowered.value()));
        assertEquals(location, result.positions().get(lowered));
        assertNotNull(provenance.association(lowered));
    }

    @Test
    void aBareOperandTakesTheWrapsPosition() {
        var location = new SourceLocation("C.java", 3, 7, null);
        var operand = call(DefaultFun.IndexByteString, variable("b"), UNKNOWN);
        var read = wrap(operand);
        var positions = new HashMap<PirTerm, SourceLocation>();
        positions.put(read, location);
        var result = new NarrowingElisionPass(positions, null).lower(read);
        assertEquals(location, result.positions().get(result.term()));
    }

}
