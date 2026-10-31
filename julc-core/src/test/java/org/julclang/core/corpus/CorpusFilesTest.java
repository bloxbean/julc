package org.julclang.core.corpus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stored form of a corpus text is lossless and injective (ADR-068 M0, F314). */
class CorpusFilesTest {

    /**
     * F314's probe: a source ending in an unpaired surrogate is stored in the marker form, whose bytes happen to be
     * valid UTF-8; the text those bytes decode to is a different source, and must not be stored as the same bytes.
     */
    @Test
    void aTextThatDecodesFromTheMarkerFormIsStoredApart() {
        String s = "class S { String s = \"\uD880";
        String t = new String(CorpusFiles.bytes(s), StandardCharsets.UTF_8);
        assertNotEquals(s, t);
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(t), "the probe needs a UTF-8-encodable twin");
        assertFalse(Arrays.equals(CorpusFiles.bytes(s), CorpusFiles.bytes(t)));
        assertNotEquals(CorpusFiles.sha256(s), CorpusFiles.sha256(t));
        assertEquals(s, CorpusFiles.text(CorpusFiles.bytes(s)));
        assertEquals(t, CorpusFiles.text(CorpusFiles.bytes(t)));
    }

    /** An ordinary source keeps its UTF-8 bytes, so the digests of the recorded corpus do not change. */
    @Test
    void ordinarySourcesKeepTheirUtf8Bytes() {
        for (String text : List.of("", "class A {}", "plain é ∑ 😀", "\0 a NUL first, but not the marker")) {
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), CorpusFiles.bytes(text), text);
            assertEquals(text, CorpusFiles.text(CorpusFiles.bytes(text)));
        }
    }

    /** Random texts over code units that exercise the marker, surrogates and ASCII: distinct texts, distinct bytes. */
    @Test
    void storedBytesAreInjective() {
        var random = new Random(20261031);
        char[] alphabet = {0, 'u', 't', 'f', '1', '6', 'a', 'é', '؀', '\uD800', '\uD880', '\uDC00', '�'};
        var seen = new HashMap<String, String>();
        var texts = new ArrayList<String>();
        texts.add("\0utf16\0");
        texts.add("\0utf16\0\0a");
        for (int i = 0; i < 50_000; i++) {
            var chars = new char[random.nextInt(9)];
            for (int j = 0; j < chars.length; j++) chars[j] = alphabet[random.nextInt(alphabet.length)];
            if (random.nextInt(4) == 0) texts.add("\0utf16\0" + new String(chars));
            texts.add(new String(chars));
        }
        for (String text : texts) {
            byte[] bytes = CorpusFiles.bytes(text);
            assertEquals(text, CorpusFiles.text(bytes));
            String key = new String(bytes, StandardCharsets.ISO_8859_1);
            String previous = seen.putIfAbsent(key, text);
            assertTrue(previous == null || previous.equals(text), "two texts, one stored form");
        }
    }
}
