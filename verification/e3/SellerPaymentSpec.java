package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** User-owned E.3 DSL source; the generated SaleModel is disposable. */
public final class SellerPaymentSpec implements VerificationSpecification {
    public SellerPaymentSpec() { }

    @Override
    public DslPropertySet properties() {
        var contract = new SaleModel();
        var paid = contract.context().txInfo().outputs().exists(output ->
                output.address().credential().matchesKeyHash(contract.datum().seller())
                        .and(output.value().lovelace().ge(contract.datum().price())));
        return DslPropertySet.of(property("seller-paid-at-least",
                contract.validSpendingContext()
                        .and(contract.exactUplcSucceeds())
                        .implies(paid)));
    }
}
