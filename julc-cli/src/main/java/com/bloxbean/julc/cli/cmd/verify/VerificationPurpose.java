package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;

import java.util.Locale;

/** Verification-facing purpose names and their exact compiler/CIP-57 mappings. */
enum VerificationPurpose {
    SPENDING("spending", "spend", ContractSchema.Purpose.SPEND),
    MINTING("minting", "mint", ContractSchema.Purpose.MINT),
    REWARDING("rewarding", "withdraw", ContractSchema.Purpose.WITHDRAW),
    CERTIFYING("certifying", "publish", ContractSchema.Purpose.CERTIFY);

    private final String userName;
    private final String cip57Name;
    private final ContractSchema.Purpose compilerPurpose;

    VerificationPurpose(
            String userName,
            String cip57Name,
            ContractSchema.Purpose compilerPurpose) {
        this.userName = userName;
        this.cip57Name = cip57Name;
        this.compilerPurpose = compilerPurpose;
    }

    String userName() {
        return userName;
    }

    String cip57Name() {
        return cip57Name;
    }

    ContractSchema.Purpose compilerPurpose() {
        return compilerPurpose;
    }

    static VerificationPurpose fromUserName(String purpose) {
        String normalized = purpose == null ? "" : purpose.toLowerCase(Locale.ROOT);
        for (var candidate : values()) {
            if (candidate.userName.equals(normalized)) return candidate;
        }
        throw new IllegalArgumentException(
                "Purpose must be spending, minting, rewarding, or certifying");
    }
}
