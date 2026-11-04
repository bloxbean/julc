package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Address;
import org.julclang.ledger.Committee;
import org.julclang.ledger.Constitution;
import org.julclang.ledger.Credential;
import org.julclang.ledger.DRep;
import org.julclang.ledger.Delegatee;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PlutusDataConvertible;
import org.julclang.ledger.ProposalProcedure;
import org.julclang.ledger.ProtocolVersion;
import org.julclang.ledger.Rational;
import org.julclang.ledger.ScriptInfo;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ledger record and variant, built with {@code new} in a script and compared with what its own {@code toPlutusData}
 * gives on the JVM. That method is the ledger's encoder, which neither frontend reads, so this holds I11 for construction:
 * tags, field order and each field's Data. A record that holds a {@code Value} is left out: {@code new Value} stops, and
 * the ledger's own constructors of one are not on-chain methods.
 */
class Adr068LedgerRecordsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static final List<Class<?>> ROOTS = List.of(Address.class, Committee.class, Constitution.class, Credential.class,
            DRep.class, Delegatee.class, GovernanceAction.class, GovernanceActionId.class, Interval.class, IntervalBound.class,
            IntervalBoundType.class, OutputDatum.class, ProposalProcedure.class, ProtocolVersion.class, Rational.class,
            ScriptInfo.class, ScriptPurpose.class, StakingCredential.class, TxCert.class, TxInInfo.class, TxOut.class,
            TxOutRef.class, Vote.class, Voter.class);

    /** What only a {@code Value} can fill: {@code new Value} stops, and {@code Value.zero()} is no on-chain method. */
    private static final Set<Class<?>> NEEDS_A_VALUE = Set.of(TxOut.class, TxInInfo.class);

    private static final Set<String> HASHES = Set.of("PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId");

    private static List<Class<?>> records() {
        var records = new ArrayList<Class<?>>();
        for (var root : ROOTS) {
            if (root.isRecord()) records.add(root);
            if (root.isSealed()) {
                for (var variant : root.getPermittedSubclasses()) if (variant.isRecord()) records.add(variant);
            }
        }
        return records;
    }

    private static String name(Class<?> type) {
        return type.getCanonicalName().substring(type.getPackageName().length() + 1);
    }

    /** A value of {@code type}, as source. A sum takes the variant {@code choice} picks. */
    private static String sample(Type type, int choice) {
        if (type instanceof ParameterizedType parameterized) {
            var arguments = parameterized.getActualTypeArguments();
            var raw = (Class<?>) parameterized.getRawType();
            if (raw == Optional.class) return "Optional.of(" + sample(arguments[0], choice) + ")";
            if (raw == JulcList.class) return "JulcList.of(" + sample(arguments[0], choice) + ")";
            if (raw == JulcMap.class) {
                return "JulcMap.<" + written(arguments[0]) + ", " + written(arguments[1]) + ">empty().insert("
                        + sample(arguments[0], choice) + ", " + sample(arguments[1], choice) + ")";
            }
        }
        var raw = (Class<?>) type;
        if (raw == BigInteger.class) return "BigInteger.valueOf(" + (7 + choice) + ")";
        if (raw == boolean.class || raw == Boolean.class) return choice % 2 == 0 ? "true" : "false";
        if (raw == byte[].class) return "new byte[]{1, 2}";
        if (raw == long.class || raw == int.class) return "9";
        if (PlutusData.class.isAssignableFrom(raw)) return "PlutusData.UNIT";
        if (HASHES.contains(raw.getSimpleName())) return "new " + name(raw) + "(new byte[]{3, 4})";
        if (raw.isSealed() && raw.isInterface()) {
            var variants = raw.getPermittedSubclasses();
            return sample(variants[choice % variants.length], choice);
        }
        if (raw.isRecord()) {
            var arguments = new ArrayList<String>();
            for (RecordComponent component : raw.getRecordComponents()) arguments.add(sample(component.getGenericType(), choice));
            return "new " + name(raw) + "(" + String.join(", ", arguments) + ")";
        }
        throw new IllegalArgumentException("No sample of " + type);
    }

    private static String written(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            var arguments = Stream.of(parameterized.getActualTypeArguments()).map(Adr068LedgerRecordsHarnessTest::written).toList();
            return written(parameterized.getRawType()) + "<" + String.join(", ", arguments) + ">";
        }
        var raw = (Class<?>) type;
        return raw == byte[].class ? "byte[]" : raw.getPackageName().startsWith("org.julclang.ledger") ? name(raw) : raw.getSimpleName();
    }

    private static boolean holdsValue(Class<?> type) {
        return NEEDS_A_VALUE.contains(type) || type.isRecord() && Stream.of(type.getRecordComponents())
                .anyMatch(component -> component.getType() == Value.class || NEEDS_A_VALUE.contains(component.getType()));
    }

    private static String program(String creation) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.ledger.*;

                public class Case {
                    public static PlutusData m(long unused) {
                        var value = %s;
                        return value.toPlutusData();
                    }
                }
                """.formatted(creation);
    }

    @TestFactory
    Stream<DynamicTest> everyRecordAndVariant() {
        var built = records().stream().filter(type -> !holdsValue(type)).toList();
        assertTrue(built.size() >= 40, () -> "too few records: " + built.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));
        return built.stream().flatMap(type -> Stream.of(0, 1).map(choice -> DynamicTest.dynamicTest(name(type) + " " + choice, () -> {
            var report = RUNNER.run(new DiffCase("ledger", program(sample(type, choice)), "Case", "m", List.of(new PlutusData.IntData(BigInteger.ZERO)),
                    TagTable.EMPTY, null));
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            assertEquals(4, report.results().size());
        })));
    }

    /** The interface every ledger value of this test answers through. */
    @TestFactory
    Stream<DynamicTest> theRootsAreConvertible() {
        return records().stream().map(type -> DynamicTest.dynamicTest(name(type),
                () -> assertTrue(PlutusDataConvertible.class.isAssignableFrom(type), type::getName)));
    }
}
