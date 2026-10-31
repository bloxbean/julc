package org.julclang.wasm;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.frontend.javac.JavacEnvironment;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * ADR-068 M1 host smoke: the javac gate, enforced, in an image built from julc-wasm's class path
 * ({@code :julc-wasm:wasmGateProbe} builds it as a Web Image and runs it under Node; {@link JavacGateProbeTest} runs
 * it on the JVM and pins the same lines). It prints the javac environment, accepts a stdlib-using and a
 * {@code @RequiresSigner} validator, and rejects JuLC's dialect.
 */
public final class JavacGateProbe {
    static final String STDLIB = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.stdlib.lib.MathLib;
            import org.julclang.stdlib.lib.ContextsLib;

            @SpendingValidator
            class FeeGate {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).compareTo(BigInteger.TWO) > 0
                            && ContextsLib.signedBy(ctx.txInfo(), ctx.txInfo().id().hash());
                }
            }
            """;
    static final String SIGNER = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.verification.annotation.RequiresSigner;

            @SpendingValidator
            @RequiresSigner("datum.owner")
            class OwnerGate {
                record OwnerDatum(PubKeyHash owner) {}

                @Entrypoint
                static boolean validate(OwnerDatum datum, PlutusData redeemer, ScriptContext ctx) {
                    return ctx.txInfo().signatories().contains(datum.owner());
                }
            }
            """;
    static final String DIALECT = STDLIB.replace(
            "MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).compareTo(BigInteger.TWO) > 0", "ctx.txInfo().fee() > 2");

    /** The probe's deterministic lines: the environment, two acceptances with their FLAT digests, the rejection. */
    static List<String> run() throws Exception {
        var lines = new ArrayList<String>();
        var env = JavacEnvironment.current();
        lines.add("environment supported=" + env.supported() + " platform=" + env.platformDigest() + " stubs="
                + env.stubsDigest());
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE));
        var pool = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        for (String source : new String[]{STDLIB, SIGNER}) {
            var result = compiler.compile(source, LibrarySourceResolver.resolve(source, pool));
            byte[] flat = UplcFlatEncoder.encodeProgram(result.program());
            lines.add("accepted " + (source.contains("OwnerGate") ? "OwnerGate" : "FeeGate") + " " + flat.length
                    + " bytes flat-sha256 " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(flat)));
        }
        try {
            compiler.compile(DIALECT, LibrarySourceResolver.resolve(DIALECT, pool));
            lines.add("UNEXPECTED: dialect accepted");
        } catch (CompilerException e) {
            var d = e.diagnostics().getFirst();
            lines.add("rejected " + d.code() + " " + d.fileName() + ":" + d.line() + ":" + d.column() + " "
                    + d.message().replace('\n', ' '));
        }
        return lines;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("javac " + JavacFrontend.javacVersion());
        run().forEach(System.out::println);
        System.out.printf("probe %.0f ms%n", (System.nanoTime() - t0) / 1e6);
    }
}
