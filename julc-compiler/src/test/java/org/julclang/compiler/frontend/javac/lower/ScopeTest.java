package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.util.ElementFilter;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** I13: how a name is chosen, and the check that a name resolves to its element's binder, with its negative controls. */
class ScopeTest {

    private static final PirType INT = new PirType.IntegerType();

    /** Three fields of one class, and a field of another with the first's simple name. */
    private static List<Element> elements() {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(
                "class P { static long a; static long b; static long c; } class Q { static long a; }")), List.of());
        return program.read(facts -> {
            var p = ElementFilter.fieldsIn(facts.elements().getTypeElement("P").getEnclosedElements());
            var q = ElementFilter.fieldsIn(facts.elements().getTypeElement("Q").getEnclosedElements());
            return List.<Element>of(p.get(0), p.get(1), p.get(2), q.get(0));
        });
    }

    private static PirTerm.Const zero() {
        return new PirTerm.Const(Constant.integer(java.math.BigInteger.ZERO));
    }

    @Test
    void aFreeNameIsTheSourceName() {
        var a = elements().get(0);
        assertEquals("a", Scope.empty().bind(a, INT).lookup(a).name());
    }

    @Test
    void aVisibleNameTakesTheSmallestFreeMark() {
        var all = elements();
        var a = all.get(0);
        var otherA = all.get(3);
        var scope = Scope.empty().bind(a, INT).bind(otherA, INT);
        assertEquals("a", scope.lookup(a).name());
        assertEquals("a'1", scope.lookup(otherA).name());
    }

    @Test
    void aMarkAlreadyVisibleIsSkipped() {
        var all = elements();
        var scope = Scope.empty().bind(all.get(0), INT).bind(all.get(3), INT);
        // A third element of the same simple name cannot be had from one class, so rebinding stands for it.
        var again = scope.bind(all.get(0), INT);
        assertEquals("a'2", again.lookup(all.get(0)).name(), "a'1 is taken");
    }

    @Test
    void anElementIsNotFoundByItsName() {
        var all = elements();
        assertNull(Scope.empty().bind(all.get(0), INT).lookup(all.get(3)), "the same simple name, another element");
    }

    @Test
    void aScopeDoesNotChangeWhenExtended() {
        var all = elements();
        var outer = Scope.empty().bind(all.get(0), INT);
        outer.bind(all.get(1), INT);
        assertNull(outer.lookup(all.get(1)));
    }

    @Test
    void aVarResolvesToItsElementsBinder() {
        var a = elements().get(0);
        var v = new PirTerm.Var("a", INT);
        var let = new PirTerm.Let("a", zero(), v);
        var provenance = new Provenance();
        provenance.read(v, a);
        provenance.binder(let, "a", a);
        assertDoesNotThrow(() -> BindingCheck.check(let, provenance));
    }

    @Test
    void aVarBoundToAnotherElementFails() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var let = new PirTerm.Let("a", zero(), v);
        var provenance = new Provenance();
        provenance.read(v, all.get(0));
        provenance.binder(let, "a", all.get(3));
        assertTrue(assertThrows(IllegalStateException.class, () -> BindingCheck.check(let, provenance))
                .getMessage().startsWith("I13"));
    }

    @Test
    void aFreeVarFails() {
        var v = new PirTerm.Var("a", INT);
        var provenance = new Provenance();
        provenance.read(v, elements().get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(v, provenance));
    }

    @Test
    void anInnerBinderOfAnotherElementCaptures() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Lam("a", INT, v);
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new Provenance();
        provenance.read(v, all.get(0));
        provenance.binder(outer, "a", all.get(0));
        provenance.binder(inner, "a", all.get(3));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
    }

    @Test
    void aGeneratedBinderCaptures() {
        var a = elements().get(0);
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Lam("a", INT, v);
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new Provenance();
        provenance.read(v, a);
        provenance.binder(outer, "a", a);
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
    }

    @Test
    void aLetValueDoesNotSeeItsOwnBinder() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Let("a", v, zero());
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new Provenance();
        provenance.read(v, all.get(0));
        provenance.binder(outer, "a", all.get(0));
        provenance.binder(inner, "a", all.get(3));
        assertDoesNotThrow(() -> BindingCheck.check(outer, provenance));
    }

    /** I13: every copy of a duplicated term is checked, in that copy's own scope. */
    @Test
    void oneCopyOfADuplicatedTermCapturedFails() {
        var all = elements();
        var shared = new PirTerm.Var("a", INT);
        var capturing = new PirTerm.Let("a", zero(), shared);
        var outer = new PirTerm.Let("a", zero(), new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), shared), capturing));
        var provenance = new Provenance();
        provenance.read(shared, all.get(0));
        provenance.binder(outer, "a", all.get(0));
        provenance.binder(capturing, "a", all.get(3));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
        provenance.binder(capturing, "a", all.get(0));
        assertDoesNotThrow(() -> BindingCheck.check(outer, provenance));
    }

    /** Only a name no source variable can have goes unchecked: a generated one, or a qualified method binder. */
    @Test
    void aVarNotBuiltForAnElementIsAllowedOnlyForANameNoSourceCanHave() {
        assertDoesNotThrow(() -> BindingCheck.check(new PirTerm.Var("#free", INT), new Provenance()));
        assertDoesNotThrow(() -> BindingCheck.check(new PirTerm.Var("P.m", INT), new Provenance()));
        var unrecorded = assertThrows(IllegalStateException.class,
                () -> BindingCheck.check(new PirTerm.Var("a", INT), new Provenance()));
        assertTrue(unrecorded.getMessage().startsWith("I13"), unrecorded.getMessage());
    }

    /** A {@code LetRec} binds several names on one node: each is its own binder. */
    @Test
    void aMultiBindingLetRecKeepsEachName() {
        var all = elements();
        var v = new PirTerm.Var("b", INT);
        var rec = new PirTerm.LetRec(List.of(new PirTerm.Binding("a", zero()), new PirTerm.Binding("b", zero())), v);
        var provenance = new Provenance();
        provenance.binder(rec, "a", all.get(0));
        provenance.binder(rec, "b", all.get(1));
        provenance.read(v, all.get(1));
        assertDoesNotThrow(() -> BindingCheck.check(rec, provenance));
        provenance.read(v, all.get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(rec, provenance));
    }

    @Test
    void aListMatchKeepsItsHeadAndItsTail() {
        var all = elements();
        var head = new PirTerm.Var("a", INT);
        var match = new PirTerm.ListMatch(zero(), "a", "b", zero(), head);
        var provenance = new Provenance();
        provenance.binder(match, "a", all.get(0));
        provenance.binder(match, "b", all.get(1));
        provenance.read(head, all.get(0));
        assertDoesNotThrow(() -> BindingCheck.check(match, provenance));
        provenance.read(head, all.get(1));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(match, provenance));
    }

    @Test
    void aPairMatchKeepsItsTwoNames() {
        var all = elements();
        var second = new PirTerm.Var("b", INT);
        var match = new PirTerm.PairMatch(zero(), new PirType.PairType(INT, INT), "a", "b", second);
        var provenance = new Provenance();
        provenance.binder(match, "a", all.get(0));
        provenance.binder(match, "b", all.get(1));
        provenance.read(second, all.get(1));
        assertDoesNotThrow(() -> BindingCheck.check(match, provenance));
        provenance.read(second, all.get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(match, provenance));
    }

    /** A {@code DataMatch} binds per branch: two branches can bind one name to two elements. */
    @Test
    void aDataMatchBindsPerBranch() {
        var all = elements();
        var left = new PirTerm.Var("a", INT);
        var right = new PirTerm.Var("a", INT);
        var first = new PirTerm.MatchBranch("A", List.of("a"), List.of(INT), left, null);
        var second = new PirTerm.MatchBranch("B", List.of("a"), List.of(INT), right, null);
        var match = new PirTerm.DataMatch(zero(), List.of(first, second));
        var provenance = new Provenance();
        provenance.binder(first, "a", all.get(0));
        provenance.binder(second, "a", all.get(3));
        provenance.read(left, all.get(0));
        provenance.read(right, all.get(3));
        assertDoesNotThrow(() -> BindingCheck.check(match, provenance));
        provenance.read(right, all.get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(match, provenance));
    }

    /** What a rebuild of the term keeps: every binder, for each node and branch that binds. */
    @Test
    void aRebuiltNodeKeepsItsBinders() {
        var all = elements();
        var read = new PirTerm.Var("a", INT);
        var rec = new PirTerm.LetRec(List.of(new PirTerm.Binding("a", zero()), new PirTerm.Binding("b", zero())), read);
        var copy = new PirTerm.LetRec(rec.bindings(), read);
        var provenance = new Provenance();
        provenance.binder(rec, "a", all.get(0));
        provenance.read(read, all.get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(copy, provenance));
        provenance.rebuilt(rec, copy);
        assertDoesNotThrow(() -> BindingCheck.check(copy, provenance));

        var branch = new PirTerm.MatchBranch("A", List.of("a"), List.of(INT), read, null);
        var match = new PirTerm.DataMatch(zero(), List.of(branch));
        var rebuilt = new PirTerm.DataMatch(zero(), List.of(new PirTerm.MatchBranch("A", List.of("a"), List.of(INT), read, null)));
        provenance.binder(branch, "a", all.get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(rebuilt, provenance));
        provenance.rebuilt(match, rebuilt);
        assertDoesNotThrow(() -> BindingCheck.check(rebuilt, provenance));
    }

    /** Only a scope makes a binder, so a name is never chosen elsewhere (I13). */
    @Test
    void noBinderIsMadeOutsideAScope() {
        for (var constructor : Scope.Binder.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor.toString());
        }
    }
}
