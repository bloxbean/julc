import org.julclang.stdlib.annotation.Param;
import org.julclang.core.PlutusData;
class BadParam {
    @Param PlutusData.BytesData owner;
    static BigInteger dummy() { return 1; }
}
