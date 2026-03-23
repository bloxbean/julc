package com.bloxbean.cardano.julc.jrl.parser;

import com.bloxbean.cardano.julc.jrl.ast.ContractNode;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;
import com.bloxbean.cardano.julc.jrl.grammar.JRLLexer;
import com.bloxbean.cardano.julc.jrl.grammar.JRLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

/**
 * Parses JRL source text into a clean AST, collecting any syntax errors.
 * <p>
 * This is the single entry point for JRL parsing — used by {@code JrlCompiler},
 * type checker, and tests.
 */
public final class JrlParser {

    private JrlParser() {}

    /**
     * Parse result containing the AST (if successful) and any diagnostics.
     */
    public record ParseResult(ContractNode contract, List<JrlDiagnostic> diagnostics) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(JrlDiagnostic::isError);
        }
    }

    /**
     * Parse JRL source text into an AST.
     *
     * @param source   the JRL source code
     * @param fileName file name for error reporting (e.g. "Vesting.jrl")
     * @return parse result with AST and any syntax errors
     */
    public static ParseResult parse(String source, String fileName) {
        var lexer = new JRLLexer(CharStreams.fromString(source));
        var errorListener = new JrlErrorListener(fileName);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        var tokens = new CommonTokenStream(lexer);
        var parser = new JRLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        var tree = parser.jrlFile();

        if (errorListener.hasErrors()) {
            return new ParseResult(null, errorListener.getDiagnostics());
        }

        var builder = new JrlAstBuilder(fileName);
        var contract = builder.visitJrlFile(tree);
        return new ParseResult(contract, errorListener.getDiagnostics());
    }
}
