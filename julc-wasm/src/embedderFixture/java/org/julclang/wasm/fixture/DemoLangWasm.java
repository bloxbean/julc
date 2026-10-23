package org.julclang.wasm.fixture;

import org.julclang.tools.service.ServiceResult;
import org.julclang.wasm.ApiRegistry;
import org.julclang.wasm.EngineInfo;
import org.julclang.wasm.JsApi;
import org.julclang.wasm.VmApi;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * A language built on julc-wasm the way the README describes (ADR-064): its own operations beside JuLC's VM
 * operations, under its own global, variant and version. {@code wasmSmokeEmbedder} builds it into a Web Image from
 * the jars an embedder gets and runs it in Node.js; {@code EmbeddingTest} runs it on the JVM.
 */
public final class DemoLangWasm {
    public static final String VERSION = "1.0.0-demo";
    public static final List<String> GROUPS = List.of("vm", "debug", "demo");

    /** Request and result records; the image registers them for reflection in its own reachability metadata. */
    public record Term(String name, BigInteger value) {}
    public record SumRequest(List<Term> terms, long scale) {}
    public record SumResult(BigInteger total, long count, List<String> names) {}

    private DemoLangWasm() {}

    public static ApiRegistry create() {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("demo.sum", SumRequest.class, DemoLangWasm::sum);
        return api;
    }

    static ServiceResult<?> sum(SumRequest request) {
        if (request.terms() == null) return ServiceResult.status(400, Map.of("error", "terms are required"));
        var total = request.terms().stream().map(Term::value).reduce(BigInteger.ZERO, BigInteger::add)
                .multiply(BigInteger.valueOf(request.scale()));
        return ServiceResult.ok(new SumResult(total, request.terms().size(),
                request.terms().stream().map(Term::name).toList()));
    }

    public static void main(String[] args) {
        JsApi.install(create(), new EngineInfo("demolang", "demo", VERSION, GROUPS));
    }
}
