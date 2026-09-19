package org.julclang.compiler.pir;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class PirSubstitutionTest {
    private static final PirType INT = new PirType.IntegerType();

    private static PirTerm.Var v(String name) {
        return new PirTerm.Var(name, INT);
    }

    @Test
    void replacementRemainsFreeUnderEveryBinder() {
        var body = new PirTerm.App(v("target"), v("x"));
        var cases =
                List.<PirTerm>of(
                        new PirTerm.Lam("x", INT, body),
                        new PirTerm.Let("x", v("outside"), body),
                        new PirTerm.LetRec(List.of(new PirTerm.Binding("x", body)), body),
                        new PirTerm.PairMatch(
                                v("outside"), new PirType.PairType(INT, INT), "x", "y", body),
                        new PirTerm.ListMatch(v("outside"), "x", "tail", v("outside"), body),
                        new PirTerm.DataMatch(
                                v("outside"),
                                List.of(
                                        new PirTerm.MatchBranch(
                                                "C", List.of("x"), List.of(INT), body))),
                        new PirTerm.DataMatch(
                                v("outside"),
                                List.of(
                                        new PirTerm.MatchBranch(
                                                "C", List.of(), List.of(), body, "x"))));
        for (var term : cases) {
            var result = PirSubstitution.substitute(term, "target", v("x"));
            var free = PirSubstitution.collectFreeVarNames(result);
            assertTrue(free.contains("x"), () -> "captured replacement in " + term);
            assertFalse(free.contains("target"));
            assertEquals(result, PirSubstitution.substitute(term, "target", v("x")));
        }
    }

    @Test
    void freshNamesAvoidNestedBindersAndFreeNames() {
        var term =
                new PirTerm.Lam(
                        "x",
                        INT,
                        new PirTerm.Lam("$pir$subst$0", INT, new PirTerm.App(v("target"), v("x"))));
        var replacement = new PirTerm.App(v("x"), v("$pir$subst$1"));
        var result = (PirTerm.Lam) PirSubstitution.substitute(term, "target", replacement);
        assertEquals("$pir$subst$2", result.param());
        var inner = (PirTerm.Lam) result.body();
        assertEquals(v(result.param()), ((PirTerm.App) inner.body()).argument());
        assertEquals(Set.of("x", "$pir$subst$1"), PirSubstitution.collectFreeVarNames(result));
    }

    @Test
    void ordinaryLetValueAndListNilKeepTheirOuterScope() {
        var let = new PirTerm.Let("x", v("target"), new PirTerm.App(v("target"), v("x")));
        var result = (PirTerm.Let) PirSubstitution.substitute(let, "target", v("x"));
        assertEquals(v("x"), result.value());
        assertEquals(v(result.name()), ((PirTerm.App) result.body()).argument());
        var list = new PirTerm.ListMatch(v("target"), "x", "tail", v("target"), v("x"));
        var match = (PirTerm.ListMatch) PirSubstitution.substitute(list, "target", v("x"));
        assertEquals(v("x"), match.scrutinee());
        assertEquals(v("x"), match.nilBranch());
        assertEquals(v(match.headName()), match.consBranch());
    }

    @Test
    void targetShadowingAndRecursiveBindingScopeArePreserved() {
        var shadow = new PirTerm.Lam("target", INT, v("target"));
        assertSame(shadow, PirSubstitution.substitute(shadow, "target", v("x")));
        var recursive =
                new PirTerm.LetRec(
                        List.of(new PirTerm.Binding("x", v("x"))),
                        new PirTerm.App(v("target"), v("x")));
        var result = (PirTerm.LetRec) PirSubstitution.substitute(recursive, "target", v("x"));
        var name = result.bindings().getFirst().name();
        assertEquals(v(name), result.bindings().getFirst().value());
        assertEquals(new PirTerm.App(v("x"), v(name)), result.body());
    }
}
