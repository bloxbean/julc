package m3b.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ListsLib;
import org.julclang.stdlib.lib.MapLib;
import org.julclang.stdlib.lib.MathLib;

/** ADR-068 M3b: the call sites of library methods. The bodies are a later slice, so the program stops there. */
@SpendingValidator
class M3bLibraryCalls {
    static BigInteger pow(BigInteger a, BigInteger b) { return MathLib.pow(a, b); }
    static BigInteger floorDiv(BigInteger a, BigInteger b) { return MathLib.floorDiv(a, b); }
    static boolean contains(JulcList<PlutusData> l, PlutusData x) { return ListsLib.contains(l, x); }
    static JulcList<PlutusData> prepend(JulcList<PlutusData> l, PlutusData x) { return ListsLib.prepend(l, x); }
    static JulcList<PlutusData> reversed(JulcList<PlutusData> l) { return ListsLib.reverse(l); }
    static JulcMap<PlutusData, PlutusData> insert(JulcMap<PlutusData, PlutusData> m, PlutusData k, PlutusData v) { return MapLib.insert(m, k, v); }
    static boolean member(JulcMap<PlutusData, PlutusData> m, PlutusData k) { return MapLib.member(m, k); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).signum() > 0;
    }
}
