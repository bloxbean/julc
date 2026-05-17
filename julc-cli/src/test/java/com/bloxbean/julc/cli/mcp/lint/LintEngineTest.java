package com.bloxbean.julc.cli.mcp.lint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LintEngineTest {

    private final LintEngine engine = new LintEngine();

    @Test
    void clean_validator_produces_no_findings() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class Clean {
                    record D() {}
                    record R() {}
                    @Entrypoint
                    public static boolean validate(D d, R r, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        assertTrue(engine.lint(src).isEmpty(),
                "clean validator should produce no findings");
    }

    @Test
    void flags_optional_mkSome_call() {
        String src = """
                import java.util.Optional;
                public class Bad {
                    static Optional<Integer> wrap(int x) {
                        return Optional.mkSome(x);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertEquals(1, findings.size(), "should flag one mkSome call: " + findings);
        var f = findings.get(0);
        assertEquals("error", f.level());
        assertEquals("JULC-LINT-OPTIONAL-API", f.ruleId());
        assertTrue(f.suggestion().contains("Optional.of(x)"),
                "suggestion must point to Optional.of(): " + f.suggestion());
    }

    @Test
    void flags_optional_mkNone_call() {
        String src = """
                public class Bad {
                    static Object none() { return Optional.mkNone(); }
                }
                """;
        var findings = engine.lint(src);
        assertEquals(1, findings.size(), "should flag mkNone");
        assertTrue(findings.get(0).suggestion().contains("Optional.empty()"));
    }

    @Test
    void flags_switch_field_shadow() {
        String src = """
                import java.math.BigInteger;
                public class V {
                    sealed interface Bound permits Finite, Inf {}
                    record Finite(BigInteger time) implements Bound {}
                    record Inf() implements Bound {}

                    static boolean check(Bound bound, BigInteger time) {
                        return switch (bound) {
                            case Finite f -> f.time().compareTo(time) > 0;
                            case Inf i -> false;
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-SWITCH-SHADOW".equals(f.ruleId())
                        && "JULC0021".equals(f.diagnostic())),
                "expected canonical switch-field-shadow finding: " + findings);
    }

    @Test
    void switch_field_shadow_rule_allows_distinct_parameter_name() {
        String src = """
                import java.math.BigInteger;
                public class V {
                    sealed interface Bound permits Finite, Inf {}
                    record Finite(BigInteger time) implements Bound {}
                    record Inf() implements Bound {}

                    static boolean check(Bound bound, BigInteger point) {
                        return switch (bound) {
                            case Finite f -> f.time().compareTo(point) > 0;
                            case Inf i -> false;
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-SWITCH-SHADOW".equals(f.ruleId())),
                "distinct parameter name should not produce shadow finding: " + findings);
    }

    @Test
    void flags_imported_ledger_switch_field_shadow() {
        String src = """
                import com.bloxbean.cardano.julc.ledger.IntervalBound;
                import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
                import java.math.BigInteger;
                public class V {
                    static boolean check(IntervalBound bound, BigInteger time) {
                        return switch (bound.boundType()) {
                            case IntervalBoundType.Finite f -> f.time().compareTo(time) > 0;
                            case IntervalBoundType.NegInf ignored -> true;
                            case IntervalBoundType.PosInf ignored -> false;
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-SWITCH-SHADOW".equals(f.ruleId())
                        && "JULC0021".equals(f.diagnostic())),
                "expected imported IntervalBoundType.Finite field-shadow finding: " + findings);
    }

    @Test
    void detects_switch_binding_named_after_param() {
        // Construct a case binding name that matches the param name.
        String src = """
                public class V {
                    sealed interface Box permits Some {}
                    record Some(String inner) implements Box {}

                    static String unwrap(Box box, String inner) {
                        return switch (box) {
                            case Some inner -> inner.inner();
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-SWITCH-SHADOW".equals(f.ruleId())),
                "expected switch-shadow finding: " + findings);
    }

    @Test
    void flags_raw_constrdata_construction() {
        String src = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import java.util.List;

                public class Bad {
                    static PlutusData mk() {
                        return new PlutusData.ConstrData(0, List.of());
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-RAW-PLUTUSDATA".equals(f.ruleId())),
                "expected raw-PlutusData finding: " + findings);
    }

    @Test
    void param_biginteger_is_supported_and_not_flagged() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import java.math.BigInteger;

                public class V {
                    @Param static BigInteger maxAmount;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.isEmpty(),
                "@Param BigInteger is supported and should not produce lint findings: " + findings);
    }

    @Test
    void flags_double_hash() {
        String src = """
                public class V {
                    static byte[] go(Object pkh) {
                        return ((com.bloxbean.cardano.julc.ledger.PubKeyHash) pkh).hash().hash();
                    }
                }
                """;
        // The cast wrapping makes this awkward to parse, but the .hash().hash()
        // pattern still appears.
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-DOUBLE-HASH".equals(f.ruleId())),
                "expected double-hash finding: " + findings);
    }

    @Test
    void malformed_source_returns_empty() {
        // Lint must be best-effort: a parse failure returns no findings, the
        // compile tool will surface the parse error.
        List<LintFinding> findings = engine.lint("class { not valid Java");
        assertTrue(findings.isEmpty());
    }

    @Test
    void rule_count_matches_default_set() {
        assertEquals(15, engine.rules().size(),
                "default rule set should have 15 rules (retired @Param BigInteger false-positive rule)");
    }

    @Test
    void mutable_var_rule_flags_post_decl_assignment() {
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static BigInteger bad() {
                        var x = BigInteger.ZERO;
                        x = x.add(BigInteger.ONE);
                        return x;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-MUTABLE-VAR".equals(f.ruleId())),
                "expected mutable-var finding: " + findings);
    }

    @Test
    void mutable_var_rule_does_not_flag_while_accumulator() {
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static BigInteger sumTo(BigInteger n) {
                        var i = BigInteger.ZERO;
                        var acc = BigInteger.ZERO;
                        while (i.compareTo(n) < 0) {
                            acc = acc.add(i);
                            i = i.add(BigInteger.ONE);
                        }
                        return acc;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-MUTABLE-VAR".equals(f.ruleId())),
                "while-loop accumulator pattern should NOT be flagged: " + findings);
    }

    @Test
    void switch_shadow_rule_does_not_flag_unreferenced_shadow() {
        // Shadow is harmless if the case body never references the ambiguous name.
        String src = """
                public class V {
                    sealed interface Box permits Some {}
                    record Some(String inner) implements Box {}

                    static int check(Box box, String inner) {
                        return switch (box) {
                            case Some inner -> 42;
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-SWITCH-SHADOW".equals(f.ruleId())),
                "unreferenced shadow should not be flagged: " + findings);
    }

    @Test
    void double_hash_rule_handles_parenthesized_chain() {
        String src = """
                public class V {
                    static byte[] go(Object pkh) {
                        return ((com.bloxbean.cardano.julc.ledger.PubKeyHash) pkh).hash().hash();
                    }
                }
                """;
        // The cast adds a layer; verify the rule still fires.
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-DOUBLE-HASH".equals(f.ruleId())),
                "expected double-hash on cast-wrapped chain: " + findings);
    }

    @Test
    void raw_plutusdata_rule_catches_intdata_construction() {
        // Regression: codex-review finding 3 — the rule used to look for
        // IntegerData (no such type) and miss IntData (the real subtype).
        String src = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import java.math.BigInteger;

                public class Bad {
                    static PlutusData mk() {
                        return new PlutusData.IntData(BigInteger.ZERO);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-RAW-PLUTUSDATA".equals(f.ruleId())),
                "expected raw-PlutusData finding for IntData: " + findings);
    }

    @Test
    void raw_plutusdata_rule_catches_direct_imported_constrdata() {
        String src = """
                import com.bloxbean.cardano.julc.core.PlutusData.ConstrData;
                import java.util.List;

                public class Bad {
                    static Object mk() { return new ConstrData(0, List.of()); }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-RAW-PLUTUSDATA".equals(f.ruleId())),
                "direct-imported ConstrData should be flagged: " + findings);
    }

    // ------------------------------------------------------------
    // Phase E rules
    // ------------------------------------------------------------

    @Test
    void return_in_loop_rule_flags_while() {
        String src = """
                public class V {
                    public static boolean has(int[] xs, int target) {
                        int i = 0;
                        while (i < xs.length) {
                            if (xs[i] == target) return true;
                            i = i + 1;
                        }
                        return false;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-RETURN-IN-LOOP".equals(f.ruleId())),
                "expected return-in-loop finding: " + findings);
    }

    @Test
    void return_in_loop_rule_does_not_flag_post_loop_return() {
        String src = """
                public class V {
                    public static int sum(int n) {
                        int i = 0;
                        int acc = 0;
                        while (i < n) {
                            acc = acc + i;
                            i = i + 1;
                        }
                        return acc;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-RETURN-IN-LOOP".equals(f.ruleId())),
                "post-loop return should NOT be flagged: " + findings);
    }

    @Test
    void return_in_loop_rule_does_not_flag_return_inside_lambda_in_loop() {
        // Return inside a lambda body (which itself is inside a loop) targets
        // the lambda, not the outer method — so the rule must NOT fire.
        String src = """
                import java.util.function.Predicate;
                public class V {
                    public static Predicate<Integer> mk() {
                        Predicate<Integer> p = x -> { return x > 0; };
                        return p;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-RETURN-IN-LOOP".equals(f.ruleId())),
                "return inside a lambda should NOT be flagged as in-loop: " + findings);
    }

    @Test
    void uninitialized_var_rule_flags_bare_local() {
        String src = """
                import java.math.BigInteger;
                public class V {
                    public static BigInteger bad() {
                        BigInteger x;
                        x = BigInteger.ZERO;
                        return x;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-UNINITIALIZED-VAR".equals(f.ruleId())),
                "expected uninitialized-var finding: " + findings);
    }

    @Test
    void uninitialized_var_rule_does_not_flag_initialized_local() {
        String src = """
                import java.math.BigInteger;
                public class V {
                    public static BigInteger ok() {
                        var x = BigInteger.ZERO;
                        return x;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-UNINITIALIZED-VAR".equals(f.ruleId())),
                "initialized local should NOT be flagged: " + findings);
    }

    @Test
    void uninitialized_var_rule_does_not_flag_enhanced_for_variable() {
        String src = """
                import java.math.BigInteger;
                import java.util.List;
                public class V {
                    public static BigInteger sum(List<BigInteger> xs) {
                        var total = BigInteger.ZERO;
                        for (var x : xs) {
                            total = total.add(x);
                        }
                        return total;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-UNINITIALIZED-VAR".equals(f.ruleId())),
                "enhanced-for loop variable should NOT be flagged as uninitialized: " + findings);
    }

    @Test
    void banned_param_type_rule_flags_bytesdata() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                public class V {
                    @Param static PlutusData.BytesData salt;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-BANNED-PARAM-TYPE".equals(f.ruleId())),
                "expected banned-param-type finding: " + findings);
    }

    @Test
    void banned_param_type_rule_flags_direct_imported_mapdata() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData.MapData;
                public class V {
                    @Param static MapData m;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-BANNED-PARAM-TYPE".equals(f.ruleId())),
                "expected banned-param-type finding for direct-imported MapData: " + findings);
    }

    @Test
    void banned_param_type_rule_flags_listdata() {
        // codex-review finding 4: ListData was missed by the lint.
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                public class V {
                    @Param static PlutusData.ListData xs;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-BANNED-PARAM-TYPE".equals(f.ruleId())),
                "expected banned-param-type finding for ListData: " + findings);
    }

    @Test
    void banned_param_type_rule_flags_intdata() {
        // codex-review finding 4: IntData was missed by the lint.
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                public class V {
                    @Param static PlutusData.IntData n;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-BANNED-PARAM-TYPE".equals(f.ruleId())),
                "expected banned-param-type finding for IntData: " + findings);
    }

    @Test
    void banned_param_type_rule_does_not_flag_byte_array() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                public class V {
                    @Param static byte[] salt;
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-BANNED-PARAM-TYPE".equals(f.ruleId())),
                "byte[] @Param should NOT be flagged: " + findings);
    }

    @Test
    void entrypoint_biginteger_param_rule_flags_direct_param() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import java.math.BigInteger;
                public class V {
                    @Entrypoint
                    public static boolean validate(BigInteger amount, Object r, Object ctx) {
                        return true;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-ENTRYPOINT-BIGINT-PARAM".equals(f.ruleId())),
                "expected entrypoint-bigint-param finding: " + findings);
    }

    @Test
    void entrypoint_biginteger_param_rule_does_not_flag_record_param() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import java.math.BigInteger;
                public class V {
                    record D(BigInteger amount) {}
                    @Entrypoint
                    public static boolean validate(D d, Object r, Object ctx) {
                        return true;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-ENTRYPOINT-BIGINT-PARAM".equals(f.ruleId())),
                "record-wrapped BigInteger should NOT be flagged: " + findings);
    }

    @Test
    void lambda_apply_rule_flags_stored_lambda_invoked() {
        String src = """
                import java.util.function.Function;
                import java.math.BigInteger;
                public class V {
                    public static BigInteger bad(BigInteger x) {
                        Function<BigInteger, BigInteger> f = a -> a.add(BigInteger.ONE);
                        return f.apply(x);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-LAMBDA-APPLY".equals(f.ruleId())),
                "expected lambda-apply finding: " + findings);
    }

    @Test
    void lambda_apply_rule_does_not_flag_inline_hof() {
        String src = """
                import java.math.BigInteger;
                import java.util.List;
                public class V {
                    public static List<BigInteger> ok(List<BigInteger> xs) {
                        return xs.stream().map(x -> x.add(BigInteger.ONE)).toList();
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-LAMBDA-APPLY".equals(f.ruleId())),
                "inline lambda HOF should NOT be flagged: " + findings);
    }

    @Test
    void tuple2_switch_rule_flags_record_pattern() {
        String src = """
                public class V {
                    public static int bad(Object t) {
                        return switch (t) {
                            case Tuple2(Integer a, Integer b) -> a + b;
                            default -> 0;
                        };
                    }
                    record Tuple2<A, B>(A first, B second) {}
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-TUPLE-SWITCH".equals(f.ruleId())),
                "expected tuple-switch finding: " + findings);
    }

    @Test
    void tuple2_switch_rule_flags_type_pattern() {
        String src = """
                public class V {
                    public static int bad(Object t) {
                        return switch (t) {
                            case Tuple3 tt -> 1;
                            default -> 0;
                        };
                    }
                    record Tuple3<A, B, C>(A first, B second, C third) {}
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-TUPLE-SWITCH".equals(f.ruleId())),
                "expected tuple-switch finding for type-pattern: " + findings);
    }

    @Test
    void tuple2_switch_rule_does_not_flag_sealed_interface() {
        String src = """
                public class V {
                    sealed interface Shape permits Circle, Square {}
                    record Circle(int r) implements Shape {}
                    record Square(int s) implements Shape {}

                    public static int area(Shape sh) {
                        return switch (sh) {
                            case Circle c -> c.r() * c.r() * 3;
                            case Square sq -> sq.s() * sq.s();
                        };
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-TUPLE-SWITCH".equals(f.ruleId())),
                "sealed interface switch should NOT be flagged: " + findings);
    }

    @Test
    void bytestringlib_offchain_rule_flags_zeros_call() {
        String src = """
                public class V {
                    public static byte[] mk() {
                        return ByteStringLib.zeros(32);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-BYTESTRINGLIB-OFFCHAIN".equals(f.ruleId())),
                "expected bytestringlib-offchain finding: " + findings);
    }

    @Test
    void bytestringlib_offchain_rule_does_not_flag_safe_methods() {
        // `take` is a safe ByteStringLib method; only zeros/empty/integerToByteString/serialiseData fail.
        String src = """
                public class V {
                    public static byte[] mk(byte[] xs) {
                        return ByteStringLib.take(xs, 4);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-BYTESTRINGLIB-OFFCHAIN".equals(f.ruleId())),
                "ByteStringLib.take should NOT be flagged: " + findings);
    }

    @Test
    void mkcons_pair_list_rule_flags_mixed_method() {
        // Method that uses mkNilData() AND pair operations is suspicious.
        String src = """
                public class V {
                    public static Object bad(Object pair) {
                        var nil = Builtins.mkNilData();
                        var k = Builtins.fstPair(pair);
                        return Builtins.mkCons(pair, nil);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-MKCONS-PAIR-LIST".equals(f.ruleId())),
                "expected mkcons-pair-list finding: " + findings);
    }

    @Test
    void mkcons_pair_list_rule_does_not_flag_simple_data_list() {
        // Only mkNilData(), no pair ops — fine.
        String src = """
                public class V {
                    public static Object ok() {
                        var nil = Builtins.mkNilData();
                        return Builtins.mkCons(Builtins.iData(java.math.BigInteger.ONE), nil);
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-MKCONS-PAIR-LIST".equals(f.ruleId())),
                "data-only list should NOT be flagged: " + findings);
    }

    @Test
    void map_return_type_rule_flags_map_head_chain() {
        String src = """
                import java.math.BigInteger;
                import java.util.List;
                public class V {
                    public static Object pickFirst(List<BigInteger> xs) {
                        return xs.map(x -> x.add(BigInteger.ONE)).head();
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-MAP-RETURN-TYPE".equals(f.ruleId())),
                "expected map-return-type finding: " + findings);
    }

    @Test
    void map_return_type_rule_does_not_flag_map_alone() {
        // .map(...) on its own (without immediate .head()/.get()) is fine.
        String src = """
                import java.math.BigInteger;
                import java.util.List;
                public class V {
                    public static Object incList(List<BigInteger> xs) {
                        return xs.map(x -> x.add(BigInteger.ONE));
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-MAP-RETURN-TYPE".equals(f.ruleId())),
                "bare .map() should NOT be flagged: " + findings);
    }

    @Test
    void increment_rule_flags_postfix_increment() {
        String src = """
                public class V {
                    public static int bad() {
                        int i = 0;
                        i++;
                        return i;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-INCREMENT".equals(f.ruleId())),
                "expected increment finding: " + findings);
    }

    @Test
    void increment_rule_flags_prefix_decrement() {
        String src = """
                public class V {
                    public static int bad(int i) {
                        return --i;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().anyMatch(f -> "JULC-LINT-INCREMENT".equals(f.ruleId())),
                "expected increment finding for --i: " + findings);
    }

    @Test
    void increment_rule_does_not_flag_unary_minus() {
        String src = """
                public class V {
                    public static int negate(int i) {
                        return -i;
                    }
                }
                """;
        var findings = engine.lint(src);
        assertTrue(findings.stream().noneMatch(f -> "JULC-LINT-INCREMENT".equals(f.ruleId())),
                "unary minus should NOT be flagged: " + findings);
    }
}
