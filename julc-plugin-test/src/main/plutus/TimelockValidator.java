import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.Interval;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * Timelock spending validator: checks that a deadline falls within
 * the transaction's validity interval.
 * <p>
 * The redeemer is a deadline (POSIXTime as integer Data).
 * The validator extracts the tx valid range from TxInfo and checks
 * that the deadline is contained within it.
 */
@SpendingValidator
class TimelockValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(PlutusData.cast(ctx, ScriptContext.class));
        Interval validRange = ContextsLib.txInfoValidRange(txInfo);
        return IntervalLib.contains(validRange, Builtins.unIData(redeemer));
    }
}
