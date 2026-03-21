package com.bloxbean.julc.cli.scaffold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackageNameUtilsTest {

    @Test
    void sanitize_removesHyphens() {
        assertEquals("myvalidator", PackageNameUtils.sanitize("my-validator"));
    }

    @Test
    void sanitize_removesDots() {
        assertEquals("myapp", PackageNameUtils.sanitize("my.app"));
    }

    @Test
    void sanitize_removesSpaces() {
        assertEquals("myproject", PackageNameUtils.sanitize("my project"));
    }

    @Test
    void sanitize_lowercases() {
        assertEquals("myvalidator", PackageNameUtils.sanitize("MyValidator"));
    }

    @Test
    void sanitize_stripsLeadingDigits() {
        assertEquals("app", PackageNameUtils.sanitize("123app"));
    }

    @Test
    void sanitize_fallbackOnEmpty() {
        assertEquals("myproject", PackageNameUtils.sanitize("---"));
    }

    @Test
    void sanitize_fallbackOnNull() {
        assertEquals("myproject", PackageNameUtils.sanitize(null));
    }

    @Test
    void sanitize_fallbackOnBlank() {
        assertEquals("myproject", PackageNameUtils.sanitize("   "));
    }

    @Test
    void sanitize_preservesUnderscores() {
        assertEquals("my_app", PackageNameUtils.sanitize("my_app"));
    }

    @Test
    void toPath_convertsDots() {
        assertEquals("com/example/foo", PackageNameUtils.toPath("com.example.foo"));
    }

    @Test
    void toPath_singleSegment() {
        assertEquals("myapp", PackageNameUtils.toPath("myapp"));
    }

    @Test
    void validate_validPackage() {
        assertNull(PackageNameUtils.validate("com.example.myapp"));
    }

    @Test
    void validate_singleSegment() {
        assertNull(PackageNameUtils.validate("myapp"));
    }

    @Test
    void validate_rejectsEmpty() {
        assertNotNull(PackageNameUtils.validate(""));
    }

    @Test
    void validate_rejectsNull() {
        assertNotNull(PackageNameUtils.validate(null));
    }

    @Test
    void validate_rejectsEmptySegment() {
        assertNotNull(PackageNameUtils.validate("com..example"));
    }

    @Test
    void validate_rejectsLeadingDigit() {
        assertNotNull(PackageNameUtils.validate("com.1example"));
    }

    @Test
    void validate_rejectsKeyword() {
        assertNotNull(PackageNameUtils.validate("com.class.myapp"));
    }

    @Test
    void validate_rejectsSpecialChars() {
        assertNotNull(PackageNameUtils.validate("com.my-app"));
    }
}
