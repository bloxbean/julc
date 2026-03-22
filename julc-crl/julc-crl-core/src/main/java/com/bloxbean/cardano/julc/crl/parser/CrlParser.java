package com.bloxbean.cardano.julc.crl.parser;

import com.bloxbean.cardano.julc.crl.ast.ContractNode;
import com.bloxbean.cardano.julc.crl.check.CrlDiagnostic;
import com.bloxbean.cardano.julc.crl.grammar.CRLLexer;
import com.bloxbean.cardano.julc.crl.grammar.CRLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

/**
 * Parses CRL source text into a clean AST, collecting any syntax errors.
 * <p>
 * This is the single entry point for CRL parsing — used by {@code CrlCompiler},
 * type checker, and tests.
 */
public final class CrlParser {

    private CrlParser() {}

    /**
     * Parse result containing the AST (if successful) and any diagnostics.
     */
    public record ParseResult(ContractNode contract, List<CrlDiagnostic> diagnostics) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(CrlDiagnostic::isError);
        }
    }

    /**
     * Parse CRL source text into an AST.
     *
     * @param source   the CRL source code
     * @param fileName file name for error reporting (e.g. "Vesting.crl")
     * @return parse result with AST and any syntax errors
     */
    public static ParseResult parse(String source, String fileName) {
        var lexer = new CRLLexer(CharStreams.fromString(source));
        var errorListener = new CrlErrorListener(fileName);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        var tokens = new CommonTokenStream(lexer);
        var parser = new CRLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        var tree = parser.crlFile();

        if (errorListener.hasErrors()) {
            return new ParseResult(null, errorListener.getDiagnostics());
        }

        var builder = new CrlAstBuilder(fileName);
        var contract = builder.visitCrlFile(tree);
        return new ParseResult(contract, errorListener.getDiagnostics());
    }
}
