package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.TermExtractor;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Fluent wrapper around an evaluated UPLC {@link Term} for the {@code call()} API.
 * <p>
 * Usage:
 * <pre>{@code
 * var eval = JulcEval.forSource("...");
 * BigInteger result = eval.call("doubleIt", 21).asInteger();
 * }</pre>
 */
public final class CallResult {

    private final Term term;

    CallResult(Term term) {
        this.term = term;
    }

    /** Extract the result as a BigInteger. */
    public BigInteger asInteger() {
        return TermExtractor.extractInteger(term);
    }

    /** Extract the result as a long. */
    public long asLong() {
        return TermExtractor.extractInteger(term).longValueExact();
    }

    /** Extract the result as an int. */
    public int asInt() {
        return TermExtractor.extractInteger(term).intValueExact();
    }

    /** Extract the result as a byte array. */
    public byte[] asByteString() {
        return TermExtractor.extractByteString(term);
    }

    /** Extract the result as a boolean. */
    public boolean asBoolean() {
        return TermExtractor.extractBoolean(term);
    }

    /** Extract the result as a String. */
    public String asString() {
        return TermExtractor.extractString(term);
    }

    /** Extract the result as PlutusData. */
    public PlutusData asData() {
        return TermExtractor.extractData(term);
    }

    /** Extract the result as an Optional. */
    public Optional<PlutusData> asOptional() {
        return TermExtractor.extractOptional(term);
    }

    /** Extract the result as a list of PlutusData. */
    public List<PlutusData> asList() {
        return TermExtractor.extractList(term);
    }

    /**
     * Extract the result as the given type.
     * Supports ledger types with {@code fromPlutusData} and all primitive types.
     */
    public <T> T as(Class<T> type) {
        return ResultConverter.convert(term, type);
    }

    /** Auto-detect the result type. */
    public Object auto() {
        return TermExtractor.extract(term);
    }

    /** Access the raw UPLC term. */
    public Term rawTerm() {
        return term;
    }
}
