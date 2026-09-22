package org.julclang.tools.uplc;

import org.julclang.core.PlutusData;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.model.VmModels.CostModel;
import org.julclang.tools.model.VmModels.Request;
import org.julclang.tools.model.VmModels.Target;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProtocolVersion;
import org.julclang.vm.java.JavaVmProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves a script once and binds a private provider to its immutable cost configuration. */
public final class EvaluationPreparation {
    private EvaluationPreparation() {}

    public record Prepared(ScriptDecoder.DecodedScript decoded, List<PlutusData> args,
                           PlutusData scriptContext, LedgerEvaluationTarget target, ExBudget budget,
                           JavaVmProvider provider, String costModelId) {
        public Prepared {
            args = List.copyOf(args);
        }

        public Prepared withArguments(List<PlutusData> arguments, PlutusData context) {
            return new Prepared(decoded, arguments, context, target, budget, provider, costModelId);
        }
    }

    public static Prepared raw(Request request) {
        var prepared = script(request.script(), request.target(), request.costModel(),
                request.maxCpu(), request.maxMem());
        var args = new ArrayList<PlutusData>();
        for (var input : request.args()) {
            var data = DataInputs.parse(input);
            if (data == null) throw new IllegalArgumentException("VM arguments must not be empty");
            args.add(data);
        }
        return prepared.withArguments(args, null);
    }

    public static Prepared script(ScriptInput script, Target target, CostModel model, Long maxCpu, Long maxMem) {
        if (script == null) throw new IllegalArgumentException("A script is required");
        if (target != null && target.language() != null) {
            // Delegate all language selection, hashing and parameter application to the decoder.
            script = new ScriptInput(script.script(), script.params(), language(target.language()).name().substring(7),
                    script.validator());
        }
        var decoded = ScriptDecoder.decode(script);
        int protocol = target == null || target.protocol() == null ? 11 : target.protocol();
        if (protocol != 10 && protocol != 11) throw new IllegalArgumentException("Protocol version must be 10 or 11");
        var resolved = new LedgerEvaluationTarget(decoded.language(), new ProtocolVersion(protocol, 0));
        var budget = new ExBudget(maxCpu == null ? UplcToolsService.DEFAULT_MAX_CPU : maxCpu,
                maxMem == null ? UplcToolsService.DEFAULT_MAX_MEM : maxMem);
        // Never mutate a shared provider: an interleaved request must not change a
        // debug replay, or leave custom costs installed for a later default request.
        var provider = new JavaVmProvider();
        String id = "default:" + decoded.language().name() + ":" + protocol;
        if (model != null) {
            long[] parameters;
            LedgerEvaluationTarget modelTarget;
            if (model.profile() != null) {
                if (model.parameters() != null || model.target() != null) {
                    throw new IllegalArgumentException("Choose either a cost profile or explicit parameters and target");
                }
                var profile = OptimizationCostProfiles.forId(model.profile());
                parameters = profile.costModelParameters();
                modelTarget = profile.target();
                id = profile.profileId();
            } else {
                if (model.parameters() == null || model.target() == null || model.target().language() == null
                        || model.target().protocol() == null) {
                    throw new IllegalArgumentException("Explicit cost parameters require a complete target");
                }
                modelTarget = new LedgerEvaluationTarget(language(model.target().language()),
                        new ProtocolVersion(model.target().protocol(), 0));
                parameters = model.parameters().stream().mapToLong(Long::longValue).toArray();
                id = "explicit";
            }
            if (!modelTarget.equals(resolved)) throw new IllegalArgumentException("Cost model target does not match evaluation target");
            provider.setCostModelParams(parameters, resolved.ledgerLanguage(), protocol, 0);
        }
        return new Prepared(decoded, List.of(), null, resolved, budget, provider, id);
    }

    public static Target targetOf(Prepared prepared) {
        return new Target("Plutus" + prepared.target().ledgerLanguage().name().substring(7),
                prepared.target().protocolVersion().major());
    }

    private static PlutusLanguage language(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "V1", "PLUTUSV1", "PLUTUS_V1" -> PlutusLanguage.PLUTUS_V1;
            case "V2", "PLUTUSV2", "PLUTUS_V2" -> PlutusLanguage.PLUTUS_V2;
            case "V3", "PLUTUSV3", "PLUTUS_V3" -> PlutusLanguage.PLUTUS_V3;
            default -> throw new IllegalArgumentException("Unknown language: " + value);
        };
    }
}
