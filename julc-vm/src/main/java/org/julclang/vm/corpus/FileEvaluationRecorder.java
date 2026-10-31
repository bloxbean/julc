package org.julclang.vm.corpus;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@link EvaluationRecorder} that writes under a corpus directory:
 * <ul>
 *   <li>{@code data/<digest>.cbor}: one argument's CBOR, named by its digest;</li>
 *   <li>{@code data/<digest>.costs}: one cost-model parameter list, a value per line, named by its digest;</li>
 *   <li>{@code evaluations/<key>/evaluation.txt}: the evaluation, named by the digest of this text (format 2:
 *       program FLAT digest and size, provider, target, the effective cost configuration ({@code config: unknown},
 *       {@code config: default}, or a {@code config: <language> <major>.<minor> <costs digest>} line per configured
 *       language), budget limit, argument digests, outcome, result FLAT digest, trace count and digest,
 *       failure-message digest, consumed budget);</li>
 *   <li>{@code evaluations/<key>/origin-<digest>}: a test that evaluated it.</li>
 * </ul>
 * Loaded by name only; never referred to from an evaluation path.
 */
public final class FileEvaluationRecorder implements EvaluationRecorder {

    private final Path root;

    public FileEvaluationRecorder(String root) {
        this.root = Path.of(root).toAbsolutePath();
    }

    @Override
    public void record(String provider, String target, List<CostConfiguration> configuration, Program program,
                       List<PlutusData> args, ExBudget budget, EvalResult result) {
        try {
            var text = new StringBuilder();
            text.append("format: 2\n");
            text.append("program: ").append(programDigest(program)).append('\n');
            text.append("provider: ").append(provider).append('\n');
            text.append("target: ").append(target).append('\n');
            if (configuration == null) {
                text.append("config: unknown\n");
            } else if (configuration.isEmpty()) {
                text.append("config: default\n");
            } else {
                for (var c : configuration) {
                    var values = new StringBuilder();
                    for (long value : c.values()) values.append(value).append('\n');
                    String digest = CorpusFiles.sha256(values.toString());
                    CorpusFiles.writeOnce(root.resolve("data").resolve(digest + ".costs"), values.toString());
                    text.append("config: ").append(c.language()).append(' ').append(c.protocolMajor()).append('.')
                            .append(c.protocolMinor()).append(' ').append(digest).append('\n');
                }
            }
            text.append("budget: ").append(budget == null ? "none" : budget.cpuSteps() + " " + budget.memoryUnits()).append('\n');
            for (PlutusData arg : args) {
                byte[] cbor = PlutusDataCborEncoder.encode(arg);
                String digest = CorpusFiles.sha256(cbor);
                CorpusFiles.writeOnce(root.resolve("data").resolve(digest + ".cbor"), cbor);
                text.append("arg: ").append(digest).append('\n');
            }
            text.append("outcome: ").append(result.getClass().getSimpleName()).append('\n');
            if (result instanceof EvalResult.Success success) {
                text.append("value: ").append(termDigest(program, success.resultTerm())).append('\n');
            }
            if (result instanceof EvalResult.Failure failure) {
                text.append("error: ").append(failureDigest(failure)).append('\n');
            }
            List<String> traces = result.traces();
            text.append("traces: ").append(traces.size()).append(' ')
                    .append(CorpusFiles.sha256(String.join("\n", traces)).substring(0, 16)).append('\n');
            var consumed = result.budgetConsumed();
            text.append("consumed: ").append(consumed.cpuSteps()).append(' ').append(consumed.memoryUnits()).append('\n');
            String record = text.toString();
            Path dir = root.resolve("evaluations").resolve(CorpusFiles.sha256(record));
            CorpusFiles.writeOnce(dir.resolve("evaluation.txt"), record);
            String origin = CorpusFiles.origin();
            CorpusFiles.writeOnce(dir.resolve("origin-" + CorpusFiles.sha256(origin).substring(0, 16)), origin + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("evaluation recorder: " + root, e);
        }
    }

    /**
     * The digest of a failure's message, as recorded ({@code error:}); a replay compares its own failure with this
     * same function (F312).
     */
    public static String failureDigest(EvalResult.Failure failure) {
        return CorpusFiles.sha256(String.valueOf(failure.error())).substring(0, 16);
    }

    /** The program's FLAT digest and size, or {@code unencodable} (a constant FLAT cannot carry, such as a BLS point). */
    static String programDigest(Program program) {
        try {
            byte[] flat = UplcFlatEncoder.encodeProgram(program);
            return CorpusFiles.sha256(flat) + " " + flat.length;
        } catch (RuntimeException e) {
            return "unencodable -";
        }
    }

    /** The FLAT digest of the result term, as a program of the evaluated program's version. */
    static String termDigest(Program program, Term term) {
        try {
            return CorpusFiles.sha256(UplcFlatEncoder.encodeProgram(
                    new Program(program.major(), program.minor(), program.patch(), term)));
        } catch (RuntimeException e) {
            return "unencodable";
        }
    }
}
