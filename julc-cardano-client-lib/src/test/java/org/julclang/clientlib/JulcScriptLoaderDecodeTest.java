package org.julclang.clientlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The loader compares resource URLs with resource names after decoding their {@code %XX} escapes (#326). */
class JulcScriptLoaderDecodeTest {

    @Test
    void escapesAreDecodedAsUtf8() {
        assertEquals("file:/a b/com/bä/日/V.class",
                JulcScriptLoader.decode("file:/a%20b/com/b%c3%a4/%E6%97%A5/V.class"));
    }

    @Test
    void plusAndUnescapedTextAreKept() {
        assertEquals("jar:file:/x+y.jar!/com/b/V$1.class", JulcScriptLoader.decode("jar:file:/x+y.jar!/com/b/V$1.class"));
        assertEquals("file:/ä/𝔸/V.class", JulcScriptLoader.decode("file:/ä/𝔸/V.class"));
    }

    @Test
    void aMalformedEscapeIsKept() {
        assertEquals("file:/50%/V%zz/%4", JulcScriptLoader.decode("file:/50%/V%zz/%4"));
    }
}
