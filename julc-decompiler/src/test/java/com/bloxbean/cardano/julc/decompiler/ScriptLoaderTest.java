package com.bloxbean.cardano.julc.decompiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.decompiler.input.ScriptLoader;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScriptLoader: loading scripts from various formats.
 */
class ScriptLoaderTest {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Create a simple identity program: (program 1.1.0 (lam x x))
     */
    private Program simpleProgram() {
        return Program.plutusV3(Term.lam("x", Term.var(new NamedDeBruijn("x", 1))));
    }

    /**
     * Encode a program to FLAT bytes.
     */
    private byte[] toFlat(Program program) {
        return UplcFlatEncoder.encodeProgram(program);
    }

    /**
     * CBOR-wrap bytes as a bytestring.
     */
    private byte[] cborWrap(byte[] data) throws Exception {
        var baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder().add(data).build());
        return baos.toByteArray();
    }

    @Test
    void testFromRawFlatHex() {
        var program = simpleProgram();
        byte[] flat = toFlat(program);
        String hex = HEX.formatHex(flat);

        Program loaded = ScriptLoader.fromHex(hex);
        assertNotNull(loaded);
        assertEquals(1, loaded.major());
        assertEquals(1, loaded.minor());
        assertEquals(0, loaded.patch());
    }

    @Test
    void testFromSingleCborHex() throws Exception {
        var program = simpleProgram();
        byte[] flat = toFlat(program);
        byte[] cbor = cborWrap(flat);
        String hex = HEX.formatHex(cbor);

        Program loaded = ScriptLoader.fromHex(hex);
        assertNotNull(loaded);
        assertEquals("1.1.0", loaded.versionString());
    }

    @Test
    void testFromDoubleCborHex() throws Exception {
        var program = simpleProgram();
        byte[] flat = toFlat(program);
        byte[] inner = cborWrap(flat);
        byte[] outer = cborWrap(inner);
        String hex = HEX.formatHex(outer);

        Program loaded = ScriptLoader.fromHex(hex);
        assertNotNull(loaded);
        assertEquals("1.1.0", loaded.versionString());
    }

    @Test
    void testFromDoubleCborHexExplicit() throws Exception {
        var program = simpleProgram();
        byte[] flat = toFlat(program);
        byte[] inner = cborWrap(flat);
        byte[] outer = cborWrap(inner);
        String hex = HEX.formatHex(outer);

        Program loaded = ScriptLoader.fromDoubleCborHex(hex);
        assertNotNull(loaded);
        assertEquals("1.1.0", loaded.versionString());
    }

    @Test
    void testFromFlatBytes() {
        var program = simpleProgram();
        byte[] flat = toFlat(program);

        Program loaded = ScriptLoader.fromFlatBytes(flat);
        assertNotNull(loaded);
        assertEquals("1.1.0", loaded.versionString());
    }

    @Test
    void testHexStrippingPrefixes() throws Exception {
        var program = simpleProgram();
        byte[] flat = toFlat(program);
        String hex = HEX.formatHex(flat);

        // Test with 0x prefix
        Program loaded1 = ScriptLoader.fromHex("0x" + hex);
        assertNotNull(loaded1);

        // Test with whitespace
        Program loaded2 = ScriptLoader.fromHex("  " + hex + "  ");
        assertNotNull(loaded2);
    }

    @Test
    void testInvalidHexThrows() {
        assertThrows(ScriptLoader.ScriptLoadException.class,
                () -> ScriptLoader.fromHex("not-a-hex-string"));
    }

    @Test
    void testInvalidDataThrows() {
        assertThrows(ScriptLoader.ScriptLoadException.class,
                () -> ScriptLoader.fromHex("deadbeef"));
    }

    @Test
    void testComplexProgram() {
        // Apply(Lam("x", Var(x)), Const(42))
        var program = Program.plutusV3(
                Term.apply(
                        Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                        Term.const_(Constant.integer(42))));
        byte[] flat = toFlat(program);
        String hex = HEX.formatHex(flat);

        Program loaded = ScriptLoader.fromHex(hex);
        assertNotNull(loaded);
        assertTrue(loaded.term() instanceof Term.Apply);
    }

    @Test
    void testBuiltinProgram() {
        // Force(Force(Builtin(IfThenElse)))
        var program = Program.plutusV3(
                Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse))));
        byte[] flat = toFlat(program);
        String hex = HEX.formatHex(flat);

        Program loaded = ScriptLoader.fromHex(hex);
        assertNotNull(loaded);
        assertTrue(loaded.term() instanceof Term.Force);
    }
}
