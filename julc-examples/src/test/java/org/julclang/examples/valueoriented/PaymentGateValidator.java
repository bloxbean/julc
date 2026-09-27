package org.julclang.examples.valueoriented;

// region validator
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.AddressLib;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;

@SpendingValidator
class PaymentGateValidator {
    record GateDatum(byte[] owner, byte[] payee, BigInteger minimum) {}

    sealed interface GateAction permits Claim, Refund {}
    record Claim() implements GateAction {}
    record Refund() implements GateAction {}

    @Entrypoint
    static boolean validate(GateDatum datum, GateAction action, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return switch (action) {
            case Claim c -> paidTo(txInfo.outputs(), datum.payee()).compareTo(datum.minimum()) >= 0;
            case Refund r -> ContextsLib.signedBy(txInfo, datum.owner());
        };
    }

    static BigInteger paidTo(JulcList<TxOut> outputs, byte[] payee) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            boolean toPayee = Builtins.equalsByteString(AddressLib.credentialHash(output.address()), payee);
            BigInteger amount = toPayee ? ValuesLib.lovelaceOf(output.value()) : BigInteger.ZERO;
            total = total.add(amount);
        }
        return total;
    }
}
// endregion
