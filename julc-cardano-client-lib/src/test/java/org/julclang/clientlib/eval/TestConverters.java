package org.julclang.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.util.Set;

/** Test helper: a {@link CclTxConverter} for a transaction built in the test, from its CCL serialisation. */
final class TestConverters {

    private TestConverters() {}

    static CclTxConverter of(Transaction tx, Set<Utxo> utxos, int protocolMajorVersion) {
        try {
            return new CclTxConverter(tx, tx.serialize(), utxos, null, null, protocolMajorVersion);
        } catch (CborSerializationException e) {
            throw new IllegalStateException(e);
        }
    }
}
