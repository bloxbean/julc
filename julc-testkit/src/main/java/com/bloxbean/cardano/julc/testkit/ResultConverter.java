package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.TermExtractor;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Converts a UPLC {@link Term} to a declared Java return type.
 */
public final class ResultConverter {

    private ResultConverter() {}

    /**
     * Convert an evaluated UPLC term to the declared return type.
     *
     * @param term       the evaluated result term
     * @param returnType the declared Java return type
     * @param <T>        the return type
     * @return the converted value
     * @throws TermExtractor.ExtractionException if the term cannot be converted
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Term term, Class<T> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }

        // Primitive and boxed types
        if (returnType == BigInteger.class) {
            return (T) TermExtractor.extractInteger(term);
        }
        if (returnType == long.class || returnType == Long.class) {
            return (T) Long.valueOf(TermExtractor.extractInteger(term).longValueExact());
        }
        if (returnType == int.class || returnType == Integer.class) {
            return (T) Integer.valueOf(TermExtractor.extractInteger(term).intValueExact());
        }
        if (returnType == byte[].class) {
            return (T) TermExtractor.extractByteString(term);
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return (T) Boolean.valueOf(TermExtractor.extractBoolean(term));
        }
        if (returnType == String.class) {
            return (T) TermExtractor.extractString(term);
        }

        // PlutusData and subtypes
        if (PlutusData.class.isAssignableFrom(returnType)) {
            return (T) TermExtractor.extractData(term);
        }

        // Optional (erased generics — returns Optional<PlutusData>)
        if (returnType == Optional.class) {
            return (T) TermExtractor.extractOptional(term);
        }

        // List (erased generics — returns List<PlutusData>)
        if (returnType == List.class) {
            return (T) TermExtractor.extractList(term);
        }

        // Term passthrough
        if (returnType == Term.class) {
            return (T) term;
        }

        // Ledger record types with static fromPlutusData(PlutusData) method
        try {
            var fromMethod = returnType.getMethod("fromPlutusData", PlutusData.class);
            if (java.lang.reflect.Modifier.isStatic(fromMethod.getModifiers())) {
                PlutusData data = TermExtractor.extractData(term);
                return (T) fromMethod.invoke(null, data);
            }
        } catch (NoSuchMethodException ignored) {
            // Not a ledger type — fall through
        } catch (ReflectiveOperationException e) {
            throw new TermExtractor.ExtractionException(
                    "Failed to invoke fromPlutusData on " + returnType.getName() + ": " + e.getMessage());
        }

        // Auto-detect fallback for Object
        if (returnType == Object.class) {
            return (T) TermExtractor.extract(term);
        }

        throw new TermExtractor.ExtractionException(
                "Unsupported return type: " + returnType.getName());
    }
}
