package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.pir.PirType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ledger's Data layouts that are JuLC's policy, keyed by FQCN (ADR-068 I11): they are never inferred from stub
 * metadata. Ledger hash types, which are their bytes, are {@code TypeRegistry.ledgerHashFqcns()}.
 */
public final class LedgerLayouts {

    public static final String PACKAGE = "org.julclang.ledger";

    public static final String VALUE = PACKAGE + ".Value";

    /** Ledger records whose Data is not a constructor: {@code Value} is a map. */
    public static final Set<String> NOT_CONSTRUCTOR_DATA = Set.of(VALUE);

    /** A {@code Value}'s Data: a map from policy to a map from token name to amount, as the ledger encodes it. */
    public static final PirType VALUE_DATA = new PirType.MapType(new PirType.ByteStringType(),
            new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType()));

    /** Sealed ledger types whose constructor tags the ledger fixes: variant simple names in tag order. */
    public static final Map<String, List<String>> FIXED_TAGS = Map.of(
            PACKAGE + ".Credential", List.of("PubKeyCredential", "ScriptCredential"));

    private LedgerLayouts() {
    }
}
