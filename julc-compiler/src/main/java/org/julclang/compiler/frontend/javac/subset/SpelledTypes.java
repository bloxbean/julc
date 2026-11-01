package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;

import java.util.Set;

/**
 * A subset rule ({@code JULC0100}): a program does not declare a type or a type parameter under a name the JavaParser
 * frontend's type resolution reads by its spelling, ahead of any type a program declares. To that frontend such a name is
 * its own type, whatever it was declared as. {@link #NAMES} is the cases of {@code resolveClassType}, then the ledger's hash
 * types, which it checks by simple name before it looks at a user type. {@code SubsetRulesTest} holds the two together.
 */
public final class SpelledTypes {

    public static final Set<String> NAMES = Set.of(
            "BigInteger", "String", "Boolean", "PlutusData", "ConstrData", "MapData", "ListData", "IntData", "BytesData",
            "JulcValue", "JulcG1", "JulcG2", "JulcMlResult", "JulcScalars", "JulcG1Points", "JulcG2Points",
            "List", "JulcList", "JulcArray", "Map", "JulcMap", "Tuple2", "Tuple3", "AssetEntry", "Optional",
            "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId");

    private SpelledTypes() {
    }

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (NAMES.contains(tree.getSimpleName().toString())) {
                    throw CompilerTypeDiagnostics.typeNameReadBySpelling(tree.getSimpleName().toString(), facts.location(tree));
                }
                return super.visitClass(tree, unused);
            }

            @Override
            public Void visitTypeParameter(TypeParameterTree tree, Void unused) {
                if (NAMES.contains(tree.getName().toString())) {
                    throw CompilerTypeDiagnostics.typeNameReadBySpelling(tree.getName().toString(), facts.location(tree));
                }
                return super.visitTypeParameter(tree, unused);
            }
        }.scan(unit, null);
    }
}
