package org.julclang.cli.repl;

import org.julclang.compiler.frontend.javac.ReplExpressionWrapper;
import org.julclang.stdlib.StdlibRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps bare expressions into a compilable Java class for REPL evaluation, through the typed wrapper JuLC's REPLs
 * share (ADR-068 D9, {@link ReplExpressionWrapper}): javac types the expression, and an {@code int} or {@code long}
 * result is returned as a {@code BigInteger}. The wrapper imports every stdlib library, the ledger and core types,
 * so users can type expressions directly.
 */
public final class ExpressionWrapper {

    private ExpressionWrapper() {}

    static final String CLASS_NAME = ReplExpressionWrapper.CLASS_NAME;
    static final String METHOD_NAME = ReplExpressionWrapper.METHOD_NAME;

    /**
     * The wrapper's imports: every stdlib class, the core types, the ledger (on demand), {@code BigInteger},
     * {@code List} and {@code Optional}, then the user's.
     */
    static List<String> imports(List<String> userImports) {
        var imports = new ArrayList<>(StdlibRegistry.stdlibClassFqcns());
        imports.add("org.julclang.core.PlutusData");
        imports.add("org.julclang.core.types.JulcList");
        imports.add("org.julclang.core.types.JulcMap");
        imports.add("org.julclang.core.types.Tuple2");
        imports.add("org.julclang.core.types.Tuple3");
        imports.add("org.julclang.ledger.*");
        imports.add("java.math.BigInteger");
        imports.add("java.util.List");
        imports.add("java.util.Optional");
        imports.addAll(userImports);
        return imports;
    }

    /**
     * Wrap an expression (or a block of statements ending in a {@code return}) as a typed method.
     *
     * @param expression  the user's expression
     * @param userImports additional user-added imports (from :import)
     * @param pool        library sources the expression may call
     * @return the wrapped source and its return type, or javac's diagnostics
     */
    public static ReplExpressionWrapper.Wrapped wrap(String expression, List<String> userImports, List<String> pool) {
        return ReplExpressionWrapper.wrap(expression, imports(userImports), pool);
    }

    /** The text the library resolver scans for the classes an expression uses: the imports and the expression. */
    static String resolutionText(String expression, List<String> userImports) {
        var sb = new StringBuilder();
        for (String imp : imports(userImports)) sb.append("import ").append(imp).append(";\n");
        return sb.append("class ").append(CLASS_NAME).append(" { Object x = ").append(expression).append("; }\n")
                .toString();
    }
}
