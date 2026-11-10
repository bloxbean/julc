package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.text.UplcParser;
import org.julclang.core.text.UplcPrinter;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProgramValidator;
import org.julclang.vm.ProtocolFeatureProfile;
import org.julclang.vm.ProtocolFeatureRegistry;
import org.julclang.vm.ProtocolVersion;
import org.julclang.vm.java.JavaVmProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Evaluates one uplc-conformance vector on the Java VM ({@link JavaVmProvider}), as a ledger evaluates a script: the
 * vector's cost model, no tracing.
 *
 * <p>The order follows the reference report (Amaru's {@code tests/conformance/run-haskell.sh}, which runs Plutus'
 * {@code uplc}). The vector's Flat program, the form a script has on chain, is evaluated first when the vector has one,
 * and its result stands if it evaluates successfully. Otherwise (no Flat program, as for a {@code value} constant; a Flat
 * program that does not decode; or one whose evaluation fails) the outcome is the textual program's. Each program is
 * first checked against what the protocol and Plutus versions allow ({@link ProgramValidator}), as the Haskell decoder
 * checks it.</p>
 *
 * <p>The outcome is what the scenario reports: the result program (the expected text when the result is
 * alpha-equivalent to it, as Amaru reports it), or {@value #PARSE_ERROR} for a program that does not parse or decode or
 * that the protocol and Plutus versions do not allow, {@value #OUT_OF_BUDGET}, or {@value #EVALUATION_FAILURE}.</p>
 */
final class UplcEvaluation {

    static final String PARSE_ERROR = "parse/decode error";
    static final String OUT_OF_BUDGET = "out of budget";
    static final String EVALUATION_FAILURE = "evaluation failure";

    /** The scenario's budget: 10B CPU, 14M memory. */
    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    /** A ledger's options: no source map, no tracing. */
    static final EvalOptions OPTIONS = new EvalOptions(null, false, false);

    /**
     * @param evaluation the result program or an error label
     * @param budget     the consumed budget of a successful evaluation, otherwise null
     */
    record Outcome(String evaluation, ExBudget budget) {
    }

    private UplcEvaluation() {
    }

    /**
     * @param flatHex   the Flat-encoded program in hex, when the vector has one, decoded with the decoding limits of the
     *                  protocol and Plutus versions; null for a vector with only the textual program
     * @param text      the textual program
     * @param expected  the expected output (a program, or an error label)
     * @param costModel the cost model of the vector's protocol and Plutus versions, or null for the VM's defaults
     */
    static Outcome evaluate(String flatHex, String text, String expected, int language, int protocolMajor,
                            long[] costModel) {
        LedgerEvaluationTarget target = target(language, protocolMajor);
        ProtocolFeatureProfile profile = ProtocolFeatureRegistry.resolve(target);
        JavaVmProvider provider = new JavaVmProvider();
        if (costModel != null) {
            provider.setCostModelParams(costModel, target);
        }
        if (flatHex != null) {
            Outcome flat = run(provider, target, profile, expected, () -> UplcFlatDecoder.decodeProgram(
                    HexFormat.of().parseHex(flatHex.trim()), profile.decodeLimits().toFlatDecodeLimits()));
            if (flat.budget() != null) {
                return flat;
            }
        }
        return run(provider, target, profile, expected, () -> UplcParser.parseProgram(text));
    }

    /** @return the outcome of one program; a successful evaluation is the only one with a budget */
    private static Outcome run(JavaVmProvider provider, LedgerEvaluationTarget target, ProtocolFeatureProfile profile,
                               String expected, Supplier<Program> source) {
        Program program;
        try {
            program = source.get();
            ProgramValidator.validate(program, profile);
        } catch (RuntimeException e) {
            return new Outcome(PARSE_ERROR, null);
        }
        return switch (provider.evaluate(program, target, BUDGET, OPTIONS)) {
            case EvalResult.Success success -> new Outcome(
                    output(new Program(program.major(), program.minor(), program.patch(), success.resultTerm()),
                            expected.trim()),
                    success.consumed());
            case EvalResult.BudgetExhausted exhausted -> new Outcome(OUT_OF_BUDGET, null);
            case EvalResult.Failure failure -> new Outcome(EVALUATION_FAILURE, null);
        };
    }

    private static String output(Program result, String expected) {
        try {
            Program expectedProgram = UplcParser.parseProgram(expected);
            if (alphaEquivalent(expectedProgram, result)) {
                return expected;
            }
        } catch (RuntimeException e) {
            // the expected output is an error label: report the VM's result as printed
        }
        return UplcPrinter.print(result);
    }

    /** @return true when the programs differ only in the names of bound variables */
    static boolean alphaEquivalent(Program a, Program b) {
        return a.major() == b.major() && a.minor() == b.minor() && a.patch() == b.patch()
                && UplcPrinter.print(canonical(a.term(), 0)).equals(UplcPrinter.print(canonical(b.term(), 0)));
    }

    /** Renames every binder by its depth, so alpha-equivalent terms print the same. */
    private static Term canonical(Term term, int depth) {
        return switch (term) {
            case Term.Var v -> {
                int index = v.name().index();
                String name = index > 0 && index <= depth ? "v" + (depth - index) : "free_" + v.name().name();
                yield new Term.Var(new NamedDeBruijn(name, index));
            }
            case Term.Lam l -> new Term.Lam("v" + depth, canonical(l.body(), depth + 1));
            case Term.Apply a -> new Term.Apply(canonical(a.function(), depth), canonical(a.argument(), depth));
            case Term.Force f -> new Term.Force(canonical(f.term(), depth));
            case Term.Delay d -> new Term.Delay(canonical(d.term(), depth));
            case Term.Constr c -> new Term.Constr(c.tag(), canonical(c.fields(), depth));
            case Term.Case c -> new Term.Case(canonical(c.scrutinee(), depth), canonical(c.branches(), depth));
            case Term.Const c -> c;
            case Term.Builtin b -> b;
            case Term.Error e -> e;
        };
    }

    private static List<Term> canonical(List<Term> terms, int depth) {
        return terms.stream().map(t -> canonical(t, depth)).toList();
    }

    /** @return a cost model file of the corpus: a JSON array of integers in the ledger's order */
    static long[] costModel(Path file) {
        try {
            JsonNode values = NdcReports.JSON.readTree(file.toFile());
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalArgumentException(file + " is not a JSON array of cost model parameters");
            }
            long[] costModel = new long[values.size()];
            for (int i = 0; i < costModel.length; i++) {
                if (!values.get(i).isIntegralNumber()) {
                    throw new IllegalArgumentException(file + ": parameter " + i + " is not an integer");
                }
                costModel[i] = values.get(i).asLong();
            }
            return costModel;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** @return the ledger target of a Plutus language (1 to 3) at a major protocol version */
    static LedgerEvaluationTarget target(int language, int protocolMajor) {
        PlutusLanguage plutus = switch (language) {
            case 1 -> PlutusLanguage.PLUTUS_V1;
            case 2 -> PlutusLanguage.PLUTUS_V2;
            case 3 -> PlutusLanguage.PLUTUS_V3;
            default -> throw new IllegalArgumentException("not a Plutus language: " + language);
        };
        return new LedgerEvaluationTarget(plutus, new ProtocolVersion(protocolMajor, 0));
    }
}
