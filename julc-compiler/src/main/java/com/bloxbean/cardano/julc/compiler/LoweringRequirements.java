package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ProtocolCapability;

import java.util.Set;

/** Explicit protocol requirements declared by one source-to-PIR lowering. */
public record LoweringRequirements(
        Set<DefaultFun> builtins,
        Set<ProtocolCapability> capabilities) {

    public static final LoweringRequirements NONE =
            new LoweringRequirements(Set.of(), Set.of());

    public LoweringRequirements {
        builtins = Set.copyOf(builtins);
        capabilities = Set.copyOf(capabilities);
    }

    public static LoweringRequirements builtin(DefaultFun builtin) {
        return new LoweringRequirements(Set.of(builtin), Set.of());
    }

    public static LoweringRequirements builtins(DefaultFun... builtins) {
        return new LoweringRequirements(Set.of(builtins), Set.of());
    }

    public static LoweringRequirements capability(ProtocolCapability capability) {
        return new LoweringRequirements(Set.of(), Set.of(capability));
    }

    public boolean isEmpty() {
        return builtins.isEmpty() && capabilities.isEmpty();
    }
}
