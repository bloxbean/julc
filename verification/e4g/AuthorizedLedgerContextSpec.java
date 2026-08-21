package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

public final class AuthorizedLedgerContextSpec implements VerificationSpecification {
    @Override public DslPropertySet properties() {
        var contract = new AuthorizedLedgerContextModel();
        var tx = contract.context().txInfo();
        var matchingReference = tx.referenceInputs().at(integer(0)).exists(input ->
                input.resolved().datum().isInline()
                        .and(input.resolved().address().paymentCredential().isScript())
                        .and(input.resolved().referenceScript().isEmpty()));
        var datumWitness = tx.datums().existsEntry((hash, raw) -> bool(true));
        var redeemerWitness = tx.redeemers().existsEntry((purpose, raw) -> bool(true));
        var currentPurpose = contract.context().scriptPurpose().isSpending();
        return contract.properties(
                property("ledger-context.reference-shape",
                        DslDomain.VALID_SPENDING_V3_PINNED, matchingReference),
                property("ledger-context.fee",
                        DslDomain.VALID_SPENDING_V3_PINNED,
                        tx.fee().ge(integer(0))),
                property("ledger-context.datum-witness",
                        DslDomain.VALID_SPENDING_V3_PINNED, datumWitness),
                property("ledger-context.redeemer-witness",
                        DslDomain.VALID_SPENDING_V3_PINNED, redeemerWitness),
                property("ledger-context.current-purpose",
                        DslDomain.VALID_SPENDING_V3_PINNED, currentPurpose));
    }
}
