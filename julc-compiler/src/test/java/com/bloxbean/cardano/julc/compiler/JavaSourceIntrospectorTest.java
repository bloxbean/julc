package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceIntrospectorTest {

    @Test
    void inspectIgnoresAnnotationTextInCommentsAndStrings() {
        var source = """
                /**
                 * Mentions @SpendingValidator and @OnchainLibrary.
                 */
                class Helper {
                    static final String DOC = "@MintingValidator @OnchainLibrary";
                }
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertTrue(info.validatorType().isEmpty());
        assertTrue(info.legacyValidatorType().isEmpty());
        assertTrue(info.topLevelOnchainLibrary().isEmpty());
        assertTrue(info.nestedOnchainLibraries().isEmpty());
    }

    @Test
    void inspectFindsSupportedValidatorByFullyQualifiedAnnotationName() {
        var source = """
                package com.example;

                @com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator
                class Mint {}
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertTrue(info.validatorType().isPresent());
        assertEquals("Mint", info.validatorType().get().simpleName());
        assertEquals("com.example.Mint", info.validatorType().get().fqcn());
        assertEquals("MintingValidator", info.validatorType().get().annotationName());
        assertEquals("PlutusScriptV3-Minting", info.scriptType().orElseThrow());
    }

    @Test
    void inspectFindsSupportedValidatorByImportedSimpleAnnotationName() {
        var source = """
                package com.example;

                import com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator;

                @WithdrawValidator
                class Withdraw {}
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertEquals("Withdraw", info.validatorType().orElseThrow().simpleName());
        assertEquals("WithdrawValidator", info.validatorType().orElseThrow().annotationName());
        assertEquals("PlutusScriptV3-Withdraw", info.scriptType().orElseThrow());
    }

    @Test
    void inspectSelectsFirstAnnotatedTopLevelType() {
        var source = """
                class Helper {}

                @SpendingValidator
                class RealValidator {}
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertEquals("RealValidator", info.validatorType().orElseThrow().simpleName());
        assertEquals("Helper", info.topLevelTypeNames().get(0));
    }

    @Test
    void inspectSelectsFirstAnnotatedTopLevelTypeWhenMultipleCompete() {
        var source = """
                @MintingValidator
                class First {}

                @SpendingValidator
                class Second {}
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertEquals("First", info.validatorType().orElseThrow().simpleName());
        assertEquals("MintingValidator", info.validatorType().orElseThrow().annotationName());
    }

    @Test
    void inspectHandlesSourceWithoutPackageDeclaration() {
        var source = """
                @SpendingValidator
                class NoPackage {}
                """;

        var validator = JavaSourceIntrospector.inspect(source)
                .validatorType()
                .orElseThrow();

        assertEquals("", validator.packageName());
        assertEquals("NoPackage", validator.fqcn());
    }

    @Test
    void inspectReportsNestedOnchainLibrarySeparately() {
        var source = """
                class Outer {
                    @OnchainLibrary
                    static class Inner {}
                }
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertTrue(info.topLevelOnchainLibrary().isEmpty());
        assertEquals(1, info.nestedOnchainLibraries().size());
        assertEquals("Inner", info.nestedOnchainLibraries().get(0).simpleName());
        assertFalse(info.nestedOnchainLibraries().get(0).topLevel());
    }

    @Test
    void inspectAllowsUnrelatedAnnotationsOnOnchainLibrary() {
        var source = """
                package com.example;

                @Getter
                @Setter
                @OnchainLibrary
                class DecoratedLib {}
                """;

        var library = JavaSourceIntrospector.inspect(source)
                .topLevelOnchainLibrary()
                .orElseThrow();

        assertEquals("DecoratedLib", library.simpleName());
        assertEquals("com.example.DecoratedLib", library.fqcn());
        assertTrue(library.annotationNames().contains("Getter"));
        assertTrue(library.annotationNames().contains("Setter"));
        assertTrue(library.annotationNames().contains("OnchainLibrary"));
    }

    @Test
    void inspectReportsOnchainLibraryValidatorRoleConflict() {
        var source = """
                package com.example;

                @OnchainLibrary
                @SpendingValidator
                class Confused {}
                """;

        var conflict = JavaSourceIntrospector.inspect(source)
                .firstRoleConflict()
                .orElseThrow();

        assertEquals("Confused", conflict.simpleName());
        assertEquals("com.example.Confused", conflict.fqcn());
        assertEquals(java.util.List.of("SpendingValidator"), conflict.conflictingAnnotations());
        assertTrue(conflict.message().contains("@OnchainLibrary"));
        assertTrue(conflict.message().contains("@SpendingValidator"));
    }

    @Test
    void inspectDetectsLegacyValidatorForDiagnosticsOnly() {
        var source = """
                @Validator
                class Old {}
                """;

        var info = JavaSourceIntrospector.inspect(source);

        assertTrue(info.validatorType().isEmpty());
        var legacy = info.legacyValidatorType().orElseThrow();
        assertEquals("Validator", legacy.annotationName());
        assertEquals("Old", legacy.simpleName());
        assertTrue(JavaSourceIntrospector.legacyAnnotationMigrationMessage(legacy)
                .contains("Use @SpendingValidator instead"));
    }

    @Test
    void inspectDetectsLegacyMintingPolicyForDiagnosticsOnly() {
        var source = """
                @MintingPolicy
                class OldMint {}
                """;

        var legacy = JavaSourceIntrospector.inspect(source)
                .legacyValidatorType()
                .orElseThrow();

        assertEquals("MintingPolicy", legacy.annotationName());
        assertTrue(JavaSourceIntrospector.legacyAnnotationMigrationMessage(legacy)
                .contains("Use @MintingValidator instead"));
    }

    @Test
    void scriptPurposeMapsEverySupportedValidatorAnnotation() {
        assertEquals(JulcCompiler.ScriptPurpose.SPENDING,
                JavaSourceIntrospector.scriptPurpose("SpendingValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.MINTING,
                JavaSourceIntrospector.scriptPurpose("MintingValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.WITHDRAW,
                JavaSourceIntrospector.scriptPurpose("WithdrawValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.CERTIFYING,
                JavaSourceIntrospector.scriptPurpose("CertifyingValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.VOTING,
                JavaSourceIntrospector.scriptPurpose("VotingValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.PROPOSING,
                JavaSourceIntrospector.scriptPurpose("ProposingValidator"));
        assertEquals(JulcCompiler.ScriptPurpose.MULTI,
                JavaSourceIntrospector.scriptPurpose("MultiValidator"));
    }

    @Test
    void inspectParserFailureThrows() {
        var ex = assertThrows(JavaSourceIntrospector.SourceParseException.class,
                () -> JavaSourceIntrospector.inspect("@SpendingValidator class Broken {"));

        assertFalse(ex.problems().isEmpty());
    }
}
