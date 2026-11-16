package org.julclang.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxInfo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An output's reference script hash in the context is the ledger's: {@code hashScript}, blake2b-224 of the script
 * type and the script's original bytes ({@code Cardano.Ledger.Core.hashScript} at cardano-ledger {@code c8cbb661d3}),
 * never the hash of a re-encoding (julc #243).
 * <p>
 * Every expected hash below was computed outside the code under test, with Python's {@code hashlib.blake2b(type ‖
 * script, digest_size=28)}. The canonical encoding's hash, which re-encoding gives, is listed beside each, to show the
 * test tells the two apart.
 */
class ReferenceScriptHashTest {

    private static final String KEY = "11".repeat(28);
    private static final String TX_HASH = "ab".repeat(32);
    private static final String ADDR = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    /** A native script, encoded as given, with the ledger's hash of it and of its canonical encoding. */
    private record NativeVector(String name, String script, String hash, String canonicalHash) {}

    private static final List<NativeVector> NON_CANONICAL = List.of(
            // ScriptAll [ScriptPubkey KEY] with an indefinite-length list
            new NativeVector("indefinite ScriptAll list", "82019f8200581c" + KEY + "ff",
                    "7c57ffc912c4f2251566f140285dba07fb4c4edfe9e585d612370a7f",
                    "274a29d05ac6fe4222292487830c20b350eb791d5a4ea507da56b187"),
            // InvalidBefore 5, the slot in a 4-byte head
            new NativeVector("non-minimal InvalidBefore slot", "82041a00000005",
                    "2699a15fb3cb6bf3a7402764bb67a99a2277a44fd1a0702d89d58cbc",
                    "7789659c6184299a248e40ed68e6329d09f3839c556f546c42f042d6"),
            // ScriptNOfK 1 [ScriptPubkey KEY], the count in a 1-byte head
            new NativeVector("non-minimal ScriptNOfK count", "83031801818200581c" + KEY,
                    "2628c51e62429ec526cc57b72916c48b922e0ad4a314a198c23d7556",
                    "a60fa94bc424ff769d93737357e77c66a68220405e09cb52639a2e4d"));

    /** ScriptPubkey KEY, canonically encoded; its hash is also JulcTransactionEvaluatorTest's NATIVE_REF_HASH. */
    private static final String PUBKEY = "8200581c" + KEY;
    private static final String PUBKEY_HASH = "5334c8c55ec6518f2e262da617d3170bf0722f43813d64dbaf0ff0b9";

    /** A Plutus script reference with the bytes {@code 010203}; the hash covers the type byte and those bytes. */
    private static final String PLUTUS_V2_REF = "820243010203";
    private static final String PLUTUS_V2_HASH = "23fce83ba01633b42251eee8a63d82dca216b8b5bfd65a2f13ab3c27";
    private static final String PLUTUS_V3_REF = "820343010203";
    private static final String PLUTUS_V3_HASH = "8b8c11dcad0af38c40d742ed155b4c938acc5507a0ecbcfcea36496a";

    @Test
    void nonCanonicalNativeReferenceScripts_hashTheirOriginalBytes() {
        for (var vector : NON_CANONICAL) {
            byte[] cbor = txBytes(List.of("8200" + vector.script()));
            for (int pv : new int[]{7, 8, 9, 10, 11}) {
                TxInfo info = convert(cbor, pv);
                var hash = info.outputs().get(0).referenceScript();
                assertEquals(Optional.of(scriptHash(vector.hash())), hash, vector.name() + " at PV " + pv);
                assertNotEquals(Optional.of(scriptHash(vector.canonicalHash())), hash, vector.name());
                // and the encoded context carries it: TxInfo field 2, the outputs; TxOut field 3, Just hash
                assertEquals(justHash(vector.hash()), encodedReferenceScript(info, 0), vector.name());
            }
        }
    }

    @Test
    void canonicalNativeAndPlutusReferenceScripts_keepTheirHashes() {
        TxInfo info = convert(txBytes(List.of("8200" + PUBKEY, PLUTUS_V2_REF, PLUTUS_V3_REF)), 10);
        assertEquals(Optional.of(scriptHash(PUBKEY_HASH)), info.outputs().get(0).referenceScript());
        assertEquals(Optional.of(scriptHash(PLUTUS_V2_HASH)), info.outputs().get(1).referenceScript());
        assertEquals(Optional.of(scriptHash(PLUTUS_V3_HASH)), info.outputs().get(2).referenceScript());
    }

    @Test
    void indefiniteOutputsAndOutputMap_keepEachOutputsReferenceScript() {
        // outputs [no script, a non-canonical native script, a Plutus V2 script]; the outputs list and the second
        // output's map re-encoded with indefinite length, which CBOR allows and the ledger reads
        var nonCanonical = NON_CANONICAL.getFirst();
        var envelopes = new ArrayList<String>();
        envelopes.add(null);
        envelopes.add("8200" + nonCanonical.script());
        envelopes.add(PLUTUS_V2_REF);
        byte[] cbor = txBytes(envelopes);
        cbor = indefinite(cbor, RawTx.of(cbor).bodyField(1).orElseThrow().items().get(1));
        cbor = indefinite(cbor, RawTx.of(cbor).bodyField(1).orElseThrow());
        assertEquals(0x9f, cbor[RawTx.of(cbor).bodyField(1).orElseThrow().offset()] & 0xff, "outputs are indefinite");
        assertEquals(0xbf, cbor[RawTx.of(cbor).bodyField(1).orElseThrow().items().get(1).offset()] & 0xff,
                "output 1 is an indefinite map");

        for (int pv : new int[]{8, 10}) {
            TxInfo info = convert(cbor, pv);
            assertEquals(3, info.outputs().size());
            assertEquals(Optional.empty(), info.outputs().get(0).referenceScript());
            assertEquals(Optional.of(scriptHash(nonCanonical.hash())), info.outputs().get(1).referenceScript());
            assertEquals(Optional.of(scriptHash(PLUTUS_V2_HASH)), info.outputs().get(2).referenceScript());
        }
    }

    @Test
    void plutusV3ReferenceScript_isDecodedFromConwayOnly() {
        // eraMaxLanguage: PlutusV2 in Babbage (Babbage/Scripts.hs), PlutusV3 in Conway (Conway/Scripts.hs)
        byte[] v3 = txBytes(List.of(PLUTUS_V3_REF));
        for (int pv : new int[]{7, 8}) {
            var error = assertThrows(IllegalStateException.class, () -> convert(v3, pv));
            assertTrue(error.getMessage().contains("reference script of output 0: script type 3 is not a language of "
                    + "protocol version " + pv), error.getMessage());
        }
        for (int pv : new int[]{9, 10, 11}) {
            assertEquals(Optional.of(scriptHash(PLUTUS_V3_HASH)), convert(v3, pv).outputs().get(0).referenceScript());
        }
        assertEquals(Optional.of(scriptHash(PLUTUS_V2_HASH)),
                convert(txBytes(List.of(PLUTUS_V2_REF)), 8).outputs().get(0).referenceScript());
    }

    @Test
    void scriptReferenceEnvelope_isReadAsTheLedgerReadsIt() {
        // decodeRecordSum (cardano-ledger-binary Decoder.hs): a definite [type, script] must have exactly two items;
        // an indefinite one may be used, and so may a type in a wider head than it needs (cborg's decodeWord)
        for (String envelope : List.of("9f00" + PUBKEY + "ff", "821800" + PUBKEY)) {
            assertEquals(Optional.of(scriptHash(PUBKEY_HASH)),
                    convert(txBytes(List.of(envelope)), 10).outputs().get(0).referenceScript(), envelope);
        }
        // three items, one item, type 4 (no era decodes Plutus V4), a type that is no integer
        assertRejected(List.of("8300" + PUBKEY + "00", "8100", "820443010203", "82410043010203"));
    }

    @Test
    void referenceScriptsTheLedgerCannotDecode_failTheEvaluation() {
        // Each is hashable bytes, but no Timelock or PlutusBinary: decoding the output fails in the ledger (decodeCIC)
        assertRejected(List.of(
                "82008109",                                // Timelock constructor 9 (Allegra decRaw: Invalid)
                "820005",                                  // an integer, not a Timelock
                "82008200410a",                            // a 1-byte key hash (a KeyHash is 28 bytes)
                "8200d81e8200581c" + KEY,                  // a tagged Timelock (cborg does not skip tags)
                "82008201" + "81c0" + PUBKEY,              // a tag inside ScriptAll's list
                "820082005f5818" + "11".repeat(24) + "4411111111ff", // a key hash in a chunked byte string
                "820082068200581c" + KEY,                  // Dijkstra's RequireGuard [6, credential]: not a Conway Timelock
                "820082018182068200581c" + KEY,            // RequireGuard inside ScriptAll
                "820205",                                  // a Plutus script that is not a byte string
                "82025f4201024103ff",                      // a Plutus script in a chunked byte string
                "8202c243010203"));                        // a tagged Plutus byte string
    }

    @Test
    void chunkedScriptReferencePayload_failsTheEvaluation() {
        // decodeNestedCborBytes = decodeNestedCborTag >> decodeBytes, and cborg's decodeBytes refuses 0x5f
        byte[] cbor = txBytes(List.of("8200" + PUBKEY));
        CborSpan bytes = scriptRefPayload(cbor, 0);
        int start = bytes.offset();
        int end = start + bytes.length();
        var chunked = new byte[cbor.length + 2];
        System.arraycopy(cbor, 0, chunked, 0, start);
        chunked[start] = 0x5f;
        System.arraycopy(cbor, start, chunked, start + 1, end - start);
        chunked[end + 1] = (byte) 0xff;
        System.arraycopy(cbor, end, chunked, end + 2, cbor.length - end);
        assertTrue(scriptRefPayload(chunked, 0).isIndefinite());

        var error = assertThrows(IllegalStateException.class, () -> convert(chunked, 10));
        assertTrue(error.getMessage().contains("reference script of output 0"), error.getMessage());
    }

    @Test
    void deeplyNestedNativeReferenceScript_isHashedWithoutDeepRecursion() throws Exception {
        // ScriptAll [ScriptAll [... ScriptPubkey KEY]], 5,000 levels (15,032 bytes, about a maximum-size transaction)
        byte[] cbor = txBytes(List.of("8200" + "820181".repeat(5_000) + PUBKEY));
        var result = new AtomicReference<Object>();
        // on a small stack, as an embedder's worker thread may be
        var thread = new Thread(null, () -> {
            try {
                result.set(convert(cbor, 10).outputs().get(0).referenceScript());
            } catch (Throwable e) {
                result.set(e);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        assertEquals(Optional.of(scriptHash("6e6b9f12419e09b375ee63a053377993899cf8e7510b697ce8880bb8")), result.get());
    }

    // --- helpers ---

    /**
     * A transaction whose output {@code i} has the script reference payload {@code envelopes.get(i)} exactly (null:
     * no script reference). The payloads are written into the serialised bytes, because CCL's serialiser re-encodes
     * the {@code [type, script]} pair, and each is checked to be there.
     */
    private static byte[] txBytes(List<String> envelopes) {
        var outputs = new ArrayList<TransactionOutput>();
        for (String envelope : envelopes) {
            outputs.add(envelope == null ? plainOutput() : output("8200" + PUBKEY));
        }
        byte[] cbor;
        try {
            cbor = tx(outputs).serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < envelopes.size(); i++) {
            if (envelopes.get(i) == null) continue;
            byte[] payload = HexFormat.of().parseHex(envelopes.get(i));
            CborSpan bytes = scriptRefPayload(cbor, i);
            byte[] head = byteStringHead(payload.length);
            var out = new byte[cbor.length - bytes.length() + head.length + payload.length];
            System.arraycopy(cbor, 0, out, 0, bytes.offset());
            System.arraycopy(head, 0, out, bytes.offset(), head.length);
            System.arraycopy(payload, 0, out, bytes.offset() + head.length, payload.length);
            int end = bytes.offset() + bytes.length();
            System.arraycopy(cbor, end, out, bytes.offset() + head.length + payload.length, cbor.length - end);
            cbor = out;
        }
        for (int i = 0; i < envelopes.size(); i++) {
            if (envelopes.get(i) == null) continue;
            assertEquals(envelopes.get(i), HexFormat.of().formatHex(scriptRefPayload(cbor, i).byteString()),
                    "output " + i + " carries its payload exactly");
        }
        return cbor;
    }

    /** The definite byte string inside output {@code index}'s {@code #6.24(bytes)} script reference. */
    private static CborSpan scriptRefPayload(byte[] cbor, int index) {
        return RawTx.of(cbor).bodyField(1).orElseThrow().items().get(index).entries().stream()
                .filter(entry -> entry.getKey().asLong() == 3).findFirst().orElseThrow().getValue().untag();
    }

    private static byte[] byteStringHead(int length) {
        if (length < 24) return new byte[]{(byte) (0x40 | length)};
        if (length < 256) return new byte[]{0x58, (byte) length};
        return new byte[]{0x59, (byte) (length >>> 8), (byte) length};
    }

    /** The context of exactly these bytes, as {@link JulcTransactionEvaluator} builds it. */
    private static TxInfo convert(byte[] cbor, int pv) {
        try {
            return new CclTxConverter(Transaction.deserialize(cbor), cbor, Set.of(utxo()), null, null, pv)
                    .buildTxInfo();
        } catch (CborDeserializationException e) {
            throw new AssertionError("CCL's model refused the transaction", e);
        }
    }

    private static void assertRejected(List<String> envelopes) {
        for (String envelope : envelopes) {
            byte[] cbor = txBytes(List.of(envelope));
            var error = assertThrows(IllegalStateException.class, () -> convert(cbor, 10), envelope);
            assertTrue(error.getMessage().contains("reference script of output 0"), envelope + ": " + error.getMessage());
        }
    }

    /** Re-encodes the definite array or map at {@code item}, whose head is one byte, with indefinite length. */
    private static byte[] indefinite(byte[] cbor, CborSpan item) {
        int head = cbor[item.offset()] & 0xff;
        int major = head >>> 5;
        assertTrue((major == 4 || major == 5) && (head & 31) < 24, "a one-byte array or map head");
        int end = item.offset() + item.length();
        var out = new byte[cbor.length + 1];
        System.arraycopy(cbor, 0, out, 0, item.offset());
        out[item.offset()] = (byte) (major << 5 | 31);
        System.arraycopy(cbor, item.offset() + 1, out, item.offset() + 1, end - item.offset() - 1);
        out[end] = (byte) 0xff;
        System.arraycopy(cbor, end, out, end + 1, cbor.length - end);
        return out;
    }

    private static Transaction tx(List<TransactionOutput> outputs) {
        return Transaction.builder()
                .body(TransactionBody.builder().inputs(List.of(new TransactionInput(TX_HASH, 0)))
                        .outputs(new ArrayList<>(outputs)).fee(BigInteger.valueOf(200_000)).build())
                .witnessSet(TransactionWitnessSet.builder().redeemers(List.of(Redeemer.builder().tag(RedeemerTag.Spend)
                        .index(BigInteger.ZERO).data(new BigIntPlutusData(BigInteger.ONE))
                        .exUnits(ExUnits.builder().mem(BigInteger.ZERO).steps(BigInteger.ZERO).build()).build()))
                        .build())
                .build();
    }

    private static TransactionOutput output(String scriptRefHex) {
        return TransactionOutput.builder().address(ADDR).value(new Value(BigInteger.valueOf(2_000_000), List.of()))
                .scriptRef(HexFormat.of().parseHex(scriptRefHex)).build();
    }

    private static TransactionOutput plainOutput() {
        return TransactionOutput.builder().address(ADDR).value(new Value(BigInteger.valueOf(2_000_000), List.of()))
                .build();
    }

    private static Utxo utxo() {
        return Utxo.builder().txHash(TX_HASH).outputIndex(0).address(ADDR)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(10_000_000)))).build();
    }

    private static ScriptHash scriptHash(String hex) {
        return ScriptHash.of(HexFormat.of().parseHex(hex));
    }

    private static PlutusData justHash(String hex) {
        return new PlutusData.ConstrData(0, List.of(new PlutusData.BytesData(HexFormat.of().parseHex(hex))));
    }

    private static PlutusData encodedReferenceScript(TxInfo info, int output) {
        var outputs = ((PlutusData.ListData) ((PlutusData.ConstrData) info.toPlutusData()).fields().get(2)).items();
        return ((PlutusData.ConstrData) outputs.get(output)).fields().get(3);
    }
}
