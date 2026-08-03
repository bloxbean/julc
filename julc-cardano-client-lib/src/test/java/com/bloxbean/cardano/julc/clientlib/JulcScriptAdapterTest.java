package com.bloxbean.cardano.julc.clientlib;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.FlatDecodingException;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JulcScriptAdapter: Program → PlutusV3Script conversion.
 */
class JulcScriptAdapterTest {

    @Test
    void fromProgramProducesPlutusV3Script() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var script = JulcScriptAdapter.fromProgram(program);

        assertNotNull(script);
        assertInstanceOf(PlutusV3Script.class, script);
    }

    @Test
    void fromProgramProducesNonEmptyCborHex() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var script = JulcScriptAdapter.fromProgram(program);

        // The script should have CBOR hex content
        assertNotNull(script.getCborHex());
        assertFalse(script.getCborHex().isEmpty());
    }

    @Test
    void scriptHashReturns56CharHex() {
        // Script hash should be a 28-byte Blake2b-224 hash = 56 hex chars
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var hash = JulcScriptAdapter.scriptHash(program);

        assertNotNull(hash);
        assertEquals(56, hash.length(), "Script hash should be 56 hex characters (28 bytes)");
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }

    @Test
    void differentProgramsProduceDifferentHashes() {
        var prog1 = Program.plutusV3(Term.const_(Constant.unit()));
        var prog2 = Program.plutusV3(Term.const_(Constant.integer(java.math.BigInteger.ONE)));

        var hash1 = JulcScriptAdapter.scriptHash(prog1);
        var hash2 = JulcScriptAdapter.scriptHash(prog2);

        assertNotEquals(hash1, hash2, "Different programs should produce different script hashes");
    }

    @Test
    void sameProgramProducesSameHash() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var hash1 = JulcScriptAdapter.scriptHash(program);
        var hash2 = JulcScriptAdapter.scriptHash(program);

        assertEquals(hash1, hash2, "Same program should produce identical script hash");
    }

    @Test
    void fromProgramWithComplexTerm() {
        // A more complex program: \x -> x (identity function)
        var identity = Term.lam("x", Term.var(1));
        var program = Program.plutusV3(identity);
        var script = JulcScriptAdapter.fromProgram(program);

        assertNotNull(script);
        assertNotNull(script.getCborHex());
        assertFalse(script.getCborHex().isEmpty());
    }

    @Test
    void toProgramRoundTrips() {
        // Create a program, encode to script, decode back — should produce equivalent program
        var original = Program.plutusV3(Term.const_(Constant.integer(BigInteger.valueOf(42))));
        var script = JulcScriptAdapter.fromProgram(original);
        var decoded = JulcScriptAdapter.toProgram(script.getCborHex());

        assertEquals(original.major(), decoded.major());
        assertEquals(original.minor(), decoded.minor());
        assertEquals(original.patch(), decoded.patch());
        assertEquals(original.term().toString(), decoded.term().toString());
    }

    @Test
    void toProgramWithIdentityFunction() {
        var original = Program.plutusV3(Term.lam("x", Term.var(1)));
        var script = JulcScriptAdapter.fromProgram(original);
        var decoded = JulcScriptAdapter.toProgram(script.getCborHex());

        assertEquals(original.versionString(), decoded.versionString());
        // The round-tripped term should represent the same function
        assertNotNull(decoded.term());
    }

    @Test
    void protocolAwareDecodeAppliesPv11ConstructorLimit() {
        var fields = Collections.nCopies(1025, Term.error());
        var program = Program.plutusV3(new Term.Constr(0, fields));
        var script = JulcScriptAdapter.fromProgram(program);

        assertDoesNotThrow(() -> JulcScriptAdapter.toProgram(
                script.getCborHex(),
                LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3)));
        assertThrows(FlatDecodingException.class, () -> JulcScriptAdapter.toProgram(
                script.getCborHex(),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3)));
    }

    @Test
    void protocolAwareDecodeMatchesLedgerRemainderRule() {
        var original = new Program(1, 0, 0, Term.const_(Constant.unit()));
        String withGarbageRemainder = appendToInnerSerialisedScript(
                JulcScriptAdapter.fromProgram(original).getCborHex(), (byte) 0xff);

        // Plutus V1/V2 preserve historical acceptance of arbitrary bytes after
        // the inner CBOR bytestring. V3 rejects any such remainder.
        assertDoesNotThrow(() -> JulcScriptAdapter.toProgram(
                withGarbageRemainder,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V1)));
        assertDoesNotThrow(() -> JulcScriptAdapter.toProgram(
                withGarbageRemainder,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V2)));
        var error = assertThrows(RuntimeException.class, () -> JulcScriptAdapter.toProgram(
                withGarbageRemainder,
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3)));
        assertTrue(error.getMessage().contains("trailing byte"));
    }

    @Test
    void noTargetDecodeRetainsInnerRemainderTolerance() {
        var original = new Program(1, 0, 0, Term.const_(Constant.unit()));
        String withGarbageRemainder = appendToInnerSerialisedScript(
                JulcScriptAdapter.fromProgram(original).getCborHex(), (byte) 0xff);

        var decoded = assertDoesNotThrow(() -> JulcScriptAdapter.toProgram(withGarbageRemainder));
        assertEquals(original, decoded);
    }

    @Test
    void rejectsTaggedInnerSerialisedScriptForEveryDecodeTarget() {
        String tagged = tagInnerSerialisedScript(validV1ScriptCbor());

        assertRejectedForEveryDecodeTarget(tagged);
    }

    @Test
    void rejectsIndefiniteLengthInnerSerialisedScriptForEveryDecodeTarget() {
        String indefinite = makeInnerSerialisedScriptIndefinite(validV1ScriptCbor());

        assertRejectedForEveryDecodeTarget(indefinite);
    }

    @Test
    void acceptsNonCanonicalEightByteDefiniteLengthHeader() {
        var original = new Program(1, 0, 0, Term.const_(Constant.unit()));
        String nonCanonical = useEightByteLengthForInnerSerialisedScript(
                JulcScriptAdapter.fromProgram(original).getCborHex());

        assertEquals(original, JulcScriptAdapter.toProgram(nonCanonical));
        for (var language : PlutusLanguage.values()) {
            assertEquals(original, JulcScriptAdapter.toProgram(
                    nonCanonical, LedgerEvaluationTarget.pv11(language)));
        }
    }

    @Test
    void rejectsHeaderImmediatelyAboveDefiniteByteStringRange() {
        String reservedHeader = replaceInnerInitialByte(validV1ScriptCbor(), (byte) 0x5c);

        assertRejectedForEveryDecodeTarget(reservedHeader);
    }

    @Test
    void toProgramParameterized() {
        // Compile a parameterized validator, decode it, apply CCL params, re-encode
        var source = """
                import java.math.BigInteger;

                @SpendingValidator
                class ThresholdValidator {
                    @Param BigInteger threshold;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 0;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());

        // Encode to script CBOR
        var script = JulcScriptAdapter.fromProgram(result.program());
        String cborHex = script.getCborHex();

        // Decode back to Program
        var decoded = JulcScriptAdapter.toProgram(cborHex);

        // Apply param via plutus-core PlutusData
        var concrete = decoded.applyParams(PlutusData.integer(100));

        // Re-encode to script
        var concreteScript = JulcScriptAdapter.fromProgram(concrete);
        assertNotNull(concreteScript.getCborHex());
        assertFalse(concreteScript.getCborHex().isEmpty());

        // The concrete script should have a different hash than the parameterized one
        var hash1 = JulcScriptAdapter.scriptHash(result.program());
        var hash2 = JulcScriptAdapter.scriptHash(concrete);
        assertNotEquals(hash1, hash2, "Parameterized and concrete scripts should differ");
    }

    private static String appendToInnerSerialisedScript(String outerCborHex, byte remainder) {
        byte[] inner = unwrapOuterCbor(outerCborHex);
        byte[] withRemainder = Arrays.copyOf(inner, inner.length + 1);
        withRemainder[withRemainder.length - 1] = remainder;
        return wrapOuterCbor(withRemainder);
    }

    private static String validV1ScriptCbor() {
        var program = new Program(1, 0, 0, Term.const_(Constant.unit()));
        return JulcScriptAdapter.fromProgram(program).getCborHex();
    }

    private static void assertRejectedForEveryDecodeTarget(String cborHex) {
        assertInvalidInnerWrapper(() -> JulcScriptAdapter.toProgram(cborHex));
        for (var language : PlutusLanguage.values()) {
            assertInvalidInnerWrapper(() -> JulcScriptAdapter.toProgram(
                    cborHex, LedgerEvaluationTarget.pv11(language)));
        }
    }

    private static void assertInvalidInnerWrapper(Executable decode) {
        var error = assertThrows(RuntimeException.class, decode);
        assertTrue(error.getMessage().contains("untagged definite-length bytestring"),
                "Unexpected error: " + error.getMessage());
    }

    private static String tagInnerSerialisedScript(String outerCborHex) {
        byte[] inner = unwrapOuterCbor(outerCborHex);
        byte[] tagged = new byte[inner.length + 2];
        tagged[0] = (byte) 0xd8;
        tagged[1] = 0x18;
        System.arraycopy(inner, 0, tagged, 2, inner.length);
        return wrapOuterCbor(tagged);
    }

    private static String makeInnerSerialisedScriptIndefinite(String outerCborHex) {
        byte[] inner = unwrapOuterCbor(outerCborHex);
        byte[] indefinite = new byte[inner.length + 2];
        indefinite[0] = 0x5f;
        System.arraycopy(inner, 0, indefinite, 1, inner.length);
        indefinite[indefinite.length - 1] = (byte) 0xff;
        return wrapOuterCbor(indefinite);
    }

    private static String useEightByteLengthForInnerSerialisedScript(String outerCborHex) {
        byte[] inner = unwrapOuterCbor(outerCborHex);
        try {
            var innerItem = new CborDecoder(new ByteArrayInputStream(inner)).decodeNext();
            byte[] flat = assertInstanceOf(ByteString.class, innerItem).getBytes();
            byte[] nonCanonical = ByteBuffer.allocate(9 + flat.length)
                    .put((byte) 0x5b)
                    .putLong(flat.length)
                    .put(flat)
                    .array();
            return wrapOuterCbor(nonCanonical);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String replaceInnerInitialByte(String outerCborHex, byte replacement) {
        byte[] inner = unwrapOuterCbor(outerCborHex);
        inner[0] = replacement;
        return wrapOuterCbor(inner);
    }

    private static byte[] unwrapOuterCbor(String outerCborHex) {
        try {
            byte[] outer = HexFormat.of().parseHex(outerCborHex);
            var outerItem = new CborDecoder(new ByteArrayInputStream(outer)).decodeNext();
            return assertInstanceOf(ByteString.class, outerItem).getBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String wrapOuterCbor(byte[] inner) {
        try {
            var encoded = new ByteArrayOutputStream();
            new CborEncoder(encoded).encode(new CborBuilder()
                    .add(inner)
                    .build());
            return HexFormat.of().formatHex(encoded.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
