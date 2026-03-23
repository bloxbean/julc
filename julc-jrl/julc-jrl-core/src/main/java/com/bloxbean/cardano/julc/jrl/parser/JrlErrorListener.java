package com.bloxbean.cardano.julc.jrl.parser;

import com.bloxbean.cardano.julc.jrl.ast.SourceRange;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects ANTLR parse errors as {@link JrlDiagnostic} instances.
 */
public class JrlErrorListener extends BaseErrorListener {

    private final String fileName;
    private final List<JrlDiagnostic> diagnostics = new ArrayList<>();

    public JrlErrorListener(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        var range = SourceRange.of(fileName, line, charPositionInLine + 1);
        diagnostics.add(JrlDiagnostic.error("JRL000", "Syntax error: " + msg, range));
    }

    public List<JrlDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
