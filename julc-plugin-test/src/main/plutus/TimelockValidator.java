import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * Timelock spending validator: checks that a deadline falls within
 * the transaction's validity interval.
 * <p>
 * The redeemer is a deadline (POSIXTime as integer Data).
 * The validator extracts the tx valid range from TxInfo and checks
 * that the deadline is contained within it.
 */
@Validator
class TimelockValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        PlutusData validRange = ContextsLib.txInfoValidRange(txInfo);
        return IntervalLib.contains(validRange, redeemer);
    }
}
