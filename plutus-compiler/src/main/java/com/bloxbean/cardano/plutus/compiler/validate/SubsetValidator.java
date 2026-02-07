package com.bloxbean.cardano.plutus.compiler.validate;

import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that Java source uses only the supported subset for on-chain compilation.
 * Rejects unsupported constructs with descriptive error messages and suggestions.
 */
public class SubsetValidator extends VoidVisitorAdapter<Void> {

    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private String fileName = "<unknown>";

    public List<CompilerDiagnostic> validate(CompilationUnit cu) {
        diagnostics.clear();
        cu.getStorage().ifPresent(s -> fileName = s.getFileName());
        cu.accept(this, null);
        return List.copyOf(diagnostics);
    }

    private void error(com.github.javaparser.ast.Node node, String message) {
        error(node, message, null);
    }

    private void error(com.github.javaparser.ast.Node node, String message, String suggestion) {
        int line = node.getBegin().map(p -> p.line).orElse(0);
        int col = node.getBegin().map(p -> p.column).orElse(0);
        diagnostics.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR, message, fileName, line, col, suggestion));
    }

    private void warning(com.github.javaparser.ast.Node node, String message) {
        int line = node.getBegin().map(p -> p.line).orElse(0);
        int col = node.getBegin().map(p -> p.column).orElse(0);
        diagnostics.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.WARNING, message, fileName, line, col));
    }

    // --- Rejected statements ---

    @Override
    public void visit(TryStmt n, Void arg) {
        error(n, "try/catch is not supported on-chain",
                "Use if/else checks instead of exception handling");
        super.visit(n, arg);
    }

    @Override
    public void visit(ThrowStmt n, Void arg) {
        error(n, "throw is not supported on-chain",
                "Return false from the validator to reject a transaction");
        super.visit(n, arg);
    }

    @Override
    public void visit(SynchronizedStmt n, Void arg) {
        error(n, "synchronized is not supported on-chain",
                "On-chain code is single-threaded; remove synchronized blocks");
        super.visit(n, arg);
    }

    @Override
    public void visit(ForStmt n, Void arg) {
        error(n, "C-style for loops are not supported on-chain",
                "Use for-each over a list or while loops instead");
        super.visit(n, arg);
    }

    @Override
    public void visit(ForEachStmt n, Void arg) {
        // for-each is now supported (desugared to fold)
        super.visit(n, arg);
    }

    @Override
    public void visit(WhileStmt n, Void arg) {
        // while is now supported (desugared to recursion)
        super.visit(n, arg);
    }

    @Override
    public void visit(DoStmt n, Void arg) {
        error(n, "do-while loops are not supported on-chain",
                "Use while loops or for-each instead");
        super.visit(n, arg);
    }

    // --- Rejected expressions ---

    @Override
    public void visit(NullLiteralExpr n, Void arg) {
        error(n, "null is not supported on-chain",
                "Use Optional<T> to represent absence of a value");
        super.visit(n, arg);
    }

    @Override
    public void visit(ObjectCreationExpr n, Void arg) {
        // Allow record-like construction; reject non-record 'new'
        // For MVP, we allow all 'new' and rely on TypeResolver to reject non-records later
        super.visit(n, arg);
    }

    @Override
    public void visit(ThisExpr n, Void arg) {
        error(n, "'this' is not supported on-chain",
                "On-chain validators are stateless; use static methods instead");
        super.visit(n, arg);
    }

    @Override
    public void visit(SuperExpr n, Void arg) {
        error(n, "'super' is not supported on-chain",
                "Use sealed interfaces and pattern matching instead of inheritance");
        super.visit(n, arg);
    }

    @Override
    public void visit(ArrayCreationExpr n, Void arg) {
        error(n, "arrays are not supported on-chain",
                "Use List<T> instead of arrays");
        super.visit(n, arg);
    }

    @Override
    public void visit(ArrayAccessExpr n, Void arg) {
        error(n, "array access is not supported on-chain",
                "Use List<T> with list operations instead");
        super.visit(n, arg);
    }

    // --- Rejected types ---

    @Override
    public void visit(PrimitiveType n, Void arg) {
        var type = n.getType();
        if (type == PrimitiveType.Primitive.FLOAT || type == PrimitiveType.Primitive.DOUBLE) {
            error(n, "floating point types (float/double) are not supported on-chain",
                    "Use BigInteger for integer arithmetic or Rational for fractions");
        }
        super.visit(n, arg);
    }

    // --- Rejected class features ---

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        // Reject inheritance (extends non-Object class)
        if (!n.getExtendedTypes().isEmpty()) {
            for (var ext : n.getExtendedTypes()) {
                if (!ext.getNameAsString().equals("Object")) {
                    error(n, "class inheritance is not supported on-chain",
                            "Use sealed interfaces with record variants instead");
                }
            }
        }
        super.visit(n, arg);
    }
}
