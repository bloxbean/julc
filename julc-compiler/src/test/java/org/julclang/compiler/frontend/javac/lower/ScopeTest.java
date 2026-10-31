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
import java.util.IdentityHashMap;
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
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, a);
        provenance.put(let, a);
        assertDoesNotThrow(() -> BindingCheck.check(let, provenance));
    }

    @Test
    void aVarBoundToAnotherElementFails() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var let = new PirTerm.Let("a", zero(), v);
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, all.get(0));
        provenance.put(let, all.get(3));
        assertTrue(assertThrows(IllegalStateException.class, () -> BindingCheck.check(let, provenance))
                .getMessage().startsWith("I13"));
    }

    @Test
    void aFreeVarFails() {
        var v = new PirTerm.Var("a", INT);
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, elements().get(0));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(v, provenance));
    }

    @Test
    void anInnerBinderOfAnotherElementCaptures() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Lam("a", INT, v);
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, all.get(0));
        provenance.put(outer, all.get(0));
        provenance.put(inner, all.get(3));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
    }

    @Test
    void aGeneratedBinderCaptures() {
        var a = elements().get(0);
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Lam("a", INT, v);
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, a);
        provenance.put(outer, a);
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
    }

    @Test
    void aLetValueDoesNotSeeItsOwnBinder() {
        var all = elements();
        var v = new PirTerm.Var("a", INT);
        var inner = new PirTerm.Let("a", v, zero());
        var outer = new PirTerm.Let("a", zero(), inner);
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(v, all.get(0));
        provenance.put(outer, all.get(0));
        provenance.put(inner, all.get(3));
        assertDoesNotThrow(() -> BindingCheck.check(outer, provenance));
    }

    /** I13: every copy of a duplicated term is checked, in that copy's own scope. */
    @Test
    void oneCopyOfADuplicatedTermCapturedFails() {
        var all = elements();
        var shared = new PirTerm.Var("a", INT);
        var capturing = new PirTerm.Let("a", zero(), shared);
        var outer = new PirTerm.Let("a", zero(), new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), shared), capturing));
        var provenance = new IdentityHashMap<PirTerm, Element>();
        provenance.put(shared, all.get(0));
        provenance.put(outer, all.get(0));
        provenance.put(capturing, all.get(3));
        assertThrows(IllegalStateException.class, () -> BindingCheck.check(outer, provenance));
        provenance.put(capturing, all.get(0));
        assertDoesNotThrow(() -> BindingCheck.check(outer, provenance));
    }

    @Test
    void aVarNotBuiltForAnElementIsNotChecked() {
        assertDoesNotThrow(() -> BindingCheck.check(new PirTerm.Var("#free", INT), new IdentityHashMap<>()));
    }
}
