package com.bloxbean.cardano.julc.crl.parser;

import com.bloxbean.cardano.julc.crl.ast.SourceRange;
import com.bloxbean.cardano.julc.crl.check.CrlDiagnostic;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects ANTLR parse errors as {@link CrlDiagnostic} instances.
 */
public class CrlErrorListener extends BaseErrorListener {

    private final String fileName;
    private final List<CrlDiagnostic> diagnostics = new ArrayList<>();

    public CrlErrorListener(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        var range = SourceRange.of(fileName, line, charPositionInLine + 1);
        diagnostics.add(CrlDiagnostic.error("CRL000", "Syntax error: " + msg, range));
    }

    public List<CrlDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
