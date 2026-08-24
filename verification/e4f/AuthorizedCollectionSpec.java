package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** Generated-model-only specification; no raw IR or Lean is accepted here. */
public final class AuthorizedCollectionSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new AuthorizedCollectionModel();
        var guarantee = contract.datum().exists(datum ->
                contract.context().txInfo().signatories()
                        .contains(datum.config().owner())
                        .and(datum.config().minimum().isPresent()
                                .or(datum.config().minimum().isEmpty()))
                        .and(datum.config().values().all(value -> value.eq(value)))
                        .and(datum.config().balances().allEntries((key, value) ->
                                key.eq(key).and(value.eq(value))))
                        .and(contract.redeemer().exists(action ->
                                action.isUse().or(action.isStop()))));
        return contract.properties(property("collections.authorized",
                DslDomain.NONE, guarantee));
    }
}
