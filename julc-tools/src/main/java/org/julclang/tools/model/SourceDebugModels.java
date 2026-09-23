package org.julclang.tools.model;

import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.Breakpoints;
import org.julclang.tools.model.UplcModels.Snapshot;
import org.julclang.tools.model.UplcModels.Timeline;
import org.julclang.tools.model.VmModels.CostModel;
import org.julclang.tools.model.VmModels.Target;

import java.util.List;

/** Requests and responses for the experimental Java-source debugger. */
public final class SourceDebugModels {
    private SourceDebugModels() {}

    public record OpenRequest(String source, String librarySource, List<DataInput> params,
                              MockTransaction transaction, Integer protocolVersion, Long maxCpu, Long maxMem,
                              Target target, CostModel costModel) {
        public Target resolvedTarget() {
            return target == null ? new Target("PlutusV3", protocolVersion) : target;
        }
    }

    public record OpenResponse(boolean ok, String error, String sessionId, String source, String uplcText,
                               String compiledCode, String scriptHash, int scriptSizeBytes,
                               List<FieldDto> params, List<DiagnosticDto> diagnostics,
                               List<Integer> executableJavaLines, Timeline timeline, Snapshot snapshot,
                               Target target, String costModelId, String warning) {}

    public record ActionRequest(String sessionId, String action, Long step, Breakpoints breakpoints) {}

    public record ActionResponse(boolean ok, String error, Timeline timeline, Snapshot snapshot) {}

    public record CloseRequest(String sessionId) {}
}
