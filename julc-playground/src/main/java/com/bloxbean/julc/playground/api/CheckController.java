package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.cardano.julc.jrl.ast.ContractNode;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;
import com.bloxbean.cardano.julc.jrl.parser.JrlParser;
import com.bloxbean.julc.playground.model.*;
import io.javalin.http.Context;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * POST /api/check — Parse + Type Check (~50ms).
 * Returns diagnostics and AST metadata for the test panel.
 */
public class CheckController {

    private final JrlCompiler compiler;

    public CheckController(JrlCompiler compiler) {
        this.compiler = compiler;
    }

    public void handle(Context ctx) {
        var req = ctx.bodyAsClass(CheckRequest.class);
        if (req.source() == null || req.source().isBlank()) {
            ctx.json(errorResponse("Source is required"));
            return;
        }

        List<JrlDiagnostic> diagnostics = compiler.check(req.source(), "playground.jrl");
        boolean hasErrors = diagnostics.stream().anyMatch(JrlDiagnostic::isError);

        // Try to parse AST for metadata extraction (even if type check has warnings)
        ContractNode ast = null;
        try {
            var parseResult = JrlParser.parse(req.source(), "playground.jrl");
            if (!parseResult.hasErrors()) {
                ast = parseResult.contract();
            }
        } catch (Exception ignored) {
        }

        ctx.json(buildResponse(!hasErrors, ast, diagnostics));
    }

    private CheckResponse buildResponse(boolean valid, ContractNode ast, List<JrlDiagnostic> diagnostics) {
        var diagDtos = diagnostics.stream().map(DiagnosticDto::from).toList();

        if (ast == null) {
            return new CheckResponse(valid, null, null, List.of(), null, List.of(), List.of(), List.of(), diagDtos);
        }

        String contractName = ast.name();
        String purpose = ast.purpose() != null ? ast.purpose().name() : null;

        List<FieldDto> params = ast.params().stream()
                .map(p -> new FieldDto(p.name(), FieldDto.typeRefToString(p.type())))
                .toList();

        String datumName = ast.datum() != null ? ast.datum().name() : null;
        List<FieldDto> datumFields = ast.datum() != null
                ? ast.datum().fields().stream().map(FieldDto::from).toList()
                : List.of();

        List<VariantDto> redeemerVariants = List.of();
        List<FieldDto> redeemerFields = List.of();
        if (ast.redeemer() != null) {
            if (ast.redeemer().isVariantStyle()) {
                redeemerVariants = IntStream.range(0, ast.redeemer().variants().size())
                        .mapToObj(i -> VariantDto.from(ast.redeemer().variants().get(i), i))
                        .toList();
            } else {
                redeemerFields = ast.redeemer().fields().stream().map(FieldDto::from).toList();
            }
        }

        return new CheckResponse(valid, contractName, purpose, params, datumName, datumFields,
                redeemerVariants, redeemerFields, diagDtos);
    }

    private CheckResponse errorResponse(String message) {
        return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(),
                List.of(new DiagnosticDto("ERROR", "JRL000", message, null, null, null, null, null)));
    }
}
