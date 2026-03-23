package com.bloxbean.cardano.julc.jrl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.jrl.ast.ContractNode;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;
import com.bloxbean.cardano.julc.jrl.check.JrlTypeChecker;
import com.bloxbean.cardano.julc.jrl.parser.JrlParser;
import com.bloxbean.cardano.julc.jrl.transpile.JavaTranspiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * JRL (JuLC Rule Language) compiler facade.
 * <p>
 * Orchestrates the full pipeline: parse -> type check -> Java transpile -> JulcCompiler -> UPLC.
 * Each stage can also be invoked independently via {@link #parse}, {@link #check},
 * or {@link #transpile}.
 */
public class JrlCompiler {

    private final StdlibRegistry stdlib;

    public JrlCompiler() {
        this(StdlibRegistry.defaultRegistry());
    }

    public JrlCompiler(StdlibRegistry stdlib) {
        this.stdlib = stdlib;
    }

    /**
     * Full JRL compilation: parse -> check -> transpile -> compile to UPLC.
     *
     * @param jrlSource JRL source code
     * @param fileName  file name for error reporting (e.g. "Vesting.jrl")
     * @return compile result with UPLC program and any diagnostics
     */
    public JrlCompileResult compile(String jrlSource, String fileName) {
        var allDiagnostics = new ArrayList<JrlDiagnostic>();

        // 1. Parse
        var parseResult = JrlParser.parse(jrlSource, fileName);
        allDiagnostics.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return new JrlCompileResult(null, null, null, allDiagnostics);
        }

        var contract = parseResult.contract();

        // 2. Type check
        var checkDiagnostics = JrlTypeChecker.check(contract);
        allDiagnostics.addAll(checkDiagnostics);
        if (checkDiagnostics.stream().anyMatch(JrlDiagnostic::isError)) {
            return new JrlCompileResult(null, null, contract, allDiagnostics);
        }

        // 3. Transpile to Java
        String javaSource = JavaTranspiler.transpile(contract);

        // 4. Compile Java -> UPLC via JulcCompiler
        try {
            var julcCompiler = new JulcCompiler(stdlib);
            CompileResult compileResult = julcCompiler.compile(javaSource);
            return new JrlCompileResult(compileResult, javaSource, contract, allDiagnostics);
        } catch (CompilerException e) {
            // Convert JulcCompiler errors to JRL diagnostics
            if (!e.diagnostics().isEmpty()) {
                for (var diag : e.diagnostics()) {
                    allDiagnostics.add(JrlDiagnostic.error("JRL100",
                            "Java compilation error: " + diag.message(), null));
                }
            } else {
                allDiagnostics.add(JrlDiagnostic.error("JRL100",
                        "Java compilation error: " + e.getMessage(), null));
            }
            return new JrlCompileResult(null, javaSource, contract, allDiagnostics);
        } catch (Exception e) {
            allDiagnostics.add(JrlDiagnostic.error("JRL100",
                    "Unexpected compilation error: " + e.getMessage(), null));
            return new JrlCompileResult(null, javaSource, contract, allDiagnostics);
        }
    }

    /**
     * Parse JRL source into an AST.
     */
    public ContractNode parse(String jrlSource, String fileName) {
        var result = JrlParser.parse(jrlSource, fileName);
        if (result.hasErrors()) {
            throw new JrlCompilerException("Parse failed", result.diagnostics());
        }
        return result.contract();
    }

    /**
     * Type check a JRL source.
     */
    public List<JrlDiagnostic> check(String jrlSource, String fileName) {
        var parseResult = JrlParser.parse(jrlSource, fileName);
        if (parseResult.hasErrors()) {
            return parseResult.diagnostics();
        }
        var checkDiags = JrlTypeChecker.check(parseResult.contract());
        var all = new ArrayList<>(parseResult.diagnostics());
        all.addAll(checkDiags);
        return all;
    }

    /**
     * Transpile JRL to Java source (without UPLC compilation).
     */
    public TranspileResult transpile(String jrlSource, String fileName) {
        var allDiagnostics = new ArrayList<JrlDiagnostic>();

        var parseResult = JrlParser.parse(jrlSource, fileName);
        allDiagnostics.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return new TranspileResult(null, null, allDiagnostics);
        }

        var contract = parseResult.contract();
        var checkDiags = JrlTypeChecker.check(contract);
        allDiagnostics.addAll(checkDiags);
        if (checkDiags.stream().anyMatch(JrlDiagnostic::isError)) {
            return new TranspileResult(null, contract, allDiagnostics);
        }

        String javaSource = JavaTranspiler.transpile(contract);
        return new TranspileResult(javaSource, contract, allDiagnostics);
    }

    /**
     * Exception thrown when JRL compilation fails.
     */
    public static class JrlCompilerException extends RuntimeException {
        private final List<JrlDiagnostic> diagnostics;

        public JrlCompilerException(String message, List<JrlDiagnostic> diagnostics) {
            super(message + ": " + diagnostics);
            this.diagnostics = diagnostics;
        }

        public List<JrlDiagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
