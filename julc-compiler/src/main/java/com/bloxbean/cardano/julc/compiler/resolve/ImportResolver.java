package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.util.*;

/**
 * Resolves simple type names to fully-qualified class names (FQCNs)
 * using the imports of a specific compilation unit.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Explicit imports (e.g., {@code import com.myapp.Token;})</li>
 *   <li>Same-package types</li>
 *   <li>Wildcard imports (including implicit {@code com.bloxbean.cardano.julc.ledger.*})</li>
 *   <li>Fallback: return simple name as-is (for packageless inline code)</li>
 * </ol>
 */
public class ImportResolver {

    private static final String LEDGER_PKG = "com.bloxbean.cardano.julc.ledger";
    private static final String STDLIB_PKG = "com.bloxbean.cardano.julc.stdlib";
    private static final String STDLIB_LIB_PKG = "com.bloxbean.cardano.julc.stdlib.lib";

    private final String packageName;
    private final Map<String, String> explicitImports;   // "Value" -> "com.bloxbean.cardano.julc.ledger.Value"
    private final List<String> wildcardPackages;          // ["com.bloxbean.cardano.julc.ledger"]
    private final Set<String> knownFqcns;                 // all registered type FQCNs

    /**
     * Create an ImportResolver from a compilation unit's imports.
     *
     * @param cu         the compilation unit (may be null for packageless inline code)
     * @param knownFqcns all known type FQCNs (ledger + user-defined)
     */
    public ImportResolver(CompilationUnit cu, Set<String> knownFqcns) {
        this.knownFqcns = knownFqcns != null ? knownFqcns : Set.of();
        this.explicitImports = new LinkedHashMap<>();
        this.wildcardPackages = new ArrayList<>();

        if (cu != null) {
            this.packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse(null);

            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.isStatic()) continue; // skip static imports
                if (imp.isAsterisk()) {
                    wildcardPackages.add(imp.getNameAsString());
                } else {
                    var fqcn = imp.getNameAsString();
                    var simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                    explicitImports.put(simpleName, fqcn);
                }
            }
        } else {
            this.packageName = null;
        }

        // Always add implicit wildcards (like Java's java.lang.*)
        for (var pkg : List.of(LEDGER_PKG, STDLIB_PKG, STDLIB_LIB_PKG)) {
            if (!wildcardPackages.contains(pkg)) {
                wildcardPackages.add(pkg);
            }
        }
    }

    /**
     * No-CU constructor for packageless inline code.
     */
    public ImportResolver(Set<String> knownFqcns) {
        this(null, knownFqcns);
    }

    /**
     * Resolve a simple name to its FQCN.
     *
     * @param simpleName the simple type name (e.g., "Value", "Token")
     * @return the FQCN if resolvable, or the simple name itself as fallback
     * @throws CompilerException if the name is ambiguous (matches multiple wildcard packages)
     */
    public String resolve(String simpleName) {
        // 1. Check explicit imports (only if the import points to a known type)
        var explicit = explicitImports.get(simpleName);
        if (explicit != null && knownFqcns.contains(explicit)) {
            return explicit;
        }

        // 2. Check same-package types
        if (packageName != null) {
            var samePackageFqcn = packageName + "." + simpleName;
            if (knownFqcns.contains(samePackageFqcn)) {
                return samePackageFqcn;
            }
        }

        // 3. Check wildcard imports
        var wildcardMatches = new ArrayList<String>();
        for (var pkg : wildcardPackages) {
            var candidate = pkg + "." + simpleName;
            if (knownFqcns.contains(candidate)) {
                wildcardMatches.add(candidate);
            }
        }
        if (wildcardMatches.size() == 1) {
            return wildcardMatches.get(0);
        }
        if (wildcardMatches.size() > 1) {
            throw new CompilerException("Ambiguous type '" + simpleName
                    + "'. Could be: " + String.join(", ", wildcardMatches)
                    + ". Use an explicit import to disambiguate.");
        }

        // 4. Fallback: return simple name (for packageless code where FQCN = simple name)
        return simpleName;
    }
}
