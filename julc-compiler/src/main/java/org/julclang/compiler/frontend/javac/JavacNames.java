package org.julclang.compiler.frontend.javac;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The qualified names the javac frontend matches by identity, in one place: a typo in one of these is a silent miss
 * (ADR-068 F848). Compile-time constants, so a {@code switch} can name them.
 */
public final class JavacNames {

    public static final String OBJECT = "java.lang.Object";
    public static final String BOOLEAN = "java.lang.Boolean";
    public static final String BIG_INTEGER = "java.math.BigInteger";
    public static final String STRING = "java.lang.String";
    public static final String PLUTUS_DATA = "org.julclang.core.PlutusData";
    public static final String CONSTR_DATA = PLUTUS_DATA + ".ConstrData";
    public static final String TYPES = "org.julclang.core.types.";

    private static final String ANNOTATIONS = "org.julclang.stdlib.annotation.";
    public static final String ENTRYPOINT = ANNOTATIONS + "Entrypoint";
    public static final String PARAM = ANNOTATIONS + "Param";
    public static final String ONCHAIN_LIBRARY = ANNOTATIONS + "OnchainLibrary";

    /** The validator annotations, in the reference's order. */
    public static final List<String> VALIDATORS = List.of(
            ANNOTATIONS + "SpendingValidator", ANNOTATIONS + "MintingValidator", ANNOTATIONS + "WithdrawValidator",
            ANNOTATIONS + "CertifyingValidator", ANNOTATIONS + "VotingValidator", ANNOTATIONS + "ProposingValidator",
            ANNOTATIONS + "MultiValidator");

    /** The typed Data subtypes a {@code @Param} cannot have. */
    public static final Set<String> BANNED_PARAM_TYPES = Set.of(PLUTUS_DATA + ".BytesData", PLUTUS_DATA + ".MapData",
            PLUTUS_DATA + ".ListData", PLUTUS_DATA + ".IntData");

    /** The API constants JuLC knows, by the identity of the class that declares them. */
    public static final Map<String, Set<String>> API_CONSTANTS = Map.of(
            BIG_INTEGER, Set.of("ZERO", "ONE", "TWO", "TEN"),
            PLUTUS_DATA, Set.of("UNIT"));

    private JavacNames() {
    }
}
