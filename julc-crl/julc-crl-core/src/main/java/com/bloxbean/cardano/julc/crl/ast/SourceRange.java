package com.bloxbean.cardano.julc.crl.ast;

/**
 * Source location in a CRL file, used for error reporting.
 */
public record SourceRange(String fileName, int startLine, int startCol, int endLine, int endCol) {

    public static SourceRange of(String fileName, int line, int col) {
        return new SourceRange(fileName, line, col, line, col);
    }

    @Override
    public String toString() {
        if (fileName != null && !fileName.isEmpty()) {
            return fileName + ":" + startLine + ":" + startCol;
        }
        return startLine + ":" + startCol;
    }
}
