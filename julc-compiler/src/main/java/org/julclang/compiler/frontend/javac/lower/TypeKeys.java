package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.LedgerLayouts;
import org.julclang.compiler.pir.PirType;

import java.util.HashMap;
import java.util.Map;

/**
 * The exact {@code TypeMethodRegistry} key of a method's owner (ADR-068 I12): identity, not spelling. {@code dispatch}
 * guesses {@code rt.name() + "." + method}, which is how a user's {@code Value} reaches the ledger's handlers; here only
 * {@code org.julclang.ledger.Value} has that key. An owner with no entry has no registry method.
 */
final class TypeKeys {

    private static final String VALUE = "Value";

    private static final Map<String, String> BY_OWNER = owners();

    private TypeKeys() {
    }

    private static Map<String, String> owners() {
        var keys = new HashMap<String, String>();
        keys.put(JavacNames.BIG_INTEGER, "IntegerType");
        keys.put(JavacNames.STRING, "StringType");
        keys.put(JavacNames.JULC_LIST, "ListType");
        keys.put(JavacNames.UTIL_LIST, "ListType");
        keys.put(JavacNames.JULC_MAP, "MapType");
        keys.put(JavacNames.UTIL_MAP, "MapType");
        keys.put(JavacNames.JULC_ARRAY, "ArrayType");
        keys.put(JavacNames.OPTIONAL, "OptionalType");
        keys.put(LedgerLayouts.PACKAGE + ".Value", VALUE);
        return Map.copyOf(keys);
    }

    /** The key of the methods {@code owner} declares, or null. */
    static String of(String owner) {
        return BY_OWNER.get(owner);
    }

    /** The key a value of {@code type} dispatches on, which a receiver must agree with: its type's representation. */
    static String of(PirType type) {
        return type.getClass().getSimpleName();
    }

    /** Whether a receiver of {@code type} has the methods of {@code key}; a class can extend an API interface. */
    static boolean accepts(String key, PirType type) {
        return key.equals(VALUE) ? type instanceof PirType.RecordType record && record.name().equals(VALUE)
                : key.equals(of(type));
    }
}
