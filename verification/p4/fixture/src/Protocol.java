import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;

import java.math.BigInteger;

@MultiValidator
class Protocol {
    record Datum(BigInteger state) {}
    record Spend(BigInteger next) {}
    record Mint(byte[] tokenName) {}
    record Certify(byte[] credential) {}

    @Entrypoint(purpose = Purpose.CERTIFY)
    static boolean certify(Certify redeemer, ScriptContext ctx) {
        return redeemer.credential().length > 0;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
        return redeemer.next().compareTo(datum.state()) > 0;
    }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(Mint redeemer, ScriptContext ctx) {
        return redeemer.tokenName().length > 0;
    }
}
