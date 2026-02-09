package com.bloxbean.cardano.plutus.clientlib;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlutusScriptAdapter: Program → PlutusV3Script conversion.
 */
class PlutusScriptAdapterTest {

    @Test
    void fromProgramProducesPlutusV3Script() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var script = PlutusScriptAdapter.fromProgram(program);

        assertNotNull(script);
        assertInstanceOf(PlutusV3Script.class, script);
    }

    @Test
    void fromProgramProducesNonEmptyCborHex() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var script = PlutusScriptAdapter.fromProgram(program);

        // The script should have CBOR hex content
        assertNotNull(script.getCborHex());
        assertFalse(script.getCborHex().isEmpty());
    }

    @Test
    void scriptHashReturns56CharHex() {
        // Script hash should be a 28-byte Blake2b-224 hash = 56 hex chars
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var hash = PlutusScriptAdapter.scriptHash(program);

        assertNotNull(hash);
        assertEquals(56, hash.length(), "Script hash should be 56 hex characters (28 bytes)");
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }

    @Test
    void differentProgramsProduceDifferentHashes() {
        var prog1 = Program.plutusV3(Term.const_(Constant.unit()));
        var prog2 = Program.plutusV3(Term.const_(Constant.integer(java.math.BigInteger.ONE)));

        var hash1 = PlutusScriptAdapter.scriptHash(prog1);
        var hash2 = PlutusScriptAdapter.scriptHash(prog2);

        assertNotEquals(hash1, hash2, "Different programs should produce different script hashes");
    }

    @Test
    void sameProgramProducesSameHash() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var hash1 = PlutusScriptAdapter.scriptHash(program);
        var hash2 = PlutusScriptAdapter.scriptHash(program);

        assertEquals(hash1, hash2, "Same program should produce identical script hash");
    }

    @Test
    void fromProgramWithComplexTerm() {
        // A more complex program: \x -> x (identity function)
        var identity = Term.lam("x", Term.var(1));
        var program = Program.plutusV3(identity);
        var script = PlutusScriptAdapter.fromProgram(program);

        assertNotNull(script);
        assertNotNull(script.getCborHex());
        assertFalse(script.getCborHex().isEmpty());
    }

    @Test
    void toProgramRoundTrips() {
        // Create a program, encode to script, decode back — should produce equivalent program
        var original = Program.plutusV3(Term.const_(Constant.integer(BigInteger.valueOf(42))));
        var script = PlutusScriptAdapter.fromProgram(original);
        var decoded = PlutusScriptAdapter.toProgram(script.getCborHex());

        assertEquals(original.major(), decoded.major());
        assertEquals(original.minor(), decoded.minor());
        assertEquals(original.patch(), decoded.patch());
        assertEquals(original.term().toString(), decoded.term().toString());
    }

    @Test
    void toProgramWithIdentityFunction() {
        var original = Program.plutusV3(Term.lam("x", Term.var(1)));
        var script = PlutusScriptAdapter.fromProgram(original);
        var decoded = PlutusScriptAdapter.toProgram(script.getCborHex());

        assertEquals(original.versionString(), decoded.versionString());
        // The round-tripped term should represent the same function
        assertNotNull(decoded.term());
    }

    @Test
    void toProgramParameterized() {
        // Compile a parameterized validator, decode it, apply CCL params, re-encode
        var source = """
                import java.math.BigInteger;

                @Validator
                class ThresholdValidator {
                    @Param BigInteger threshold;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 0;
                    }
                }
                """;
        var result = new PlutusCompiler().compile(source);
        assertTrue(result.isParameterized());

        // Encode to script CBOR
        var script = PlutusScriptAdapter.fromProgram(result.program());
        String cborHex = script.getCborHex();

        // Decode back to Program
        var decoded = PlutusScriptAdapter.toProgram(cborHex);

        // Apply param via plutus-core PlutusData
        var concrete = decoded.applyParams(PlutusData.integer(100));

        // Re-encode to script
        var concreteScript = PlutusScriptAdapter.fromProgram(concrete);
        assertNotNull(concreteScript.getCborHex());
        assertFalse(concreteScript.getCborHex().isEmpty());

        // The concrete script should have a different hash than the parameterized one
        var hash1 = PlutusScriptAdapter.scriptHash(result.program());
        var hash2 = PlutusScriptAdapter.scriptHash(concrete);
        assertNotEquals(hash1, hash2, "Parameterized and concrete scripts should differ");
    }
}
