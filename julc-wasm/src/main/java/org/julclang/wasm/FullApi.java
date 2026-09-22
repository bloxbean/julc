package org.julclang.wasm;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.tools.model.CheckRequest;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.EvalExpressionRequest;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.UplcModels;
import org.julclang.tools.model.VmModels;
import org.julclang.tools.repl.ExpressionEvaluator;
import org.julclang.tools.service.ServiceResult;
import org.julclang.tools.service.ToolsService;
import org.julclang.tools.uplc.UplcToolsService;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.JulcVm;
import org.julclang.vm.java.JavaVmProvider;

/** Full-only registrations, kept out of the VM entry point's reachable graph. */
public final class FullApi {
    private FullApi() {}

    public record TransactionRequest(UplcModels.ScriptInput script, MockTransaction transaction,
                                     Integer protocolVersion, Long maxCpu, Long maxMem,
                                     VmModels.Target target, VmModels.CostModel costModel) {
        VmModels.Target resolvedTarget() {
            return target == null ? new VmModels.Target(null, protocolVersion) : target;
        }
    }

    public static void register(ApiRegistry api, VmApi vmApi) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        var vm = JulcVm.withProvider(new JavaVmProvider());
        var service = new ToolsService(compiler, libraries, () -> vm);
        var evaluator = new ExpressionEvaluator(compiler, vm, libraries);
        var uplc = new UplcToolsService();
        api.add("compiler.check", CheckRequest.class, ToolsService::check);
        api.add("compiler.compile", CompileRequest.class, service::compile);
        api.add("compiler.evaluate", EvaluateRequest.class, service::evaluate);
        api.add("compiler.evalExpression", EvalExpressionRequest.class, r -> ToolsService.evalExpression(evaluator, r));
        api.add("uplc.decompile", UplcModels.DecompileRequest.class, uplc::decompile);
        api.add("uplc.evaluateTransaction", TransactionRequest.class, r -> transaction(r, vmApi, false));
        api.add("uplc.debugTransaction", TransactionRequest.class, r -> transaction(r, vmApi, true));
    }

    private static ServiceResult<?> transaction(TransactionRequest r, VmApi vm, boolean debug) {
        var tools = new UplcToolsService();
        try {
            var prepared = tools.prepareTransaction(r.script(), r.transaction(), r.resolvedTarget(), r.costModel(),
                    r.maxCpu(), r.maxMem());
            return debug ? vm.open(prepared) : vm.evaluate(prepared);
        } catch (IllegalArgumentException | org.julclang.core.text.UplcParseException e) {
            if (r.target() != null || r.costModel() != null) throw e;
            // Legacy invalid scripts/data are HTTP-200 error results. Delegate their
            // exact body to the same service used by the server.
            return debug ? tools.debug(new UplcModels.DebugRequest(r.script(), r.transaction(), r.protocolVersion(),
                    r.maxCpu(), r.maxMem(), "goto", 0L, null))
                    : tools.evaluate(new UplcModels.EvaluateRequest(r.script(), r.transaction(), r.protocolVersion(),
                    r.maxCpu(), r.maxMem()));
        }
    }
}
