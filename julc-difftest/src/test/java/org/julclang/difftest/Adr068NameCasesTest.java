package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 M2's exit: the saved #284 and #286 cases (ADR-062 T33) under the javac frontend. Each pin's divergence is a
 * name bound to the wrong declaration, so the check is the binding: the user's record is registered with the user's
 * components, and every {@code new} and accessor binds to it. What each evaluates to on the javac frontend is
 * {@code Adr068SavedCasesOnJavacTest}'s.
 */
class Adr068NameCasesTest {

    private static final Path SAVED = Path.of("src/test/resources/regressions");

    private static RegressionStore.SavedCase saved(String name) {
        return RegressionStore.load(SAVED).stream()
                .filter(c -> c.file().getFileName().toString().equals(name + ".diffcase")).findFirst().orElseThrow();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "reject-packageless-rational-record|Rational|denominator,numerator|numerator",
            "reject-packageless-rational-record-wildcard|Rational|denominator,numerator|numerator",
            "reject-packageless-pubkeyhash-record|PubKeyHash|h|h",
            "reject-top-level-user-value-on-constructor|Value|isEmpty|isEmpty",
            "reject-top-level-user-value-on-local|Value|isEmpty|isEmpty",
            "reject-user-value-accessor-on-constructor|Probe.Value|isEmpty|isEmpty",
            "agree-nested-rational|Probe.Rational|denominator,numerator|numerator"})
    void theNameBindsToTheUsersRecord(String pin, String identity, String components, String accessor) {
        String source = saved(pin).diffCase().source();
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());

        var registered = program.registry().snapshot().records().get(identity);
        assertNotNull(registered, identity + " is registered as the user's record");
        assertEquals(List.of(components.split(",")), registered.fields().stream().map(PirType.Field::name).toList());
        assertEquals(registered, program.registry().lookupNominal(identity).orElseThrow());

        var bindings = JavacEvidence.bindings(program);
        assertTrue(bindings.contains("new " + identity), bindings.toString());
        assertTrue(bindings.contains(identity + "." + accessor), bindings.toString());
        assertTrue(bindings.stream().noneMatch(b -> b.contains("org.julclang.ledger")), bindings.toString());
    }
}
