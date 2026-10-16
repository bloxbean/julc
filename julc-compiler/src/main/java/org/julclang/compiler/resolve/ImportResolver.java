package org.julclang.compiler.resolve;

import org.julclang.compiler.CompilerException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.*;

/**
 * Resolves simple type names to fully-qualified class names (FQCNs)
 * using the imports of a specific compilation unit.
 * <p>
 * Resolution order, following Java's shadowing rules (JLS 6.4.1):
 * <ol>
 *   <li>Member types of the scope type and its enclosing types, innermost first (e.g., a record
 *       nested in the validator class), when the resolver is created for a scope type</li>
 *   <li>Explicit imports (e.g., {@code import com.myapp.Token;})</li>
 *   <li>Same-package types</li>
 *   <li>Wildcard imports (including implicit {@code org.julclang.ledger.*})</li>
 *   <li>Fallback: return simple name as-is (for packageless inline code)</li>
 * </ol>
 */
public class ImportResolver {

    private static final String LEDGER_PKG = "org.julclang.ledger";
    private static final String STDLIB_PKG = "org.julclang.stdlib";
    private static final String STDLIB_LIB_PKG = "org.julclang.stdlib.lib";

    private final String packageName;
    private final Map<String, String> memberTypes;        // "Datum" -> "com.myapp.Validator.Datum"
    private final Map<String, String> explicitImports;   // "Value" -> "org.julclang.ledger.Value"
    private final List<String> wildcardPackages;          // ["org.julclang.ledger"]
    private final Set<String> knownFqcns;                 // all registered type FQCNs

    /**
     * Create an ImportResolver from a compilation unit's imports.
     *
     * @param cu         the compilation unit (may be null for packageless inline code)
     * @param knownFqcns all known type FQCNs (ledger + user-defined)
     */
    public ImportResolver(CompilationUnit cu, Set<String> knownFqcns) {
        this(cu, null, knownFqcns);
    }

    /**
     * Create an ImportResolver for code inside {@code scope}: the imports of its compilation unit,
     * plus the member types in scope there (those of {@code scope} and of its enclosing types).
     */
    public ImportResolver(TypeDeclaration<?> scope, Set<String> knownFqcns) {
        this(scope.findCompilationUnit().orElse(null), scope, knownFqcns);
    }

    private ImportResolver(CompilationUnit cu, TypeDeclaration<?> scope, Set<String> knownFqcns) {
        this.knownFqcns = knownFqcns != null ? knownFqcns : Set.of();
        this.explicitImports = new LinkedHashMap<>();
        this.wildcardPackages = new ArrayList<>();
        this.memberTypes = new LinkedHashMap<>();

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

        // A type's member types are in scope in its body and in the bodies of its nested types;
        // an inner declaration shadows an outer one
        for (Node node = scope; node != null; node = node.getParentNode().orElse(null)) {
            if (!(node instanceof TypeDeclaration<?> type)) continue;
            for (var member : type.getMembers()) {
                if (member instanceof TypeDeclaration<?> memberType) {
                    memberType.getFullyQualifiedName()
                            .filter(this.knownFqcns::contains)
                            .ifPresent(fqcn -> memberTypes.putIfAbsent(memberType.getNameAsString(), fqcn));
                }
            }
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
        this((CompilationUnit) null, knownFqcns);
    }

    /**
     * Resolve a simple name to its FQCN.
     *
     * @param simpleName the simple type name (e.g., "Value", "Token")
     * @return the FQCN if resolvable, or the simple name itself as fallback
     * @throws CompilerException if the name is ambiguous (matches multiple wildcard packages)
     */
    public String resolve(String simpleName) {
        // 1. Check member types in scope
        var member = memberTypes.get(simpleName);
        if (member != null) {
            return member;
        }

        // 2. Check explicit imports (only if the import points to a known type)
        var explicit = explicitImports.get(simpleName);
        if (explicit != null && knownFqcns.contains(explicit)) {
            return explicit;
        }

        // 3. Check same-package types
        if (packageName != null) {
            var samePackageFqcn = packageName + "." + simpleName;
            if (knownFqcns.contains(samePackageFqcn)) {
                return samePackageFqcn;
            }
        }

        // 4. Check wildcard imports
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

        // 5. Fallback: return simple name (for packageless code where FQCN = simple name)
        return simpleName;
    }
}
