package com.bloxbean.julc.cli.repl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MethodDocExtractorTest {

    private static MethodDocExtractor extractor;

    private static final String SAMPLE_SOURCE = """
            package com.example;

            import java.math.BigInteger;

            /**
             * Mathematical operations compiled from Java source to UPLC.
             */
            public class MathLib {

                /** Returns the absolute value of an integer. */
                public static BigInteger abs(BigInteger x) {
                    return x;
                }

                /**
                 * Returns base raised to the power of exp.
                 *
                 * Exponent must be non-negative.
                 */
                public static BigInteger pow(BigInteger base, BigInteger exp) {
                    return base;
                }

                /** Returns (base^exp) mod modulus using the builtin ExpModInteger operation. */
                public static BigInteger expMod(BigInteger base, BigInteger exp, BigInteger mod) {
                    return base;
                }

                public static BigInteger noJavadoc(BigInteger x) {
                    return x;
                }

                private static void hidden() {}
            }
            """;

    private static final String MAP_SOURCE = """
            package com.example;

            import java.util.Optional;

            /**
             * Map (association list) operations compiled from Java source to UPLC.
             *
             * Uses JulcMap for type-safe map manipulation.
             */
            public class MapLib {

                /** Return an empty map. */
                @SuppressWarnings("unchecked")
                public static JulcMap<PlutusData, PlutusData> empty() {
                    return null;
                }

                /** Look up a key. Returns Optional.of(value) if found, Optional.empty() if not. */
                @SuppressWarnings("unchecked")
                public static Optional<PlutusData> lookup(JulcMap<PlutusData, PlutusData> map, PlutusData key) {
                    return null;
                }
            }
            """;

    private static final String NO_JAVADOC_SOURCE = """
            package com.example;

            public class Bare {
                public static int add(int a, int b) {
                    return a + b;
                }
            }
            """;

    @BeforeAll
    static void setup() {
        extractor = new MethodDocExtractor(Map.of(
                "MathLib", SAMPLE_SOURCE,
                "MapLib", MAP_SOURCE,
                "Bare", NO_JAVADOC_SOURCE));
    }

    @Test
    void lookupMethod_found() {
        var doc = extractor.lookupMethod("MathLib.abs");
        assertNotNull(doc);
        assertEquals("MathLib", doc.className());
        assertEquals("abs", doc.methodName());
        assertTrue(doc.signature().contains("MathLib.abs("));
        assertTrue(doc.signature().contains("BigInteger x"));
        assertTrue(doc.signature().contains("-> BigInteger"));
        assertTrue(doc.javadoc().contains("absolute value"));
    }

    @Test
    void lookupMethod_pow_multiLineJavadoc() {
        var doc = extractor.lookupMethod("MathLib.pow");
        assertNotNull(doc);
        assertTrue(doc.signature().contains("BigInteger base, BigInteger exp"));
        assertTrue(doc.javadoc().contains("base raised to the power"));
        assertTrue(doc.javadoc().contains("non-negative"));
    }

    @Test
    void lookupMethod_threeParams() {
        var doc = extractor.lookupMethod("MathLib.expMod");
        assertNotNull(doc);
        assertTrue(doc.signature().contains("BigInteger base, BigInteger exp, BigInteger mod"));
    }

    @Test
    void lookupMethod_noJavadoc_emptyString() {
        var doc = extractor.lookupMethod("MathLib.noJavadoc");
        assertNotNull(doc, "Methods without javadoc should still be found");
        assertEquals("", doc.javadoc());
        assertTrue(doc.signature().contains("MathLib.noJavadoc("));
    }

    @Test
    void lookupMethod_privateNotIncluded() {
        var doc = extractor.lookupMethod("MathLib.hidden");
        assertNull(doc, "Private methods should not be extracted");
    }

    @Test
    void lookupMethod_unknown_returnsNull() {
        assertNull(extractor.lookupMethod("UnknownClass.foo"));
        assertNull(extractor.lookupMethod("MathLib.nonexistent"));
    }

    @Test
    void lookupClass_found() {
        var doc = extractor.lookupClass("MathLib");
        assertNotNull(doc);
        assertEquals("MathLib", doc.className());
        assertTrue(doc.javadoc().contains("Mathematical operations"));
        assertTrue(doc.methodNames().contains("abs"));
        assertTrue(doc.methodNames().contains("pow"));
        assertTrue(doc.methodNames().contains("expMod"));
        assertTrue(doc.methodNames().contains("noJavadoc"));
    }

    @Test
    void lookupClass_unknown_returnsNull() {
        assertNull(extractor.lookupClass("UnknownClass"));
    }

    @Test
    void lookupClass_mapLib_multiLineClassJavadoc() {
        var doc = extractor.lookupClass("MapLib");
        assertNotNull(doc);
        assertTrue(doc.javadoc().contains("Map (association list)"));
        assertTrue(doc.methodNames().contains("empty"));
        assertTrue(doc.methodNames().contains("lookup"));
    }

    @Test
    void lookupMethod_withAnnotation_javadocExtracted() {
        var doc = extractor.lookupMethod("MapLib.empty");
        assertNotNull(doc);
        assertTrue(doc.javadoc().contains("empty map"));
    }

    @Test
    void lookupMethod_withAnnotation_signatureCorrect() {
        var doc = extractor.lookupMethod("MapLib.lookup");
        assertNotNull(doc);
        // Generic types should be simplified and commas inside <> preserved
        assertTrue(doc.signature().contains("MapLib.lookup("), "Got: " + doc.signature());
        assertTrue(doc.signature().contains("JulcMap<PlutusData, PlutusData> map"),
                "Generic params should be intact, got: " + doc.signature());
    }

    @Test
    void lookupClass_noJavadoc_emptyString() {
        var doc = extractor.lookupClass("Bare");
        assertNotNull(doc);
        assertEquals("", doc.javadoc());
        assertTrue(doc.methodNames().contains("add"));
    }

    // --- Formatting tests ---

    @Test
    void formatMethodDoc_signature_and_javadoc() {
        var doc = extractor.lookupMethod("MathLib.abs");
        String formatted = MethodDocExtractor.formatMethodDoc(doc);
        assertTrue(formatted.contains("MathLib.abs("));
        assertTrue(formatted.contains("absolute value"));
    }

    @Test
    void formatMethodDoc_noJavadoc() {
        var doc = extractor.lookupMethod("MathLib.noJavadoc");
        String formatted = MethodDocExtractor.formatMethodDoc(doc);
        assertTrue(formatted.contains("MathLib.noJavadoc("));
        // Should not have extra blank lines
        assertFalse(formatted.contains("\n\n"));
    }

    @Test
    void formatClassDoc_showsMethodList() {
        var doc = extractor.lookupClass("MathLib");
        String formatted = MethodDocExtractor.formatClassDoc(doc);
        assertTrue(formatted.contains("MathLib"));
        assertTrue(formatted.contains("Methods:"));
        assertTrue(formatted.contains("abs"));
        assertTrue(formatted.contains("pow"));
    }

    // --- Static helper tests ---

    @Test
    void cleanJavadoc_stripsStars() {
        String raw = " * First line.\n * Second line.\n ";
        String cleaned = MethodDocExtractor.cleanJavadoc(raw);
        assertEquals("First line.\nSecond line.", cleaned);
    }

    @Test
    void cleanJavadoc_stripsParagraphTag() {
        String raw = " * Line one.\n * <p>\n * Line two.\n ";
        String cleaned = MethodDocExtractor.cleanJavadoc(raw);
        assertTrue(cleaned.contains("Line one."));
        assertTrue(cleaned.contains("Line two."));
        assertFalse(cleaned.contains("<p>"));
    }

    @Test
    void cleanJavadoc_skipsParamTags() {
        String raw = " * Does something.\n * @param x the value\n * @return the result\n ";
        String cleaned = MethodDocExtractor.cleanJavadoc(raw);
        assertEquals("Does something.", cleaned);
    }

    @Test
    void simplifyType_simple() {
        assertEquals("BigInteger", MethodDocExtractor.simplifyType("BigInteger"));
        assertEquals("int", MethodDocExtractor.simplifyType("int"));
    }

    @Test
    void simplifyType_fullyQualified() {
        assertEquals("BigInteger", MethodDocExtractor.simplifyType("java.math.BigInteger"));
    }

    @Test
    void simplifyType_generic() {
        assertEquals("Optional<PlutusData>",
                MethodDocExtractor.simplifyType("Optional<PlutusData>"));
        assertEquals("Tuple2<BigInteger, BigInteger>",
                MethodDocExtractor.simplifyType("Tuple2<BigInteger, BigInteger>"));
    }

    @Test
    void simplifyType_nestedGeneric() {
        assertEquals("JulcMap<PlutusData, PlutusData>",
                MethodDocExtractor.simplifyType("JulcMap<PlutusData, PlutusData>"));
    }

    @Test
    void cleanParams_simple() {
        assertEquals("BigInteger x", MethodDocExtractor.cleanParams("BigInteger x"));
    }

    @Test
    void cleanParams_multiple() {
        assertEquals("BigInteger a, BigInteger b",
                MethodDocExtractor.cleanParams("BigInteger a, BigInteger b"));
    }

    @Test
    void cleanParams_empty() {
        assertEquals("", MethodDocExtractor.cleanParams(""));
    }

    @Test
    void cleanParams_genericType_commaPreserved() {
        assertEquals("JulcMap<PlutusData, PlutusData> map, PlutusData key",
                MethodDocExtractor.cleanParams("JulcMap<PlutusData, PlutusData> map, PlutusData key"));
    }

    @Test
    void splitParams_respectsAngleBrackets() {
        var result = MethodDocExtractor.splitParams("JulcMap<PlutusData, PlutusData> map, PlutusData key");
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("JulcMap<PlutusData, PlutusData>"));
        assertTrue(result.get(1).contains("PlutusData key"));
    }

    @Test
    void splitParams_noGenerics() {
        var result = MethodDocExtractor.splitParams("BigInteger a, BigInteger b");
        assertEquals(2, result.size());
    }

    @Test
    void buildSignature_format() {
        String sig = MethodDocExtractor.buildSignature("MathLib", "pow", "BigInteger", "BigInteger base, BigInteger exp");
        assertEquals("MathLib.pow(BigInteger base, BigInteger exp) -> BigInteger", sig);
    }

    // --- Integration with real stdlib sources ---

    @Test
    void realLibraryPool_mathLibPow() {
        var pool = com.bloxbean.cardano.julc.compiler.LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        if (pool.containsKey("MathLib")) {
            var realExtractor = new MethodDocExtractor(pool);
            var doc = realExtractor.lookupMethod("MathLib.pow");
            assertNotNull(doc, "MathLib.pow should be found in real stdlib");
            assertTrue(doc.signature().contains("pow"));
            assertFalse(doc.javadoc().isEmpty(), "MathLib.pow should have javadoc");
        }
        // Skip if stdlib not on classpath (CI without julc-stdlib dependency)
    }

    @Test
    void realLibraryPool_classDocExists() {
        var pool = com.bloxbean.cardano.julc.compiler.LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        if (pool.containsKey("MapLib")) {
            var realExtractor = new MethodDocExtractor(pool);
            var doc = realExtractor.lookupClass("MapLib");
            assertNotNull(doc);
            assertFalse(doc.methodNames().isEmpty(), "MapLib should have methods");
        }
    }
}
