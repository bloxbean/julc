package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.DiagnosticCodes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ADR-068 D3's ownership check, by provenance, before javac runs. A unit is a <em>bundled source</em> when its
 * primary type is in the bundled manifest and its text has that entry's digest; it is then accepted as it is. Any
 * other unit is rejected with {@code JULC0081} when it declares a type in a package the stubs or the bundled
 * sources define, or a type with a stub's or bundled source's name: no source can replace or extend an API type or
 * a bundled library.
 */
final class Ownership {

    private Ownership() {
    }

    static List<JavacDiagnostic> check(JavacPlatform platform, JavacFrontend.BundledSources bundled,
                                       List<JavacSourceFile> roots, List<JavacSourceFile> pool) {
        Set<String> packages = new HashSet<>(platform.stubPackages());
        packages.addAll(bundled.packages());
        Set<String> names = new HashSet<>(platform.stubTopLevelNames());
        names.addAll(bundled.digests().keySet());
        var rejections = new ArrayList<JavacDiagnostic>();
        var units = new ArrayList<>(roots);
        units.addAll(pool);
        for (var unit : units) {
            String digest = bundled.digests().get(unit.primaryName());
            if (digest != null && digest.equals(JavacPlatform.sha256(unit.text().getBytes(StandardCharsets.UTF_8)))) {
                continue;
            }
            for (String type : unit.topLevelNames()) {
                String owner;
                if (bundled.digests().containsKey(type)) {
                    owner = "bundled libraries (this text is not the shipped source)";
                } else if (names.contains(type)) {
                    owner = "API";
                } else if (platform.stubPackages().contains(unit.packageName())) {
                    owner = "API package " + unit.packageName();
                } else if (packages.contains(unit.packageName())) {
                    owner = "bundled-library package " + unit.packageName();
                } else {
                    owner = null;
                }
                if (owner == null) continue;
                rejections.add(JavacDiagnostic.of(DiagnosticCodes.LEDGER_TYPE_REDECLARED, null,
                        DiagnosticCodes.LEDGER_TYPE_REDECLARED.format(unit.displayName(), type, owner),
                        unit.displayName(), 1, 1, -1, -1,
                        "Move the type to a package of your own; JuLC's API packages and bundled libraries"
                                + " come only from JuLC (ADR-068 D3)"));
            }
        }
        return rejections;
    }
}
