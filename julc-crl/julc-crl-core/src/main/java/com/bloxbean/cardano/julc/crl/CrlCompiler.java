package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.crl.ast.ContractNode;
import com.bloxbean.cardano.julc.crl.check.CrlDiagnostic;
import com.bloxbean.cardano.julc.crl.check.CrlTypeChecker;
import com.bloxbean.cardano.julc.crl.parser.CrlParser;
import com.bloxbean.cardano.julc.crl.transpile.JavaTranspiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * CRL (CardanoRL) compiler facade.
 * <p>
 * Orchestrates the full pipeline: parse -> type check -> Java transpile -> JulcCompiler -> UPLC.
 * Each stage can also be invoked independently via {@link #parse}, {@link #check},
 * or {@link #transpile}.
 */
public class CrlCompiler {

    private final StdlibRegistry stdlib;

    public CrlCompiler() {
        this(StdlibRegistry.defaultRegistry());
    }

    public CrlCompiler(StdlibRegistry stdlib) {
        this.stdlib = stdlib;
    }

    /**
     * Full CRL compilation: parse -> check -> transpile -> compile to UPLC.
     *
     * @param crlSource CRL source code
     * @param fileName  file name for error reporting (e.g. "Vesting.crl")
     * @return compile result with UPLC program and any diagnostics
     */
    public CrlCompileResult compile(String crlSource, String fileName) {
        var allDiagnostics = new ArrayList<CrlDiagnostic>();

        // 1. Parse
        var parseResult = CrlParser.parse(crlSource, fileName);
        allDiagnostics.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return new CrlCompileResult(null, null, null, allDiagnostics);
        }

        var contract = parseResult.contract();

        // 2. Type check
        var checkDiagnostics = CrlTypeChecker.check(contract);
        allDiagnostics.addAll(checkDiagnostics);
        if (checkDiagnostics.stream().anyMatch(CrlDiagnostic::isError)) {
            return new CrlCompileResult(null, null, contract, allDiagnostics);
        }

        // 3. Transpile to Java
        String javaSource = JavaTranspiler.transpile(contract);

        // 4. Compile Java -> UPLC via JulcCompiler
        try {
            var julcCompiler = new JulcCompiler(stdlib);
            CompileResult compileResult = julcCompiler.compile(javaSource);
            return new CrlCompileResult(compileResult, javaSource, contract, allDiagnostics);
        } catch (CompilerException e) {
            // Convert JulcCompiler errors to CRL diagnostics
            if (!e.diagnostics().isEmpty()) {
                for (var diag : e.diagnostics()) {
                    allDiagnostics.add(CrlDiagnostic.error("CRL100",
                            "Java compilation error: " + diag.message(), null));
                }
            } else {
                allDiagnostics.add(CrlDiagnostic.error("CRL100",
                        "Java compilation error: " + e.getMessage(), null));
            }
            return new CrlCompileResult(null, javaSource, contract, allDiagnostics);
        } catch (Exception e) {
            allDiagnostics.add(CrlDiagnostic.error("CRL100",
                    "Unexpected compilation error: " + e.getMessage(), null));
            return new CrlCompileResult(null, javaSource, contract, allDiagnostics);
        }
    }

    /**
     * Parse CRL source into an AST.
     */
    public ContractNode parse(String crlSource, String fileName) {
        var result = CrlParser.parse(crlSource, fileName);
        if (result.hasErrors()) {
            throw new CrlCompilerException("Parse failed", result.diagnostics());
        }
        return result.contract();
    }

    /**
     * Type check a CRL source.
     */
    public List<CrlDiagnostic> check(String crlSource, String fileName) {
        var parseResult = CrlParser.parse(crlSource, fileName);
        if (parseResult.hasErrors()) {
            return parseResult.diagnostics();
        }
        var checkDiags = CrlTypeChecker.check(parseResult.contract());
        var all = new ArrayList<>(parseResult.diagnostics());
        all.addAll(checkDiags);
        return all;
    }

    /**
     * Transpile CRL to Java source (without UPLC compilation).
     */
    public TranspileResult transpile(String crlSource, String fileName) {
        var allDiagnostics = new ArrayList<CrlDiagnostic>();

        var parseResult = CrlParser.parse(crlSource, fileName);
        allDiagnostics.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return new TranspileResult(null, null, allDiagnostics);
        }

        var contract = parseResult.contract();
        var checkDiags = CrlTypeChecker.check(contract);
        allDiagnostics.addAll(checkDiags);
        if (checkDiags.stream().anyMatch(CrlDiagnostic::isError)) {
            return new TranspileResult(null, contract, allDiagnostics);
        }

        String javaSource = JavaTranspiler.transpile(contract);
        return new TranspileResult(javaSource, contract, allDiagnostics);
    }

    /**
     * Exception thrown when CRL compilation fails.
     */
    public static class CrlCompilerException extends RuntimeException {
        private final List<CrlDiagnostic> diagnostics;

        public CrlCompilerException(String message, List<CrlDiagnostic> diagnostics) {
            super(message + ": " + diagnostics);
            this.diagnostics = diagnostics;
        }

        public List<CrlDiagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
