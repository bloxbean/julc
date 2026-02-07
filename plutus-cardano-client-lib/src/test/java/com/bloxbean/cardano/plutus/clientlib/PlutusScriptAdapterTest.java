package com.bloxbean.cardano.plutus.clientlib;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import org.junit.jupiter.api.Test;

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
}
