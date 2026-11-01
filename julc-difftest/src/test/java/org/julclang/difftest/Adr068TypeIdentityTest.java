package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D7's rows that are observed on a script: a type is what javac resolved. A datum is decoded as its declared type,
 * so these evaluate each frontend's script on datums of each shape, at every level, against the Data the JVM's mapping
 * gives that type. A {@code .diffcase} compiles a method, so it cannot show a validator's datum: {@code Adr068SavedCasesOnJavacTest}
 * runs the same types through a method. The fixtures are in {@code adr068/m3a-fixtures}.
 */
class Adr068TypeIdentityTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();

    private static String fixture(String name) throws IOException {
        return Files.readString(REPO.resolve("julc-difftest/src/test/resources/adr068/m3a-fixtures").resolve(name));
    }

    private static Program compile(String source, CompilerFrontend frontend, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level)
                .setFrontend(frontend).setJavacGate(JavacGateMode.OFF).setLogger(message -> { })).compile(source, List.of()).program();
    }

    private static boolean accepts(Program program, PlutusData context) {
        var vm = JulcVm.create("Java");
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        return vm.evaluateWithArgs(program, List.of(context), new ExBudget(10_000_000_000L, 14_000_000L)) instanceof EvalResult.Success;
    }

    /** A spending context with {@code datum} as its datum, or none. */
    private static PlutusData spending(PlutusData datum) {
        var outRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        var option = datum == null ? PlutusData.constr(1) : PlutusData.constr(0, datum);
        return PlutusData.constr(0, PlutusData.integer(0), PlutusData.integer(0), PlutusData.constr(1, outRef, option));
    }

    record OptionalThing(BigInteger value) { }

    /** {@code OptionalThing} is a record: the JVM's Data for it is {@code Constr 0 [int]}, and nothing else is one. */
    @Test
    void aUserRecordNamedLikeOptionalIsARecord() throws IOException {
        String source = fixture("M3aUserOptionalThing.java");
        PlutusData record = PlutusData.constr(0, PlutusData.integer(5));
        assertEquals(record, ValueMapping.toData(new OptionalThing(BigInteger.valueOf(5))));
        for (var level : OptimizationLevel.values()) {
            var javac = compile(source, CompilerFrontend.JAVAC, level);
            assertTrue(accepts(javac, spending(record)), level + " the record");
            assertFalse(accepts(javac, spending(PlutusData.integer(5))), level + " its field alone");
            assertFalse(accepts(javac, spending(PlutusData.constr(1))), level + " Constr 1 is no record of this type");
            assertFalse(accepts(javac, spending(null)), level + " a spend with no datum");
        }
    }

    /** {@code Optional<PlutusData>} reads a datum that may be absent. */
    @Test
    void aQualifiedOptionalIsOptional() throws IOException {
        String source = fixture("M3aSpelledOptional.java");
        for (var level : OptimizationLevel.values()) {
            var javac = compile(source, CompilerFrontend.JAVAC, level);
            assertTrue(accepts(javac, spending(PlutusData.integer(5))), level + " a datum");
            assertTrue(accepts(javac, spending(null)), level + " no datum");
        }
    }

    /** The reference goes by the spelling: it takes the integer for the record, and needs a datum for the optional. */
    @Test
    void theReferenceReadsBothByTheirSpelling() throws IOException {
        for (var level : OptimizationLevel.values()) {
            var thing = compile(fixture("M3aUserOptionalThing.java"), CompilerFrontend.JAVAPARSER, level);
            assertFalse(accepts(thing, spending(PlutusData.constr(0, PlutusData.integer(5)))), level + " the record's own Data");
            assertTrue(accepts(thing, spending(PlutusData.integer(5))), level + " its field alone");
            var spelled = compile(fixture("M3aSpelledOptional.java"), CompilerFrontend.JAVAPARSER, level);
            assertFalse(accepts(spelled, spending(null)), level + " no datum");
        }
    }

    @Test
    void aUserRecordNamedLikeAnApiTypeIsNoApiType() throws IOException {
        assertEquals(1, new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)
                .setJavacGate(JavacGateMode.OFF)).compile(fixture("M3aUserBytesData.java"), List.of()).params().size());
    }
}
