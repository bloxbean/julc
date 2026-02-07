package com.bloxbean.cardano.plutus.compiler.uplc;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.bloxbean.cardano.plutus.core.Term;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UplcGeneratorTest {

    UplcGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new UplcGenerator();
    }

    @Nested
    class Constants {
        @Test void intConst() {
            var result = gen.generate(new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))));
            assertInstanceOf(Term.Const.class, result);
        }

        @Test void boolConst() {
            var result = gen.generate(new PirTerm.Const(Constant.bool(true)));
            assertInstanceOf(Term.Const.class, result);
        }

        @Test void unitConst() {
            var result = gen.generate(new PirTerm.Const(Constant.unit()));
            assertInstanceOf(Term.Const.class, result);
        }
    }

    @Nested
    class LambdaAndVar {
        @Test void simpleIdentity() {
            // \x -> x
            var pir = new PirTerm.Lam("x", new PirType.IntegerType(),
                    new PirTerm.Var("x", new PirType.IntegerType()));
            var result = gen.generate(pir);
            assertInstanceOf(Term.Lam.class, result);
            var lam = (Term.Lam) result;
            assertInstanceOf(Term.Var.class, lam.body());
            assertEquals(1, ((Term.Var) lam.body()).name().index()); // De Bruijn index 1
        }

        @Test void nestedLambda() {
            // \x -> \y -> x
            var pir = new PirTerm.Lam("x", new PirType.IntegerType(),
                    new PirTerm.Lam("y", new PirType.IntegerType(),
                            new PirTerm.Var("x", new PirType.IntegerType())));
            var result = gen.generate(pir);
            var outerLam = (Term.Lam) result;
            var innerLam = (Term.Lam) outerLam.body();
            var varRef = (Term.Var) innerLam.body();
            assertEquals(2, varRef.name().index()); // x is 2 hops away
        }

        @Test void innerVariable() {
            // \x -> \y -> y
            var pir = new PirTerm.Lam("x", new PirType.IntegerType(),
                    new PirTerm.Lam("y", new PirType.IntegerType(),
                            new PirTerm.Var("y", new PirType.IntegerType())));
            var result = gen.generate(pir);
            var outerLam = (Term.Lam) result;
            var innerLam = (Term.Lam) outerLam.body();
            var varRef = (Term.Var) innerLam.body();
            assertEquals(1, varRef.name().index()); // y is 1 hop away
        }

        @Test void unboundVariable() {
            assertThrows(CompilerException.class,
                    () -> gen.generate(new PirTerm.Var("unbound", new PirType.IntegerType())));
        }
    }

    @Nested
    class LetBindings {
        @Test void letAsApplication() {
            // let x = 42 in x  ->  (\x -> x) 42
            var pir = new PirTerm.Let("x",
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))),
                    new PirTerm.Var("x", new PirType.IntegerType()));
            var result = gen.generate(pir);
            assertInstanceOf(Term.Apply.class, result);
            var app = (Term.Apply) result;
            assertInstanceOf(Term.Lam.class, app.function());
            assertInstanceOf(Term.Const.class, app.argument());
        }
    }

    @Nested
    class IfThenElse {
        @Test void structure() {
            // if true then 1 else 0
            var pir = new PirTerm.IfThenElse(
                    new PirTerm.Const(Constant.bool(true)),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
            var result = gen.generate(pir);
            // Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))
            assertInstanceOf(Term.Force.class, result);
            var force = (Term.Force) result;
            assertInstanceOf(Term.Apply.class, force.term());
        }
    }

    @Nested
    class Builtins {
        @Test void monomorphicBuiltin() {
            // AddInteger -> no Force
            var result = gen.generate(new PirTerm.Builtin(DefaultFun.AddInteger));
            assertInstanceOf(Term.Builtin.class, result);
        }

        @Test void oneForceBuiltin() {
            // IfThenElse -> 1 Force
            var result = gen.generate(new PirTerm.Builtin(DefaultFun.IfThenElse));
            assertInstanceOf(Term.Force.class, result);
            var force = (Term.Force) result;
            assertInstanceOf(Term.Builtin.class, force.term());
        }

        @Test void twoForceBuiltin() {
            // FstPair -> 2 Forces
            var result = gen.generate(new PirTerm.Builtin(DefaultFun.FstPair));
            assertInstanceOf(Term.Force.class, result);
            var f1 = (Term.Force) result;
            assertInstanceOf(Term.Force.class, f1.term());
            var f2 = (Term.Force) f1.term();
            assertInstanceOf(Term.Builtin.class, f2.term());
        }
    }

    @Nested
    class ForceCountTable {
        @Test void ifThenElse() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.IfThenElse)); }
        @Test void trace() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.Trace)); }
        @Test void chooseData() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.ChooseData)); }
        @Test void fstPair() { assertEquals(2, UplcGenerator.forceCount(DefaultFun.FstPair)); }
        @Test void sndPair() { assertEquals(2, UplcGenerator.forceCount(DefaultFun.SndPair)); }
        @Test void headList() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.HeadList)); }
        @Test void tailList() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.TailList)); }
        @Test void mkCons() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.MkCons)); }
        @Test void nullList() { assertEquals(1, UplcGenerator.forceCount(DefaultFun.NullList)); }
        @Test void chooseList() { assertEquals(2, UplcGenerator.forceCount(DefaultFun.ChooseList)); }
        @Test void mkNilData() { assertEquals(0, UplcGenerator.forceCount(DefaultFun.MkNilData)); }
        @Test void mkNilPairData() { assertEquals(0, UplcGenerator.forceCount(DefaultFun.MkNilPairData)); }
        @Test void addInteger() { assertEquals(0, UplcGenerator.forceCount(DefaultFun.AddInteger)); }
        @Test void equalsInteger() { assertEquals(0, UplcGenerator.forceCount(DefaultFun.EqualsInteger)); }
    }

    @Nested
    class DataConstructors {
        @Test void emptyConstr() {
            var pir = new PirTerm.DataConstr(0, new PirType.RecordType("R", List.of()), List.of());
            var result = gen.generate(pir);
            assertInstanceOf(Term.Constr.class, result);
            assertEquals(0, ((Term.Constr) result).tag());
        }

        @Test void constrWithFields() {
            var pir = new PirTerm.DataConstr(1, new PirType.RecordType("R", List.of()),
                    List.of(new PirTerm.Const(Constant.integer(BigInteger.ONE))));
            var result = gen.generate(pir);
            assertInstanceOf(Term.Constr.class, result);
            assertEquals(1, ((Term.Constr) result).fields().size());
        }
    }

    @Nested
    class Application {
        @Test void simpleApp() {
            var pir = new PirTerm.Lam("x", new PirType.IntegerType(),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                    new PirTerm.Var("x", new PirType.IntegerType())),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE))));
            var result = gen.generate(pir);
            assertInstanceOf(Term.Lam.class, result);
        }
    }

    @Nested
    class ErrorTerm {
        @Test void error() {
            var result = gen.generate(new PirTerm.Error(new PirType.UnitType()));
            assertInstanceOf(Term.Error.class, result);
        }
    }
}
