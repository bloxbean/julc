package org.julclang.compiler.pir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.compiler.resolve.TypeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IfJoinLoweringTest {
    private static final PirType INTEGER = new PirType.IntegerType();

    private static SymbolTable symbols() {
        var symbols = new SymbolTable();
        for (String name : List.of("a", "b", "local", "item", "p", "helper")) symbols.define(name, INTEGER);
        symbols.define("condition", new PirType.BoolType());
        symbols.define("xs", new PirType.ListType(INTEGER));
        return symbols;
    }

    private static BlockStmt block(String source) {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parseBlock(source).getResult().orElseThrow();
    }

    @Test
    void parametersAreExactlyTheEnclosingBindingsUpdatedByLoopsInEitherBranch() {
        var symbols = symbols();
        var body = block("""
                {
                    if (condition) {
                        long local = 0;
                        for (var item : xs) { local = local + item; a = a + item; }
                    } else { while (b < 3) { b = b + 1; } }
                    return a + b;
                }
                """);
        var branch = body.getStatement(0).asIfStmt();
        assertEquals(List.of("a", "b"), AccumulatorTypeAnalyzer.detectBranchAccumulators(branch, symbols::lookup));
        var term = new PirGenerator(new TypeResolver(), symbols).generateBlock(body);
        var join = assertInstanceOf(PirTerm.Let.class, term);
        assertTrue(join.name().startsWith("#if-join-"));
        var first = assertInstanceOf(PirTerm.Lam.class, join.value());
        var second = assertInstanceOf(PirTerm.Lam.class, first.body());
        assertEquals("a", first.param());
        assertEquals("b", second.param());
        assertEquals(INTEGER, first.paramType());
        assertEquals(INTEGER, second.paramType());
        assertFalse(second.body() instanceof PirTerm.Lam);
    }

    @Test
    void lexicalOwnershipExcludesLocalsPatternsAndExpressionOwnedLoops() {
        var branch = block("""
                {
                    if (condition) {
                        long local = 0;
                        for (var item : xs) {
                            long helper = 0;
                            while (helper < 2) { helper = helper + 1; a = a + 1; }
                            local = local + item;
                        }
                        if (action instanceof Only p) { for (var item : xs) { p = p; b = b + 1; } }
                        long ignored = switch (action) {
                            case Only p -> { while (local < 3) { local = local + 1; } yield local; }
                        };
                    } else { while (local < 2) { local = local + 1; } }
                }
                """).getStatement(0).asIfStmt();
        assertEquals(List.of("a", "b", "local"),
                AccumulatorTypeAnalyzer.detectBranchAccumulators(branch, symbols()::lookup));
    }

    @Test
    void noAccumulatorJoinUsesAUnitThunkSoTheTailIsNotEvaluatedEarly() {
        var term = new PirGenerator(new TypeResolver(), symbols()).generateBlock(block("""
                { if (condition) { for (var item : xs) {} } return a; }
                """));
        var join = assertInstanceOf(PirTerm.Let.class, term);
        var thunk = assertInstanceOf(PirTerm.Lam.class, join.value());
        assertInstanceOf(PirType.UnitType.class, thunk.paramType());
        assertEquals(new PirTerm.Var("a", INTEGER), thunk.body());
    }
}
