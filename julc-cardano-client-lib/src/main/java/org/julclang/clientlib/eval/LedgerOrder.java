package org.julclang.clientlib.eval;

import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Credential;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.Voter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The key orders cardano-ledger uses when it translates its maps and sets into a script context. Every such map is
 * a Haskell {@code Data.Map} turned into a list in ascending key order, so the Plutus map follows the key's
 * {@code Ord} instance. Shared by the V1, V2 and V3 context builders.
 */
final class LedgerOrder {

    private LedgerOrder() {}

    /** Byte strings (hashes, policy ids, asset names): unsigned lexicographic, a prefix first. */
    static final Comparator<byte[]> BYTES = Arrays::compareUnsigned;

    /** cardano-ledger {@code Credential}: {@code ScriptHashObj} before {@code KeyHashObj}, then the hash. */
    static final Comparator<Credential> LEDGER_CREDENTIAL = Comparator
            .comparingInt((Credential c) -> c instanceof Credential.ScriptCredential ? 0 : 1)
            .thenComparing(LedgerOrder::hash, BYTES);

    /** plutus-ledger-api {@code Credential} (V1/V2 withdrawals): {@code PubKeyCredential} first, then the hash. */
    static final Comparator<Credential> PLUTUS_CREDENTIAL = Comparator
            .comparingInt((Credential c) -> c instanceof Credential.PubKeyCredential ? 0 : 1)
            .thenComparing(LedgerOrder::hash, BYTES);

    /**
     * cardano-ledger {@code AccountAddress}, as reward-account bytes (header, 28-byte hash): the network, then the
     * credential in ledger order.
     */
    static final Comparator<byte[]> ACCOUNT = Comparator
            .comparingInt((byte[] account) -> account[0] & 0x0f)
            .thenComparingInt(account -> (account[0] & 0x10) != 0 ? 0 : 1)
            .thenComparing(account -> Arrays.copyOfRange(account, 1, 29), BYTES);

    /** cardano-ledger {@code Voter}: committee, DRep, then pool; the credential in ledger order. */
    static final Comparator<Voter> VOTER = (a, b) -> {
        int c = Integer.compare(voterKind(a), voterKind(b));
        if (c != 0) return c;
        return switch (a) {
            case Voter.CommitteeVoter(var cred) ->
                    LEDGER_CREDENTIAL.compare(cred, ((Voter.CommitteeVoter) b).credential());
            case Voter.DRepVoter(var cred) -> LEDGER_CREDENTIAL.compare(cred, ((Voter.DRepVoter) b).credential());
            case Voter.StakePoolVoter(var pkh) ->
                    BYTES.compare(pkh.hash(), ((Voter.StakePoolVoter) b).pubKeyHash().hash());
        };
    };

    /** cardano-ledger {@code GovActionId}: the transaction id, then the index. */
    static final Comparator<GovernanceActionId> GOV_ACTION_ID = Comparator
            .comparing((GovernanceActionId id) -> id.txId().hash(), BYTES)
            .thenComparing(GovernanceActionId::govActionIx);

    /** A {@link JulcMap} holding the entries in the given order ({@link JulcAssocMap#insert} prepends). */
    static <K, V> JulcMap<K, V> assocMap(Map<K, V> ordered) {
        List<Map.Entry<K, V>> entries = new ArrayList<>(ordered.entrySet());
        JulcMap<K, V> result = JulcAssocMap.empty();
        for (int i = entries.size() - 1; i >= 0; i--) {
            result = result.insert(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return result;
    }

    private static byte[] hash(Credential credential) {
        return switch (credential) {
            case Credential.PubKeyCredential(var pkh) -> pkh.hash();
            case Credential.ScriptCredential(var sh) -> sh.hash();
        };
    }

    private static int voterKind(Voter voter) {
        return switch (voter) {
            case Voter.CommitteeVoter _ -> 0;
            case Voter.DRepVoter _ -> 1;
            case Voter.StakePoolVoter _ -> 2;
        };
    }
}
