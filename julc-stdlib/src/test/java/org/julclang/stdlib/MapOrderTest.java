package org.julclang.stdlib;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.ledger.ScriptContextBuilder;
import org.julclang.ledger.PolicyId;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Typed map templates specialized at key and value types (ADR-059 generic exports). */
/** On-chain {@code JulcMap.keys()} and {@code values()} keep the map order, as off-chain. */
class MapOrderTest {
    @Test
    void javaMapKeysAndValuesKeepMapOrderOnChain() {
        var source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                @MintingValidator
                class Ordered {
                    @Entrypoint
                    static boolean validate(JulcMap<BigInteger, BigInteger> redeemer, ScriptContext ctx) {
                        JulcList<BigInteger> keys = redeemer.keys();
                        JulcList<BigInteger> values = redeemer.values();
                        return keys.head().equals(BigInteger.ONE) && values.head().equals(BigInteger.TEN);
                    }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);
        assertFalse(compiled.hasErrors(), compiled::toString);
        var redeemer = PlutusData.map(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
        var ctx = ScriptContextBuilder.minting(new PolicyId(new byte[28])).redeemer(redeemer).build().toPlutusData();
        var result = JulcVm.create().evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(), List.of(ctx),
                null, org.julclang.vm.EvalOptions.DEFAULT);
        assertInstanceOf(EvalResult.Success.class, result, "the first key and value come first: " + result);
    }
}
