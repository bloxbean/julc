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
import org.julclang.vm.java.CekEvaluationException;
import org.julclang.vm.java.CekMachine;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.java.SteppingEvaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Evaluates one uplc-conformance vector on the Java VM ({@link JavaVmProvider}), as a ledger evaluates a script: the
 * vector's cost model, no tracing.
 *
 * <p><b>The reported outcome</b> follows the reference report (Amaru's {@code tests/conformance/run-haskell.sh}, which
 * runs Plutus' {@code uplc}). The vector's Flat program, the form a script has on chain, is evaluated first when the
 * vector has one, and its result stands if it evaluates successfully. Otherwise (no Flat program, as for a
 * {@code value} constant; a Flat program that does not decode; or one whose evaluation fails) the outcome is the
 * textual program's. Each program is first checked against what the protocol and Plutus versions allow
 * ({@link ProgramValidator}), as the Haskell decoder checks it. The outcome is the result program (the expected text
 * when the result is alpha-equivalent to it, as Amaru reports it), or {@value #PARSE_ERROR}, {@value #OUT_OF_BUDGET} or
 * {@value #EVALUATION_FAILURE}.</p>
 *
 * <p><b>JuLC's own checks</b>, which the site does not make and which are reported as {@link Outcome#problems()}:</p>
 * <ul>
 *   <li>The fallback above would hide a Flat decoder that rejects a valid program. So when the vector says what its
 *       Flat program gives ({@code .flat.expected}: a Flat-encoded result or an error label), the Flat program alone
 *       must give exactly that.</li>
 *   <li>A failed evaluation must be a machine or builtin error ({@link CekEvaluationException}), not a crash of the
 *       VM such as a {@link NullPointerException}, which would otherwise match an expected
 *       {@value #EVALUATION_FAILURE}.</li>
 * </ul>
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
     * @param evaluation the result program or an error label, as reported
     * @param budget     the consumed budget of a successful evaluation, otherwise null
     * @param problems   what JuLC's own checks found; empty when the VM behaved
     */
    record Outcome(String evaluation, ExBudget budget, List<String> problems) {
    }

    /** How one program ended. */
    enum Kind {
        SUCCESS, PARSE_ERROR, OUT_OF_BUDGET, FAILURE;

        String label() {
            return switch (this) {
                case SUCCESS -> "a result";
                case PARSE_ERROR -> UplcEvaluation.PARSE_ERROR;
                case OUT_OF_BUDGET -> UplcEvaluation.OUT_OF_BUDGET;
                case FAILURE -> UplcEvaluation.EVALUATION_FAILURE;
            };
        }
    }

    /**
     * @param kind   how it ended
     * @param result the result program of a successful evaluation
     * @param budget the consumed budget of a successful evaluation
     * @param crash  for a failure that is not a machine or builtin error, what was thrown
     */
    record Run(Kind kind, Program result, ExBudget budget, String crash) {
    }

    private UplcEvaluation() {
    }

    /**
     * @param flatHex      the Flat-encoded program in hex, when the vector has one, decoded with the decoding limits
     *                     of the protocol and Plutus versions; null for a vector with only the textual program
     * @param flatExpected what the Flat program gives ({@code .flat.expected}: a Flat-encoded result in hex, or an
     *                     error label), or null if the vector does not say
     * @param text         the textual program
     * @param expected     the expected output of the vector (a program, or an error label)
     * @param costModel    the cost model of the vector's protocol and Plutus versions, or null for the VM's defaults
     */
    static Outcome evaluate(String flatHex, String flatExpected, String text, String expected, int language,
                            int protocolMajor, long[] costModel) {
        return evaluate(flatHex, flatExpected, text, expected, null, language, protocolMajor, costModel);
    }

    /**
     * @param scenarioEvaluation the scenario's expected evaluation of the vector, or null: when it is
     *                           {@value #OUT_OF_BUDGET}, a Flat program that runs out of the scenario's budget is
     *                           what the scenario expects, whatever {@code .flat.expected} (computed without that
     *                           budget) says
     */
    static Outcome evaluate(String flatHex, String flatExpected, String text, String expected,
                            String scenarioEvaluation, int language, int protocolMajor, long[] costModel) {
        LedgerEvaluationTarget target = target(language, protocolMajor);
        ProtocolFeatureProfile profile = ProtocolFeatureRegistry.resolve(target);
        JavaVmProvider provider = new JavaVmProvider();
        if (costModel != null) {
            provider.setCostModelParams(costModel, target);
        }
        List<String> problems = new ArrayList<>();
        Run reported = null;
        if (flatHex != null) {
            Run flat = run(provider, target, profile, () -> UplcFlatDecoder.decodeProgram(
                    HexFormat.of().parseHex(flatHex.trim()), profile.decodeLimits().toFlatDecodeLimits()));
            addCrash(problems, "Flat", flat);
            boolean budgetDecides = flat.kind() == Kind.OUT_OF_BUDGET && OUT_OF_BUDGET.equals(scenarioEvaluation);
            if (flatExpected != null && !budgetDecides) {
                String problem = flatProblem(flat, flatExpected);
                if (problem != null) {
                    problems.add(problem);
                }
            }
            if (flat.kind() == Kind.SUCCESS) {
                reported = flat;
            }
        }
        if (reported == null) {
            reported = run(provider, target, profile, () -> UplcParser.parseProgram(text));
            addCrash(problems, "text", reported);
        }
        return switch (reported.kind()) {
            case SUCCESS -> new Outcome(output(reported.result(), expected.trim()), reported.budget(), problems);
            case PARSE_ERROR -> new Outcome(PARSE_ERROR, null, problems);
            case OUT_OF_BUDGET -> new Outcome(OUT_OF_BUDGET, null, problems);
            case FAILURE -> new Outcome(EVALUATION_FAILURE, null, problems);
        };
    }

    private static void addCrash(List<String> problems, String form, Run run) {
        if (run.crash() != null) {
            problems.add(crashProblem(form, run.crash()));
        }
    }

    /** @return the problem reported for a failure that is not a machine or builtin error */
    static String crashProblem(String form, String crash) {
        return "the " + form + " program's evaluation failed with " + crash + ", not with a machine or builtin error";
    }

    /** @return the problem reported when the Flat program ends otherwise than {@code .flat.expected} says */
    static String flatKindProblem(Kind actual, Kind expected) {
        return "the Flat program gave " + actual.label() + ", where the vector expects " + expected.label();
    }

    /** @return what differs between the Flat program's run and {@code .flat.expected}, or null */
    static String flatProblem(Run flat, String flatExpected) {
        String expected = flatExpected.trim();
        Kind expectedKind = switch (expected) {
            case PARSE_ERROR -> Kind.PARSE_ERROR;
            case OUT_OF_BUDGET -> Kind.OUT_OF_BUDGET;
            case EVALUATION_FAILURE -> Kind.FAILURE;
            default -> Kind.SUCCESS;
        };
        if (flat.kind() != expectedKind) {
            return flatKindProblem(flat.kind(), expectedKind);
        }
        if (expectedKind != Kind.SUCCESS) {
            return null;
        }
        Program expectedProgram;
        try {
            expectedProgram = UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(expected));
        } catch (RuntimeException e) {
            return "the vector's expected Flat result does not decode: " + e.getMessage();
        }
        if (!alphaEquivalent(expectedProgram, flat.result())) {
            return "the Flat program's result " + UplcPrinter.print(flat.result()) + " differs from the expected "
                    + UplcPrinter.print(expectedProgram);
        }
        return null;
    }

    /** Runs one program; a successful evaluation is the only one with a result and a budget. */
    private static Run run(JavaVmProvider provider, LedgerEvaluationTarget target, ProtocolFeatureProfile profile,
                           Supplier<Program> source) {
        Program program;
        try {
            program = source.get();
            ProgramValidator.validate(program, profile);
        } catch (RuntimeException e) {
            return new Run(Kind.PARSE_ERROR, null, null, null);
        }
        return switch (provider.evaluate(program, target, BUDGET, OPTIONS)) {
            case EvalResult.Success success -> new Run(Kind.SUCCESS,
                    new Program(program.major(), program.minor(), program.patch(), success.resultTerm()),
                    success.consumed(), null);
            case EvalResult.BudgetExhausted exhausted -> new Run(Kind.OUT_OF_BUDGET, null, null, null);
            case EvalResult.Failure failure -> new Run(Kind.FAILURE, null, null,
                    crashOf(provider, program, target, failure));
        };
    }

    /**
     * The evaluation API reports every exception as a failure. Re-runs the program step by step to see what was thrown.
     *
     * @return null for a machine or builtin error, otherwise a description of what was thrown
     */
    static String crashOf(JavaVmProvider provider, Program program, LedgerEvaluationTarget target,
                          EvalResult.Failure failure) {
        SteppingEvaluation stepping = provider.startStepping(program, target, List.of(), BUDGET, OPTIONS);
        CekMachine machine = stepping.machine();
        if (machine == null || stepping.isFinished()) {
            return "no evaluation (" + failure.error() + ")";
        }
        try {
            while (machine.step()) {
                // run to the failing transition
            }
            return "no exception when stepped, though evaluation failed (" + failure.error() + ")";
        } catch (CekEvaluationException e) {
            return null;
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
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
