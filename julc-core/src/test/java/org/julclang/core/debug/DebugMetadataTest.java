package org.julclang.core.debug;

import org.julclang.core.Constant;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugMetadataTest {
    private static final Program PROGRAM = Program.plutusV3(Term.const_(Constant.integer(1)));
    private static final String CONTENT = "class A {}\n";

    @Test
    void validatesExactArtifactAndDeterministicDigest() {
        var metadata = valid();
        metadata.validateArtifact(PROGRAM);
        assertEquals(metadata.canonicalDigest(), valid().canonicalDigest());
    }

    @Test
    void independentlyVerifiesDecodedLambdaAnchorsVectorsAndSlots() {
        var metadata = nested();
        metadata.validateArtifact(nestedProgram());
        var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(nestedProgram()));
        assertDoesNotThrow(() -> metadata.validateArtifact(decoded),
                "decoded lambda display names must not participate in binder verification");

        var point = metadata.suspensionPoints().get(2);
        var selfConsistentButFalse = new DebugMetadata.SuspensionPoint(
                point.occurrenceId(), point.termKind(), point.context(), point.sourceId(), point.range(),
                point.scopeChain(), List.of("lowered:a", "lowered:b"),
                List.of(new DebugMetadata.VisibleBinding("binding:a", DebugMetadata.Availability.AVAILABLE,
                        new DebugMetadata.EnvironmentSlot(1, "lowered:a", "layout:integer"), null, false)), null);
        var falseVector = withEvidence(metadata, metadata.loweredBindings(),
                List.of(metadata.suspensionPoints().get(0), metadata.suspensionPoints().get(1),
                        selfConsistentButFalse));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> falseVector.validateArtifact(nestedProgram()));

        var swappedAnchors = List.of(
                new DebugMetadata.LoweredBinding("lowered:a", "binding:a", "source-binder",
                        "layout:integer", 1),
                new DebugMetadata.LoweredBinding("lowered:b", "binding:b", "source-binder",
                        "layout:integer", 0));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(metadata, swappedAnchors, metadata.suspensionPoints())
                        .validateArtifact(nestedProgram()));

        var nonLambdaAnchor = List.of(
                metadata.loweredBindings().get(0),
                new DebugMetadata.LoweredBinding("lowered:b", "binding:b", "source-binder",
                        "layout:integer", 2));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(metadata, nonLambdaAnchor, metadata.suspensionPoints())
                        .validateArtifact(nestedProgram()));

        var duplicateAnchor = List.of(
                metadata.loweredBindings().get(0),
                new DebugMetadata.LoweredBinding("lowered:b", "binding:b", "source-binder",
                        "layout:integer", 0));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(metadata, duplicateAnchor, metadata.suspensionPoints())
                        .validateArtifact(nestedProgram()));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(metadata, List.of(metadata.loweredBindings().get(0)),
                        metadata.suspensionPoints()).validateArtifact(nestedProgram()));
    }

    @Test
    void rejectsInvalidDecodedDeBruijnIndicesAndParameterWrapperEvidence() {
        var metadata = nested();
        for (int invalidIndex : List.of(0, 3)) {
            var invalidProgram = Program.plutusV3(new Term.Lam("ignored",
                    new Term.Lam("ignored", new Term.Var(new NamedDeBruijn(invalidIndex)))));
            var artifact = new DebugMetadata.Artifact(metadata.artifact().compilerIdentity(),
                    metadata.artifact().targetId(), metadata.artifact().options(),
                    metadata.artifact().optimizationProvenance(), DebugMetadata.flatSha256(invalidProgram),
                    DebugMetadata.flatSha256(invalidProgram), invalidProgram.versionString(), DebugMetadata.TRAVERSAL,
                    3, metadata.artifact().sourceManifestSha256(), List.of());
            var invalidMetadata = new DebugMetadata(metadata.format(), metadata.major(), metadata.minor(),
                    metadata.requiredCapabilities(), artifact, metadata.sources(), metadata.scopes(),
                    metadata.bindings(), metadata.types(), metadata.layouts(), metadata.loweredBindings(),
                    metadata.suspensionPoints());
            assertThrows(DebugMetadata.ValidationException.class,
                    () -> invalidMetadata.validateArtifact(invalidProgram));
        }

        var parameter = new PlutusData.IntData(java.math.BigInteger.ONE);
        var applied = nestedProgram().applyParams(parameter);
        String digest = DebugMetadata.sha256(PlutusDataCborEncoder.encode(parameter));
        assertDoesNotThrow(() -> metadata.withAppliedArtifact(applied, 1, List.of(digest)));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> metadata.withAppliedArtifact(applied, 1, List.of(DebugMetadata.sha256(new byte[0]))));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> metadata.withAppliedArtifact(applied, 0, List.of(digest)));

        var secondParameter = new PlutusData.IntData(java.math.BigInteger.TWO);
        String secondDigest = DebugMetadata.sha256(PlutusDataCborEncoder.encode(secondParameter));
        var twiceApplied = nestedProgram().applyParams(parameter, secondParameter);
        var twiceAppliedMetadata = metadata.withAppliedArtifact(twiceApplied, 2, List.of(digest, secondDigest));
        assertThrows(DebugMetadata.ValidationException.class,
                () -> metadata.withAppliedArtifact(twiceApplied, 2, List.of(secondDigest, digest)));

        var unshiftedAnchors = twiceAppliedMetadata.loweredBindings().stream()
                .map(lowered -> new DebugMetadata.LoweredBinding(lowered.id(), lowered.sourceBindingId(),
                        lowered.generatedRole(), lowered.layoutId(), lowered.lambdaOccurrenceId() - 2))
                .toList();
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(twiceAppliedMetadata, unshiftedAnchors,
                        twiceAppliedMetadata.suspensionPoints()).validateArtifact(twiceApplied));

        var unshiftedPoints = twiceAppliedMetadata.suspensionPoints().stream()
                .map(point -> new DebugMetadata.SuspensionPoint(point.occurrenceId() - 2, point.termKind(),
                        point.context(), point.sourceId(), point.range(), point.scopeChain(),
                        point.expectedBinderIds(), point.visibleBindings(), point.unavailableReason()))
                .toList();
        assertThrows(DebugMetadata.ValidationException.class,
                () -> withEvidence(twiceAppliedMetadata, twiceAppliedMetadata.loweredBindings(),
                        unshiftedPoints).validateArtifact(twiceApplied));
    }

    @Test
    void rejectsUnknownVersionsCapabilitiesAndArtifactKinds() {
        var value = valid();
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 2,
                value.requiredCapabilities(), value.scopes(), value.bindings(), value.layouts()).validate());
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 1,
                List.of("future-evaluator-v9"), value.scopes(), value.bindings(), value.layouts()).validate());

        var badPoint = new DebugMetadata.SuspensionPoint(0, "lam", DebugMetadata.SourceContext.EXACT,
                "validator", range(), List.of("scope:method"), List.of(), List.of(), null);
        var wrongKind = new DebugMetadata(value.format(), value.major(), value.minor(), value.requiredCapabilities(),
                value.artifact(), value.sources(), value.scopes(), value.bindings(), value.types(), value.layouts(),
                value.loweredBindings(), List.of(badPoint));
        assertThrows(DebugMetadata.ValidationException.class, () -> wrongKind.validateArtifact(PROGRAM));
    }

    @Test
    void rejectsDuplicateIdsDanglingReferencesAndCycles() {
        var value = valid();
        var duplicate = new ArrayList<>(value.bindings());
        duplicate.add(value.bindings().getFirst());
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 1,
                value.requiredCapabilities(), value.scopes(), duplicate, value.layouts()).validate());

        var dangling = List.of(new DebugMetadata.Binding("binding:x", "x", range(), "scope:method",
                DebugMetadata.BindingKind.LOCAL, "long", "type:missing"));
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 1,
                value.requiredCapabilities(), value.scopes(), dangling, value.layouts()).validate());

        var cyclicScopes = List.of(
                new DebugMetadata.Scope("scope:method", "scope:block", "validator", range(),
                        DebugMetadata.ScopeKind.METHOD, "method:0"),
                new DebugMetadata.Scope("scope:block", "scope:method", "validator", range(),
                        DebugMetadata.ScopeKind.BLOCK, "method:0"));
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 1,
                value.requiredCapabilities(), cyclicScopes, value.bindings(), value.layouts()).validate());

        var cyclicLayouts = List.of(new DebugMetadata.Layout("layout:integer",
                DebugMetadata.LayoutKind.INTEGER, List.of("layout:integer")));
        assertThrows(DebugMetadata.ValidationException.class, () -> copy(value, 1,
                value.requiredCapabilities(), value.scopes(), value.bindings(), cyclicLayouts).validate());
    }

    @Test
    void collectionLimitsFailClosed() {
        var value = valid();
        var sources = new ArrayList<DebugMetadata.Source>();
        for (int i = 0; i <= DebugMetadata.MAX_SOURCES; i++) {
            sources.add(new DebugMetadata.Source("s" + i, "s" + i + ".java",
                    DebugMetadata.sha256(new byte[0]), "", List.of(0)));
        }
        var tooMany = new DebugMetadata(value.format(), value.major(), value.minor(), value.requiredCapabilities(),
                value.artifact(), sources, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(DebugMetadata.ValidationException.class, tooMany::validate);

        var invalidLines = new DebugMetadata.Source("validator", "A.java",
                DebugMetadata.sha256(CONTENT.getBytes(StandardCharsets.UTF_8)), CONTENT, List.of(0));
        var invalidLineTable = new DebugMetadata(value.format(), value.major(), value.minor(),
                value.requiredCapabilities(), value.artifact(), List.of(invalidLines), value.scopes(),
                value.bindings(), value.types(), value.layouts(), value.loweredBindings(),
                value.suspensionPoints());
        assertThrows(DebugMetadata.ValidationException.class, invalidLineTable::validate);
    }

    private static DebugMetadata valid() {
        String digest = DebugMetadata.flatSha256(PROGRAM);
        var source = new DebugMetadata.Source("validator", "A.java",
                DebugMetadata.sha256(CONTENT.getBytes(StandardCharsets.UTF_8)), CONTENT,
                List.of(0, CONTENT.length()));
        var artifact = new DebugMetadata.Artifact("julc-test", "test-target", "sourceMap=true", "none",
                digest, digest, PROGRAM.versionString(), DebugMetadata.TRAVERSAL, 1,
                DebugMetadata.sourceManifestSha256(List.of(source)), List.of());
        var scope = new DebugMetadata.Scope("scope:method", null, "validator", range(),
                DebugMetadata.ScopeKind.METHOD, "method:0");
        var type = new DebugMetadata.TypeInfo("type:integer", "BigInteger");
        var layout = new DebugMetadata.Layout("layout:integer", DebugMetadata.LayoutKind.INTEGER, List.of());
        var binding = new DebugMetadata.Binding("binding:x", "x", range(), "scope:method",
                DebugMetadata.BindingKind.LOCAL, "BigInteger", "type:integer");
        var point = new DebugMetadata.SuspensionPoint(0, "const", DebugMetadata.SourceContext.EXACT,
                "validator", range(), List.of("scope:method"), List.of(), List.of(), null);
        return new DebugMetadata(DebugMetadata.FORMAT, DebugMetadata.MAJOR, DebugMetadata.MINOR,
                List.of("anchored-lambda-occurrences-v1", "exact-environment-slots-v1"),
                artifact, List.of(source), List.of(scope),
                List.of(binding), List.of(type), List.of(layout), List.of(), List.of(point));
    }

    private static Program nestedProgram() {
        return Program.plutusV3(new Term.Lam("original-a",
                new Term.Lam("original-b", Term.var(1))));
    }

    private static DebugMetadata nested() {
        Program program = nestedProgram();
        var base = valid();
        var artifact = new DebugMetadata.Artifact("julc-test", "test-target", "sourceMap=true", "none",
                DebugMetadata.flatSha256(program), DebugMetadata.flatSha256(program), program.versionString(),
                DebugMetadata.TRAVERSAL, 3, base.artifact().sourceManifestSha256(), List.of());
        var bindingA = new DebugMetadata.Binding("binding:a", "a", range(), "scope:method",
                DebugMetadata.BindingKind.LOCAL, "BigInteger", "type:integer");
        var bindingB = new DebugMetadata.Binding("binding:b", "b", range(), "scope:method",
                DebugMetadata.BindingKind.LOCAL, "BigInteger", "type:integer");
        var loweredA = new DebugMetadata.LoweredBinding("lowered:a", "binding:a", "source-binder",
                "layout:integer", 0);
        var loweredB = new DebugMetadata.LoweredBinding("lowered:b", "binding:b", "source-binder",
                "layout:integer", 1);
        var root = point(0, "lam", List.of(), List.of());
        var inner = point(1, "lam", List.of("lowered:a"), List.of(
                new DebugMetadata.VisibleBinding("binding:a", DebugMetadata.Availability.AVAILABLE,
                        new DebugMetadata.EnvironmentSlot(1, "lowered:a", "layout:integer"), null, false)));
        var variable = point(2, "var", List.of("lowered:b", "lowered:a"), List.of(
                new DebugMetadata.VisibleBinding("binding:b", DebugMetadata.Availability.AVAILABLE,
                        new DebugMetadata.EnvironmentSlot(1, "lowered:b", "layout:integer"), null, false)));
        return new DebugMetadata(DebugMetadata.FORMAT, DebugMetadata.MAJOR, DebugMetadata.MINOR,
                List.of("anchored-lambda-occurrences-v1", "exact-environment-slots-v1"), artifact,
                base.sources(), base.scopes(), List.of(bindingA, bindingB), base.types(), base.layouts(),
                List.of(loweredA, loweredB), List.of(root, inner, variable));
    }

    private static DebugMetadata.SuspensionPoint point(int occurrence, String kind,
                                                        List<String> expected,
                                                        List<DebugMetadata.VisibleBinding> visible) {
        return new DebugMetadata.SuspensionPoint(occurrence, kind, DebugMetadata.SourceContext.EXACT,
                "validator", range(), List.of("scope:method"), expected, visible, null);
    }

    private static DebugMetadata withEvidence(DebugMetadata metadata,
                                               List<DebugMetadata.LoweredBinding> lowered,
                                               List<DebugMetadata.SuspensionPoint> points) {
        return new DebugMetadata(metadata.format(), metadata.major(), metadata.minor(),
                metadata.requiredCapabilities(), metadata.artifact(), metadata.sources(), metadata.scopes(),
                metadata.bindings(), metadata.types(), metadata.layouts(), lowered, points);
    }

    private static DebugMetadata.SourceRange range() {
        return new DebugMetadata.SourceRange("validator", 0, 5, 1, 1, 1, 6);
    }

    private static DebugMetadata copy(DebugMetadata value, int major, List<String> capabilities,
                                      List<DebugMetadata.Scope> scopes, List<DebugMetadata.Binding> bindings,
                                      List<DebugMetadata.Layout> layouts) {
        return new DebugMetadata(value.format(), major, value.minor(), capabilities, value.artifact(),
                value.sources(), scopes, bindings, value.types(), layouts, value.loweredBindings(),
                value.suspensionPoints());
    }
}
