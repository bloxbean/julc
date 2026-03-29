package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden file tests that verify byte-for-byte identical UPLC output across refactoring.
 * <p>
 * On first run (or when golden files are missing), the test captures the UPLC output.
 * On subsequent runs, it compares against the captured baseline.
 * <p>
 * To regenerate golden files, delete the {@code src/test/resources/golden/} directory and re-run.
 */
class GoldenUplcTest {

    static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();
    static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    @BeforeAll
    static void ensureDir() throws IOException {
        Files.createDirectories(GOLDEN_DIR);
    }

    // --- 1. Simple validator (no loops) ---

    static final String SIMPLE_VALIDATOR = """
            import java.math.BigInteger;

            @Validator
            class LetValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    var sum = redeemer + ctx;
                    return sum == BigInteger.valueOf(100);
                }
            }
            """;

    @Test
    void golden_simpleValidator() throws IOException {
        verifyGolden("simple-validator", new JulcCompiler().compile(SIMPLE_VALIDATOR).program());
    }

    // --- 2. For-each with single accumulator ---

    static final String FOREACH_SINGLE_ACC = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    var txInfo = ctx.txInfo();
                    long count = 0;
                    for (var sig : txInfo.signatories()) {
                        count = count + 1;
                    }
                    return count >= 0;
                }
            }
            """;

    @Test
    void golden_forEachSingleAcc() throws IOException {
        verifyGolden("foreach-single-acc",
                new JulcCompiler(STDLIB::lookup).compile(FOREACH_SINGLE_ACC).program());
    }

    // --- 3. While loop with break ---

    static final String WHILE_BREAK = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long acc = 0;
                    long counter = 5;
                    while (counter > 0) {
                        if (acc > 3) {
                            break;
                        }
                        acc = acc + counter;
                        counter = counter - 1;
                    }
                    return acc > 0;
                }
            }
            """;

    @Test
    void golden_whileBreak() throws IOException {
        verifyGolden("while-break", new JulcCompiler().compile(WHILE_BREAK).program());
    }

    // --- 4. Nested loops (while-in-while) ---

    static final String NESTED_WHILE = """
            import java.math.BigInteger;

            @Validator
            class NestedWhileSum {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger sum = BigInteger.ZERO;
                    BigInteger i = BigInteger.ZERO;
                    while (i < BigInteger.valueOf(3)) {
                        BigInteger j = BigInteger.ZERO;
                        while (j < BigInteger.valueOf(3)) {
                            sum = sum + i * BigInteger.valueOf(10) + j;
                            j = j + BigInteger.ONE;
                        }
                        i = i + BigInteger.ONE;
                    }
                    return sum == BigInteger.valueOf(99);
                }
            }
            """;

    @Test
    void golden_nestedWhile() throws IOException {
        verifyGolden("nested-while", new JulcCompiler().compile(NESTED_WHILE).program());
    }

    // --- 5. HOF lambda (list.map) ---

    static final String HOF_MAP = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.ledger.*;
            import java.util.List;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    List<PlutusData> items = Builtins.unListData(redeemer);
                    List<PlutusData> mapped = items.map((PlutusData x) -> Builtins.iData(Builtins.unIData(x) + 1));
                    return Builtins.unIData(mapped.head()) == 2;
                }
            }
            """;

    @Test
    void golden_hofMap() throws IOException {
        verifyGolden("hof-map", new JulcCompiler(STDLIB::lookup).compile(HOF_MAP).program());
    }

    // --- 6. HOF lambda (list.filter) ---

    static final String HOF_FILTER = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.ledger.*;
            import java.util.List;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    List<PlutusData> items = Builtins.unListData(redeemer);
                    List<PlutusData> filtered = items.filter((PlutusData x) -> Builtins.unIData(x) > 2);
                    return filtered.size() == 1;
                }
            }
            """;

    @Test
    void golden_hofFilter() throws IOException {
        verifyGolden("hof-filter", new JulcCompiler(STDLIB::lookup).compile(HOF_FILTER).program());
    }

    // --- 7. Multi-accumulator while ---

    static final String MULTI_ACC_WHILE = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 10;
                        counter = counter - 1;
                    }
                    return (acc1 + acc2) == 33;
                }
            }
            """;

    @Test
    void golden_multiAccWhile() throws IOException {
        verifyGolden("multi-acc-while", new JulcCompiler().compile(MULTI_ACC_WHILE).program());
    }

    // --- 8. Nested while with self-contained inner loop (no outer accumulator contribution) ---

    static final String NESTED_WHILE_NO_ACC = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long sum = 0;
                    long i = 3;
                    while (i > 0) {
                        long j = 2;
                        while (j > 0) {
                            j = j - 1;
                        }
                        sum = sum + i;
                        i = i - 1;
                    }
                    return sum == 6;
                }
            }
            """;

    @Test
    void golden_nestedWhileNoAcc() throws IOException {
        verifyGolden("nested-while-no-acc", new JulcCompiler().compile(NESTED_WHILE_NO_ACC).program());
    }

    // --- Golden file infrastructure ---

    private void verifyGolden(String name, Program program) throws IOException {
        var flatBytes = UplcFlatEncoder.encodeProgram(program);
        var hexString = HexFormat.of().formatHex(flatBytes);
        var textUplc = UplcPrinter.print(program);

        var hexFile = GOLDEN_DIR.resolve(name + ".flat.hex");
        var textFile = GOLDEN_DIR.resolve(name + ".uplc");

        if (!Files.exists(hexFile)) {
            // First run — capture golden files
            Files.writeString(hexFile, hexString);
            Files.writeString(textFile, textUplc);
            System.out.println("Captured golden: " + name
                    + " (" + flatBytes.length + " bytes FLAT, "
                    + textUplc.length() + " chars UPLC)");
        } else {
            // Subsequent runs — compare
            var expectedHex = Files.readString(hexFile).strip();
            assertEquals(expectedHex, hexString,
                    "FLAT encoding mismatch for " + name
                            + ". Delete src/test/resources/golden/ to regenerate.");

            var expectedText = Files.readString(textFile).strip();
            assertEquals(expectedText, textUplc.strip(),
                    "UPLC text mismatch for " + name);
        }
    }
}
