package org.julclang.tools.service;

import org.julclang.blueprint.BlueprintConfig;
import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.tools.api.InputValidator;
import org.julclang.tools.java.JavaMetadataExtractor;
import org.julclang.tools.model.CheckRequest;
import org.julclang.tools.model.CheckResponse;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.CompileResponse;
import org.julclang.tools.model.DiagnosticDto;
import org.julclang.tools.model.EvalExpressionRequest;
import org.julclang.tools.model.EvalExpressionResponse;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.tools.model.EvaluateResponse;
import org.julclang.tools.model.FieldDto;
import org.julclang.tools.repl.ExpressionEvaluator;
import org.julclang.tools.scenario.ScenarioContextBuilder;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.LedgerEvaluationTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Transport-neutral implementation of the playground API.
 * <p>
 * Each operation returns the response record together with the HTTP status the REST server uses for it, so the
 * Javalin controllers and the in-browser (WebAssembly) engine expose exactly the same JSON contract. Concurrency
 * limits and timeouts are the transport's responsibility.
 */
public final class ToolsService {

    private record PlaygroundCompilation(CompileResult result, ContractSchema contractSchema) {}

    private final JulcCompiler julcCompiler;
    private final Map<String, LibrarySource> cachedLibSources;
    private final Supplier<JulcVm> vmFactory;

    /**
     * @param julcCompiler     shared compiler
     * @param cachedLibSources stdlib sources scanned once from the classpath
     * @param vmFactory        creates the VM used for each evaluation
     */
    public ToolsService(JulcCompiler julcCompiler, Map<String, LibrarySource> cachedLibSources,
                             Supplier<JulcVm> vmFactory) {
        this.julcCompiler = julcCompiler;
        this.cachedLibSources = cachedLibSources;
        this.vmFactory = vmFactory;
    }

    // ---------------------------------------------------------------- POST /api/check

    /** Parse + type check. Returns diagnostics and AST metadata for the test panel. */
    public static ServiceResult<CheckResponse> check(CheckRequest req) {
        String err = InputValidator.validateSource(req.source());
        if (err != null) {
            return ServiceResult.ok(new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(),
                    List.of(), List.of(errorDiagnostic(err))));
        }
        return ServiceResult.ok(JavaMetadataExtractor.extract(req.source()));
    }

    // ---------------------------------------------------------------- POST /api/compile

    /** Input validation for {@link #compile}; {@code null} when the request is acceptable. */
    public static ServiceResult<CompileResponse> validateCompile(CompileRequest req) {
        String err = InputValidator.validateSource(req.source());
        if (err == null) err = InputValidator.validateLibrary(req.librarySource());
        return err != null ? ServiceResult.ok(compileError(err)) : null;
    }

    /** Full pipeline to UPLC, with an optional validated CIP-57 blueprint. */
    public ServiceResult<CompileResponse> compile(CompileRequest req) {
        var invalid = validateCompile(req);
        if (invalid != null) return invalid;

        try {
            var resolvedLibs = resolveLibraries(req.source(), req.librarySource());
            PlaygroundCompilation compiled;
            if (req.blueprintEnabled()) {
                var result = julcCompiler.compileContractWithDetails(req.source(), resolvedLibs);
                compiled = new PlaygroundCompilation(result.compileResult(), result.contractSchema());
            } else {
                compiled = new PlaygroundCompilation(julcCompiler.compileWithDetails(req.source(), resolvedLibs), null);
            }
            var cr = compiled.result();
            var diagnostics = cr.diagnostics().stream().map(DiagnosticDto::from).toList();

            if (cr.hasErrors() || cr.program() == null) {
                return ServiceResult.ok(new CompileResponse(null, null, null, null, null, null,
                        0, null, List.of(), diagnostics));
            }

            var params = cr.params().stream()
                    .map(p -> new FieldDto(p.name(), p.type()))
                    .toList();

            String blueprintJson = null;
            if (req.blueprintEnabled()) {
                try {
                    blueprintJson = generateBlueprint("Playground", cr, compiled.contractSchema());
                } catch (IllegalArgumentException e) {
                    return ServiceResult.status(422, compileError(InputValidator.sanitizeError(e.getMessage())));
                }
            }
            String compiledCode = BlueprintGenerator.compiledCode(cr.program());
            String scriptHash = BlueprintGenerator.scriptHash(cr.program());

            return ServiceResult.ok(new CompileResponse(
                    cr.uplcFormatted(),
                    null,
                    cr.pirPretty(),
                    blueprintJson,
                    compiledCode,
                    scriptHash,
                    cr.scriptSizeBytes(),
                    cr.scriptSizeFormatted(),
                    params,
                    diagnostics
            ));
        } catch (CompilerException e) {
            var diagnostics = e.diagnostics().stream().map(DiagnosticDto::from).toList();
            return ServiceResult.ok(new CompileResponse(null, null, null, null, null, null,
                    0, null, List.of(), diagnostics));
        } catch (Exception e) {
            return ServiceResult.failed(500, compileError(InputValidator.sanitizeError("Compilation failed")), e);
        }
    }

    // ---------------------------------------------------------------- POST /api/evaluate

    /** Input validation for {@link #evaluate}; {@code null} when the request is acceptable. */
    public static ServiceResult<EvaluateResponse> validateEvaluate(EvaluateRequest req) {
        String err = InputValidator.validateSource(req.source());
        if (err == null) err = InputValidator.validateLibrary(req.librarySource());
        return err != null ? ServiceResult.ok(evaluateError(err)) : null;
    }

    /** Compile + apply params + build ScriptContext + VM evaluate. */
    public ServiceResult<EvaluateResponse> evaluate(EvaluateRequest req) {
        var invalid = validateEvaluate(req);
        if (invalid != null) return invalid;

        try {
            // 1. Extract metadata for context building
            var metadata = JavaMetadataExtractor.extract(req.source());
            if (!metadata.valid()) {
                return ServiceResult.ok(new EvaluateResponse(false, 0, 0, List.of(), "Check failed",
                        metadata.diagnostics()));
            }

            // 2. Compile Java -> UPLC
            var cr = julcCompiler.compile(req.source(), resolveLibraries(req.source(), req.librarySource()));
            if (cr.hasErrors() || cr.program() == null) {
                var diagnostics = cr.diagnostics().stream().map(DiagnosticDto::from).toList();
                return ServiceResult.ok(new EvaluateResponse(false, 0, 0, List.of(), "Compilation failed",
                        diagnostics));
            }

            var program = cr.program();

            // 3. Apply params if present
            if (req.paramValues() != null && !req.paramValues().isEmpty() && !cr.params().isEmpty()) {
                var paramData = new ArrayList<PlutusData>();
                for (var param : cr.params()) {
                    String value = req.paramValues().get(param.name());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException("Missing param value: " + param.name());
                    }
                    paramData.add(ScenarioContextBuilder.convertValue(value, param.type()));
                }
                program = program.applyParams(paramData.toArray(PlutusData[]::new));
            }

            // 4. Build ScriptContext
            PlutusData datum = ScenarioContextBuilder.buildDatumFromFields(
                    metadata.datumFields(),
                    req.datum() != null ? req.datum() : Map.of());
            PlutusData redeemer = ScenarioContextBuilder.buildRedeemerFromMetadata(
                    metadata.redeemerVariants(), metadata.redeemerFields(), req.redeemer());

            PlutusData scriptContext = ScenarioContextBuilder.buildContext(
                    metadata.purpose() != null ? metadata.purpose() : "SPENDING",
                    req.scenario(), datum, redeemer);

            // 5. Evaluate
            return ServiceResult.ok(evaluateProgram(program, cr.target().ledgerTarget(), scriptContext));
        } catch (CompilerException e) {
            var diagnostics = e.diagnostics().stream().map(DiagnosticDto::from).toList();
            return ServiceResult.ok(new EvaluateResponse(false, 0, 0, List.of(), "Compilation failed", diagnostics));
        } catch (IllegalArgumentException e) {
            return ServiceResult.status(400, evaluateError(e.getMessage()));
        } catch (Exception e) {
            return ServiceResult.failed(500, evaluateError(InputValidator.sanitizeError("Evaluation failed")), e);
        }
    }

    private EvaluateResponse evaluateProgram(Program program, LedgerEvaluationTarget target, PlutusData scriptContext) {
        var result = vmFactory.get().evaluateWithArgs(
                program, target, List.of(scriptContext), null, EvalOptions.DEFAULT);
        return switch (result) {
            case EvalResult.Success s -> new EvaluateResponse(
                    true, s.consumed().cpuSteps(), s.consumed().memoryUnits(), s.traces(), null, List.of());
            case EvalResult.Failure f -> new EvaluateResponse(
                    false, f.consumed().cpuSteps(), f.consumed().memoryUnits(), f.traces(), f.error(), List.of());
            case EvalResult.BudgetExhausted b -> new EvaluateResponse(
                    false, b.consumed().cpuSteps(), b.consumed().memoryUnits(), b.traces(), "Budget exhausted",
                    List.of());
        };
    }

    // ---------------------------------------------------------------- POST /api/eval

    /** Input validation for {@link #evalExpression}; {@code null} when the request is acceptable. */
    public static ServiceResult<EvalExpressionResponse> validateEvalExpression(EvalExpressionRequest req) {
        String err = InputValidator.validateExpression(req.expression());
        return err != null
                ? ServiceResult.ok(new EvalExpressionResponse(false, null, null, 0, 0, List.of(), err, null))
                : null;
    }

    /** Evaluate a standalone expression with the given evaluator. */
    public static ServiceResult<EvalExpressionResponse> evalExpression(ExpressionEvaluator evaluator,
                                                                       EvalExpressionRequest req) {
        var invalid = validateEvalExpression(req);
        if (invalid != null) return invalid;
        try {
            var result = evaluator.evaluate(req.expression());
            return ServiceResult.ok(new EvalExpressionResponse(
                    result.success(), result.result(), result.type(),
                    result.budgetCpu(), result.budgetMem(),
                    result.traces(), result.error(), result.uplc()
            ));
        } catch (Exception e) {
            return ServiceResult.ok(new EvalExpressionResponse(false, null, null, 0, 0, List.of(),
                    InputValidator.sanitizeError("Evaluation failed"), null));
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> resolveLibraries(String source, String librarySource) {
        var resolvedLibs = new ArrayList<>(LibrarySourceResolver.resolve(source, cachedLibSources));
        if (librarySource != null && !librarySource.isBlank()) {
            resolvedLibs.add(librarySource);
        }
        return resolvedLibs;
    }

    /** Generate a validated CIP-57 blueprint or fail the request. */
    private static String generateBlueprint(String name, CompileResult cr, ContractSchema contractSchema) {
        var config = new BlueprintConfig(name, "1.0.0");
        var compiled = new BlueprintGenerator.CompiledValidator(name, cr, contractSchema);
        var blueprint = BlueprintGenerator.generate(config, List.of(compiled));
        return blueprint.toJson();
    }

    private static DiagnosticDto errorDiagnostic(String message) {
        return new DiagnosticDto("ERROR", "JULC000", message, null, null, null, null, null);
    }

    private static CompileResponse compileError(String message) {
        return new CompileResponse(null, null, null, null, null, null, 0, null, List.of(),
                List.of(errorDiagnostic(message)));
    }

    private static EvaluateResponse evaluateError(String message) {
        return new EvaluateResponse(false, 0, 0, List.of(), message, List.of(errorDiagnostic(message)));
    }
}
