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
                              Target target, CostModel costModel, Boolean locals) {
        public OpenRequest(String source, String librarySource, List<DataInput> params,
                           MockTransaction transaction, Integer protocolVersion, Long maxCpu, Long maxMem,
                           Target target, CostModel costModel) {
            this(source, librarySource, params, transaction, protocolVersion, maxCpu, maxMem,
                    target, costModel, null);
        }

        public Target resolvedTarget() {
            return target == null ? new Target("PlutusV3", protocolVersion) : target;
        }
    }

    public record OpenResponse(boolean ok, String error, String sessionId, String source, String uplcText,
                               String compiledCode, String scriptHash, int scriptSizeBytes,
                               List<FieldDto> params, List<DiagnosticDto> diagnostics,
                               List<Integer> executableJavaLines, Timeline timeline, Snapshot snapshot,
                               Target target, String costModelId, String warning,
                               LocalsCapability localsCapability) {
        public OpenResponse(boolean ok, String error, String sessionId, String source, String uplcText,
                            String compiledCode, String scriptHash, int scriptSizeBytes,
                            List<FieldDto> params, List<DiagnosticDto> diagnostics,
                            List<Integer> executableJavaLines, Timeline timeline, Snapshot snapshot,
                            Target target, String costModelId, String warning) {
            this(ok, error, sessionId, source, uplcText, compiledCode, scriptHash, scriptSizeBytes,
                    params, diagnostics, executableJavaLines, timeline, snapshot, target, costModelId,
                    warning, null);
        }
    }

    public record ActionRequest(String sessionId, String action, Long step, Breakpoints breakpoints) {}

    public record ActionResponse(boolean ok, String error, Timeline timeline, Snapshot snapshot) {}

    public record CloseRequest(String sessionId) {}

    public record LocalsCapability(boolean available, String schema, List<String> layouts,
                                   String unavailableReason) {
        public LocalsCapability {
            layouts = List.copyOf(layouts);
        }
    }

    public record LocalsRequest(String sessionId, long stopGeneration) {}

    public record ChildrenRequest(String sessionId, long stopGeneration, String handle,
                                  int start, int count) {}

    public record LocalsResponse(boolean ok, String error, long stopGeneration,
                                 String availability, String reason, List<ScopeValue> scopes) {
        public LocalsResponse { scopes = List.copyOf(scopes); }
    }

    public record ChildrenResponse(boolean ok, String error, long stopGeneration,
                                   String handle, int start, Integer nextStart,
                                   List<ChildValue> children) {
        public ChildrenResponse { children = List.copyOf(children); }
    }

    public record ScopeValue(String id, String kind, List<LocalValue> variables) {
        public ScopeValue { variables = List.copyOf(variables); }
    }

    public record LocalValue(String bindingId, String name, String declaredType, String resolvedType,
                             boolean shadowed, String availability, String reason,
                             SourceRange declaration, DebugValue value) {}

    public record SourceRange(String sourceId, int startUtf16, int endUtf16,
                              int startLine, int startColumn, int endLine, int endColumn) {}

    public record DebugValue(String kind, String summary, String typeId, String layoutId,
                             String availability, boolean truncated, String truncationReason,
                             Integer childCount, String childrenHandle) {}

    public record ChildValue(String name, DebugValue value) {}
}
