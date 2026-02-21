package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Detects hardcoded credential hashes (28-byte or 32-byte ByteString literals).
 * <p>
 * Hardcoded hashes represent centralization risk — if a private key is lost,
 * the contract becomes permanently locked or permanently permissive.
 * <p>
 * Checks multiple HIR node types:
 * <ul>
 *   <li>{@code ByteStringLiteral} — bare byte string</li>
 *   <li>{@code DataLiteral} — PlutusData containing BytesData</li>
 *   <li>{@code ConstValue} — raw UPLC constant (ByteStringConst)</li>
 * </ul>
 */
public final class HardcodedCredentialRule implements SecurityRule {

    private static final HexFormat HEX = HexFormat.of();

    @Override
    public String name() {
        return "HardcodedCredential";
    }

    @Override
    public List<Finding> analyze(DecompileResult result) {
        if (result.hir() == null) return List.of();

        var findings = new ArrayList<Finding>();

        HirTreeWalker.walk(result.hir(), node -> {
            byte[] bytes = extractBytes(node);
            if (bytes != null && (bytes.length == 28 || bytes.length == 32)) {
                findings.add(createFinding(bytes));
            }
            // Also scan inside DataLiteral for nested BytesData
            if (node instanceof HirTerm.DataLiteral dl) {
                collectBytesFromPlutusData(dl.value(), findings);
            }
        });

        return findings;
    }

    private static byte[] extractBytes(HirTerm node) {
        if (node instanceof HirTerm.ByteStringLiteral bs) {
            return bs.value();
        }
        if (node instanceof HirTerm.ConstValue cv
                && cv.value() instanceof Constant.ByteStringConst bsc) {
            return bsc.value();
        }
        return null;
    }

    private static void collectBytesFromPlutusData(PlutusData data, List<Finding> findings) {
        switch (data) {
            case PlutusData.BytesData bd -> {
                if (bd.value().length == 28 || bd.value().length == 32) {
                    findings.add(createFinding(bd.value()));
                }
            }
            case PlutusData.ConstrData cd -> {
                for (var field : cd.fields()) {
                    collectBytesFromPlutusData(field, findings);
                }
            }
            case PlutusData.ListData ld -> {
                for (var item : ld.items()) {
                    collectBytesFromPlutusData(item, findings);
                }
            }
            case PlutusData.MapData md -> {
                for (var entry : md.entries()) {
                    collectBytesFromPlutusData(entry.key(), findings);
                    collectBytesFromPlutusData(entry.value(), findings);
                }
            }
            case PlutusData.IntData _ -> { /* no bytes */ }
        }
    }

    private static Finding createFinding(byte[] bytes) {
        String hashType = bytes.length == 28 ? "credential hash (28 bytes)" : "hash (32 bytes)";
        String hexValue = HEX.formatHex(bytes);
        String shortHex = hexValue.length() > 16
                ? hexValue.substring(0, 8) + "..." + hexValue.substring(hexValue.length() - 8)
                : hexValue;

        return new Finding(
                Severity.MEDIUM,
                Category.HARDCODED_CREDENTIAL,
                "Hardcoded " + hashType,
                "ByteString literal " + shortHex + " appears to be a hardcoded "
                        + hashType + ". This creates a centralization risk — if the "
                        + "corresponding private key is lost, the contract may become "
                        + "permanently locked or uncontrollable.",
                "ByteString literal",
                "Consider using a parameterized script where credential hashes "
                        + "are passed as datum or redeemer fields."
        );
    }
}
