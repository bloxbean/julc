package org.julclang.tools.debug;

import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceMap;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.api.InputValidator;
import org.julclang.tools.model.DiagnosticDto;
import org.julclang.tools.model.FieldDto;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.ActionResponse;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.tools.model.SourceDebugModels.OpenResponse;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.service.ServiceResult;
import org.julclang.tools.uplc.DataInputs;
import org.julclang.tools.uplc.EvaluationPreparation;
import org.julclang.tools.uplc.UplcToolsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles a separate, unoptimized Java source artifact and binds its source map to one debugger session.
 * Normal compiler instances and normal UPLC debugger sessions are never mutated.
 */
public final class SourceDebugService {
    public static final String WARNING = "Experimental source-debug build — the UPLC optimizer is disabled. "
            + "Script bytes, hash and execution budget may differ from normal compilation. "
            + "Do not use this build's budget as an estimate for your normally compiled contract.";

    private static final int MAX_SESSIONS = 16;

    private record Session(UplcToolsService tools) {}

    private final Map<String, LibrarySource> cachedLibSources;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private long nextSession;

    public SourceDebugService(Map<String, LibrarySource> cachedLibSources) {
        this.cachedLibSources = Map.copyOf(cachedLibSources);
    }

    /** Compile, bind source metadata to the exact decoded term tree, and open a source-debug session. */
    public synchronized ServiceResult<OpenResponse> open(OpenRequest request) {
        String validation = validate(request);
        if (validation != null) return ServiceResult.status(400, error(validation, List.of()));

        try {
            var options = new CompilerOptions().setSourceMapEnabled(true);
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
            var libraries = new ArrayList<>(LibrarySourceResolver.resolve(request.source(), cachedLibSources));
            if (request.librarySource() != null && !request.librarySource().isBlank()) {
                libraries.add(request.librarySource());
            }
            CompileResult compiled = compiler.compileWithDetails(request.source(), libraries);
            var diagnostics = compiled.diagnostics().stream().map(DiagnosticDto::from).toList();
            if (compiled.hasErrors() || compiled.program() == null) {
                return ServiceResult.ok(error("Compilation failed", diagnostics));
            }
            if (!compiled.hasSourceMap()) {
                return ServiceResult.failed(500, error("Source-debug compilation produced no source map", diagnostics),
                        new IllegalStateException("Source map is empty"));
            }

            var declaredParams = compiled.params().stream().map(p -> new FieldDto(p.name(), p.type())).toList();
            int suppliedParams = request.params() == null ? 0 : request.params().size();
            if (suppliedParams != declaredParams.size()) {
                return ServiceResult.ok(new OpenResponse(false,
                        "Source-debug build requires " + declaredParams.size() + " parameter value"
                                + (declaredParams.size() == 1 ? "" : "s") + "; received " + suppliedParams,
                        null, request.source(), compiled.uplcFormatted(),
                        BlueprintGenerator.compiledCode(compiled.program()),
                        BlueprintGenerator.scriptHash(compiled.program()), compiled.scriptSizeBytes(),
                        declaredParams, diagnostics,
                        compiled.sourceMap().toIndexed(compiled.program().term()).values().stream()
                                .map(org.julclang.core.source.SourceLocation::line).filter(line -> line > 0)
                                .distinct().sorted().toList(),
                        null, null, null, null, WARNING));
            }

            Program debugProgram = applyParameters(compiled, request);
            Map<Integer, org.julclang.core.source.SourceLocation> indexed =
                    compiled.sourceMap().toIndexed(debugProgram.term());
            String compiledCode = BlueprintGenerator.compiledCode(debugProgram);
            var script = new ScriptInput(compiledCode, List.of(), "V3", null);

            var target = request.resolvedTarget();
            if (target.language() != null && !isV3(target.language())) {
                throw new IllegalArgumentException("Java source-debug compilation only supports Plutus V3");
            }
            var tools = new UplcToolsService();
            var prepared = tools.prepareTransaction(script, request.transaction(), target, request.costModel(),
                    request.maxCpu(), request.maxMem());
            SourceMap sourceMap = SourceMap.reconstruct(indexed, prepared.decoded().program().term());
            if (sourceMap.size() != indexed.size()) {
                throw new IllegalStateException("Source map does not match the source-debug artifact");
            }

            var opened = tools.open(prepared, true, sourceMap);
            if (!opened.body().ok()) {
                return ServiceResult.status(opened.status(), error(opened.body().error(), diagnostics));
            }
            String sessionId = Long.toUnsignedString(++nextSession);
            sessions.put(sessionId, new Session(tools));
            evictOldest();

            var decoded = prepared.decoded();
            return ServiceResult.ok(new OpenResponse(true, null, sessionId, request.source(),
                    tools.uplcText(),
                    compiledCode, BlueprintGenerator.scriptHash(debugProgram),
                    UplcFlatEncoder.encodeProgram(debugProgram).length, declaredParams, diagnostics,
                    tools.executableJavaLines(), opened.body().timeline(), opened.body().snapshot(),
                    EvaluationPreparation.targetOf(prepared), prepared.costModelId(), WARNING));
        } catch (CompilerException e) {
            var diagnostics = e.diagnostics().stream().map(DiagnosticDto::from).toList();
            return ServiceResult.ok(error("Compilation failed", diagnostics));
        } catch (IllegalArgumentException e) {
            return ServiceResult.status(400, error(e.getMessage(), List.of()));
        } catch (Exception e) {
            return ServiceResult.failed(500, error("Source debugging failed", List.of()), e);
        }
    }

    public synchronized ServiceResult<ActionResponse> act(ActionRequest request) {
        if (request == null || request.sessionId() == null) return missing();
        Session session = sessions.get(request.sessionId());
        if (session == null) return missing();
        var result = session.tools().act(request.action(), request.step(), request.breakpoints());
        var body = result.body();
        return ServiceResult.status(result.status(), new ActionResponse(body.ok(), body.error(),
                body.timeline(), body.snapshot()));
    }

    public synchronized ServiceResult<Map<String, Boolean>> close(CloseRequest request) {
        if (request == null || request.sessionId() == null) return closeMissing();
        Session session = sessions.remove(request.sessionId());
        if (session == null) return closeMissing();
        session.tools().close();
        return ServiceResult.ok(Map.of("closed", true));
    }

    private static Program applyParameters(CompileResult compiled, OpenRequest request) {
        var inputs = request.params() == null ? List.<org.julclang.tools.model.MockTransaction.DataInput>of()
                : request.params();
        if (inputs.isEmpty()) return compiled.program();
        var values = new PlutusData[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            try {
                values[i] = DataInputs.parse(inputs.get(i));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Parameter " + (i + 1) + ": " + e.getMessage(), e);
            }
            if (values[i] == null) throw new IllegalArgumentException("Parameter " + (i + 1) + " is empty");
        }
        return compiled.program().applyParams(values);
    }

    private static String validate(OpenRequest request) {
        if (request == null) return "Request is required";
        String error = InputValidator.validateSource(request.source());
        if (error == null) error = InputValidator.validateLibrary(request.librarySource());
        return error;
    }

    private static boolean isV3(String language) {
        String normalized = language.replace("_", "").toUpperCase(Locale.ROOT);
        return normalized.equals("V3") || normalized.equals("PLUTUSV3");
    }

    private void evictOldest() {
        while (sessions.size() > MAX_SESSIONS) {
            var iterator = sessions.entrySet().iterator();
            var oldest = iterator.next();
            oldest.getValue().tools().close();
            iterator.remove();
        }
    }

    private static OpenResponse error(String message, List<DiagnosticDto> diagnostics) {
        return new OpenResponse(false, message, null, null, null, null, null, 0,
                List.of(), diagnostics, List.of(), null, null, null, null, WARNING);
    }

    private static ServiceResult<ActionResponse> missing() {
        return ServiceResult.status(404, new ActionResponse(false,
                "Source-debug session is closed or belongs to a previous worker", null, null));
    }

    private static ServiceResult<Map<String, Boolean>> closeMissing() {
        return ServiceResult.status(404, Map.of("closed", false));
    }
}
