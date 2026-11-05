import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcG1;
import org.julclang.core.types.JulcG2;
import org.julclang.core.types.JulcMlResult;
import org.julclang.core.types.JulcScalars;
import org.julclang.core.types.JulcG1Points;
import org.julclang.core.types.JulcG2Points;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.BlsLib;
import org.julclang.stdlib.lib.ContextsLib;
import java.math.BigInteger;
class InExpression {
    static boolean m(JulcList<BigInteger> xs, byte[] dst) {
        JulcG1 acc = Builtins.bls12_381_G1_hashToGroup(dst, dst);
        for (var x : xs) {
            acc = BlsLib.g1Neg(acc);
            BlsLib.g2Compress(acc = Builtins.bls12_381_G2_hashToGroup(dst, dst));
        }
        return BlsLib.g1Equal(acc, acc);
    }
}
