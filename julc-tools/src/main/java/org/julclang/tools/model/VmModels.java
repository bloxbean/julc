package org.julclang.tools.model;

import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;

import java.util.List;

/** Transport records for the raw-argument VM API. */
public final class VmModels {
    private VmModels() {}

    public record Target(String language, Integer protocol) {}

    public record CostModel(String profile, Target target, List<Long> parameters) {
        public CostModel {
            parameters = parameters == null ? null : List.copyOf(parameters);
        }
    }

    public record Request(ScriptInput script, List<DataInput> args, Target target,
                          CostModel costModel, Long maxCpu, Long maxMem) {
        public Request {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
