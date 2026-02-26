package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts Java values from evaluated UPLC terms.
 * <p>
 * After evaluating a UPLC program, the result is a {@link Term}. This utility
 * converts result terms back to usable Java types. Handles both raw constants
 * (e.g., {@code Const(IntegerConst(42))}) and Data-wrapped values
 * (e.g., {@code Const(DataConst(IntData(42)))}).
 */
public final class TermExtractor {

    private TermExtractor() {}

    /**
     * Extract a BigInteger from an evaluated term.
     * Handles both {@code Const(IntegerConst)} and {@code Const(DataConst(IntData))}.
     *
     * @throws ExtractionException if the term is not an integer
     */
    public static BigInteger extractInteger(Term term) {
        if (term instanceof Term.Const c) {
            if (c.value() instanceof Constant.IntegerConst ic) {
                return ic.value();
            }
            if (c.value() instanceof Constant.DataConst dc
                    && dc.value() instanceof PlutusData.IntData id) {
                return id.value();
            }
        }
        throw new ExtractionException("Expected integer term, got: " + term);
    }

    /**
     * Extract a byte array from an evaluated term.
     * Handles both {@code Const(ByteStringConst)} and {@code Const(DataConst(BytesData))}.
     *
     * @throws ExtractionException if the term is not a byte string
     */
    public static byte[] extractByteString(Term term) {
        if (term instanceof Term.Const c) {
            if (c.value() instanceof Constant.ByteStringConst bs) {
                return bs.value();
            }
            if (c.value() instanceof Constant.DataConst dc
                    && dc.value() instanceof PlutusData.BytesData bd) {
                return bd.value();
            }
        }
        throw new ExtractionException("Expected byte string term, got: " + term);
    }

    /**
     * Extract a boolean from an evaluated term.
     * Handles {@code Const(BoolConst)} and Plutus Bool convention
     * ({@code Constr(0)} = False, {@code Constr(1)} = True).
     *
     * @throws ExtractionException if the term is not a boolean
     */
    public static boolean extractBoolean(Term term) {
        if (term instanceof Term.Const c) {
            if (c.value() instanceof Constant.BoolConst bc) {
                return bc.value();
            }
            if (c.value() instanceof Constant.DataConst dc
                    && dc.value() instanceof PlutusData.ConstrData cd
                    && cd.fields().isEmpty()) {
                // Plutus Bool: False=Constr(0,[]), True=Constr(1,[])
                return cd.tag() == 1;
            }
        }
        if (term instanceof Term.Constr constr && constr.fields().isEmpty()) {
            // V3 Constr term: tag 0 = False, tag 1 = True
            return constr.tag() == 1;
        }
        throw new ExtractionException("Expected boolean term, got: " + term);
    }

    /**
     * Extract a String from an evaluated term.
     *
     * @throws ExtractionException if the term is not a string
     */
    public static String extractString(Term term) {
        if (term instanceof Term.Const c
                && c.value() instanceof Constant.StringConst sc) {
            return sc.value();
        }
        throw new ExtractionException("Expected string term, got: " + term);
    }

    /**
     * Extract PlutusData from an evaluated term.
     * Handles {@code Const(DataConst)}, as well as unwrapping from raw constants.
     *
     * @throws ExtractionException if the term cannot be converted to PlutusData
     */
    public static PlutusData extractData(Term term) {
        if (term instanceof Term.Const c) {
            if (c.value() instanceof Constant.DataConst dc) {
                return dc.value();
            }
            // Raw constants can be converted to Data
            if (c.value() instanceof Constant.IntegerConst ic) {
                return PlutusData.integer(ic.value());
            }
            if (c.value() instanceof Constant.ByteStringConst bs) {
                return PlutusData.bytes(bs.value());
            }
            if (c.value() instanceof Constant.BoolConst bc) {
                return new PlutusData.ConstrData(bc.value() ? 1 : 0, List.of());
            }
        }
        if (term instanceof Term.Constr constr) {
            var fields = new ArrayList<PlutusData>();
            for (var arg : constr.fields()) {
                fields.add(extractData(arg));
            }
            return new PlutusData.ConstrData((int) constr.tag(), fields);
        }
        throw new ExtractionException("Expected data term, got: " + term);
    }

    /**
     * Extract an Optional value from an evaluated term.
     * Handles Plutus Maybe convention:
     * {@code ConstrData(0, [value])} = Some, {@code ConstrData(1, [])} = None.
     *
     * @throws ExtractionException if the term is not an Optional
     */
    public static Optional<PlutusData> extractOptional(Term term) {
        if (term instanceof Term.Const c
                && c.value() instanceof Constant.DataConst dc
                && dc.value() instanceof PlutusData.ConstrData cd) {
            if (cd.tag() == 0 && cd.fields().size() == 1) {
                return Optional.of(cd.fields().get(0));
            }
            if (cd.tag() == 1 && cd.fields().isEmpty()) {
                return Optional.empty();
            }
        }
        if (term instanceof Term.Constr constr) {
            if (constr.tag() == 0 && constr.fields().size() == 1) {
                return Optional.of(extractData(constr.fields().get(0)));
            }
            if (constr.tag() == 1 && constr.fields().isEmpty()) {
                return Optional.empty();
            }
        }
        throw new ExtractionException("Expected Optional term (Constr 0/1), got: " + term);
    }

    /**
     * Extract a list of PlutusData from an evaluated term.
     *
     * @throws ExtractionException if the term is not a list
     */
    public static List<PlutusData> extractList(Term term) {
        if (term instanceof Term.Const c
                && c.value() instanceof Constant.DataConst dc
                && dc.value() instanceof PlutusData.ListData ld) {
            return ld.items();
        }
        if (term instanceof Term.Const c
                && c.value() instanceof Constant.ListConst lc) {
            var result = new ArrayList<PlutusData>(lc.values().size());
            for (var elem : lc.values()) {
                if (elem instanceof Constant.DataConst dc) {
                    result.add(dc.value());
                } else {
                    result.add(extractData(new Term.Const(elem)));
                }
            }
            return result;
        }
        throw new ExtractionException("Expected list term, got: " + term);
    }

    /**
     * Auto-detect and extract the most appropriate Java type from a term.
     *
     * @return BigInteger, byte[], boolean, String, PlutusData, Optional, or List
     */
    public static Object extract(Term term) {
        if (term instanceof Term.Const c) {
            return switch (c.value()) {
                case Constant.IntegerConst ic -> ic.value();
                case Constant.ByteStringConst bs -> bs.value();
                case Constant.BoolConst bc -> bc.value();
                case Constant.StringConst sc -> sc.value();
                case Constant.UnitConst ignored -> null;
                case Constant.DataConst dc -> dc.value();
                case Constant.ListConst ignored -> extractList(term);
                default -> term;
            };
        }
        if (term instanceof Term.Constr constr) {
            // Try Optional first (Constr 0/1 with 0-1 args)
            if (constr.fields().size() <= 1 && constr.tag() <= 1) {
                try {
                    return extractOptional(term);
                } catch (ExtractionException ignored) {}
            }
            return extractData(term);
        }
        return term;
    }

    /**
     * Extract a value from an EvalResult, throwing if evaluation failed.
     *
     * @throws ExtractionException if the evaluation did not succeed
     */
    public static Term extractResultTerm(EvalResult result) {
        if (result instanceof EvalResult.Success s) {
            return s.resultTerm();
        }
        if (result instanceof EvalResult.Failure f) {
            throw new ExtractionException("Evaluation failed: " + f.error());
        }
        if (result instanceof EvalResult.BudgetExhausted b) {
            throw new ExtractionException("Budget exhausted: " + b.consumed());
        }
        throw new ExtractionException("Unknown eval result: " + result);
    }

    /**
     * Exception thrown when a term cannot be extracted to the expected type.
     */
    public static class ExtractionException extends RuntimeException {
        public ExtractionException(String message) { super(message); }
    }
}
