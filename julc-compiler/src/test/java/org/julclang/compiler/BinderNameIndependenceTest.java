package org.julclang.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.TypePatternExpr;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060 G1, the alpha-renaming oracle. Renaming a source name consistently must not change the
 * program. UPLC FLAT carries de Bruijn indices, so the bytes must be identical, which holds only if
 * no source name can resolve to a compiler-generated binder and no lowering decision depends on
 * the name. Each corpus program has its locals, parameters, lambda parameters and pattern
 * variables renamed, one at a time, to names the compiler used to generate; dedicated validators
 * also rename their helper methods, static fields and {@code @Param}s. NONE and BASELINE must give
 * identical bytes. The PV11 passes count binders by name, so there the bytes must be identical
 * when the new name is not bound anywhere in the linked program; otherwise the fixture inputs
 * must evaluate identically (ADR-060, documented non-goal).
 * <p>
 * The oracle is not vacuous. Run against PR #186's head {@code ef932b21}, 184 renames covering 53
 * of the 72 target names changed the program; against {@code c95ee610}, before #186, 488 renames
 * covering 64 names did. The other 8 ({@code count__pv11_drop}, {@code _body}, {@code result__},
 * {@code __native_scalars}, {@code go__scalars}, {@code __bytes}, {@code $pir$subst$0},
 * {@code $julc$x}) name binders whose scope holds only compiler-built terms, so no source rename can
 * reach them. They stay as targets so that a future lowering placing user code there fails here.
 */
class BinderNameIndependenceTest {
    /** Names the compiler generated before ADR-060, plus the fresh-name spellings of other frontends. */
    static final List<String> TARGETS = List.of(
            "x", "acc", "lst", "go", "h", "x_map", "acc_map", "x_flt", "acc_flt", "go_zip", "go_get", "lst_get",
            "idx_get", "target_c", "list_c", "go_c", "h_c", "acc__f", "lst__f", "go__f", "acc__len", "go_rev",
            "go_crev", "go_take", "go_drop", "list__pv11_drop", "count__pv11_drop", "v__ao", "pol__ao", "tok__ao",
            "__match_data", "__match_pair", "__match_tag", "__match_fields", "__rest_0", "__rest_1", "xs__",
            "loop__forEach__0", "loop__forEach__1", "loop__forEach__2", "loop__while__0", "loop__while__1", "_body", "_u",
            "acc__forEach", "__acc_tuple",
            "_if", "_forEach", "_while", "_nested", "__t", "scriptContextData", "ctxFields__",
            "redeemer__", "redeemer__decoded", "result__", "scriptInfo__", "scriptInfoFields__", "optDatum__",
            "datum__", "datum__decoded", "scriptInfoPair__", "tag__", "__native_scalars", "go__scalars",
            "__integer", "__bytes", "$pir$subst$0", "$julc$x", "ps_ck", "xs_con", "e_con");
    private static final List<OptimizationLevel> LEVELS = List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE,
            OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    /** Validators whose class-level names (helpers, static fields, @Param) are renamed too. */
    static final List<Adr060Corpus.Entry> CLASS_LEVEL = List.of(
            new Adr060Corpus.Entry("class-spend", """
                    import java.math.BigInteger;
                    import org.julclang.core.types.JulcList;
                    @SpendingValidator
                    class Vault {
                        @Param BigInteger limit;
                        @Param PlutusData owner;
                        static final BigInteger fee = BigInteger.TWO;
                        static boolean within(BigInteger amount) { return amount.compareTo(limit) <= 0; }
                        static BigInteger total(JulcList<PlutusData> items) {
                            BigInteger sum = BigInteger.ZERO;
                            for (var item : items) { sum = sum.add(Builtins.unIData(item)); }
                            return sum;
                        }
                        @Entrypoint
                        static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> items = Builtins.unListData(redeemer);
                            BigInteger amount = total(items).add(fee);
                            return within(amount) && Builtins.equalsData(datum, owner)
                                    && items.any(entry -> Builtins.unIData(entry).signum() > 0);
                        }
                    }
                    """, null, List.of()),
            new Adr060Corpus.Entry("class-mint", """
                    import java.math.BigInteger;
                    @MintingValidator
                    class Minter {
                        @Param BigInteger cap;
                        sealed interface Action permits Mint, Burn {}
                        record Mint(BigInteger amount) implements Action {}
                        record Burn(BigInteger amount) implements Action {}
                        static boolean allowed(BigInteger requested) { return requested.compareTo(cap) <= 0; }
                        @Entrypoint
                        static boolean validate(Action action, ScriptContext ctx) {
                            return switch (action) {
                                case Mint m -> allowed(m.amount());
                                case Burn b -> b.amount().signum() > 0;
                            };
                        }
                    }
                    """, null, List.of()),
            new Adr060Corpus.Entry("class-multi", BinderNameIndependenceTest.MULTI, null, List.of()),
            new Adr060Corpus.Entry("shapes", BinderNameIndependenceTest.SHAPES, "m", List.of(
                    List.of(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)),
                            PlutusData.constr(0, PlutusData.integer(5), PlutusData.integer(1)),
                            PlutusData.map(new PlutusData.Pair(PlutusData.integer(7), PlutusData.integer(70)))),
                    List.of(PlutusData.list(PlutusData.integer(7), PlutusData.integer(9)), PlutusData.constr(1),
                            PlutusData.map()))),
            new Adr060Corpus.Entry("statements", BinderNameIndependenceTest.STATEMENTS, "m", List.of(
                    List.of(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)),
                            PlutusData.list(PlutusData.integer(7)), value(7, 8, 70)),
                    List.of(PlutusData.list(PlutusData.integer(9)), PlutusData.list(), value(7, 9, 5)))));

    /** A ledger Value holding {@code quantity} of the one-byte policy and token. */
    private static PlutusData value(int policy, int token, long quantity) {
        return PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[] {(byte) policy}),
                PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[] {(byte) token}),
                        PlutusData.integer(quantity)))));
    }

    /** One method holding every construct whose lowering binds names around user code. */
    static final String SHAPES = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.stdlib.Builtins;
            class Shapes {
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount, BigInteger fee) implements Action {}
                record Cancel() implements Action {}
                static BigInteger m(JulcList<BigInteger> items, Action action, JulcMap<BigInteger, BigInteger> table) {
                    BigInteger probe = BigInteger.valueOf(7);
                    BigInteger viaSwitch = switch (action) {
                        case Pay p -> p.amount().add(p.fee()).add(probe);
                        case Cancel c -> probe;
                    };
                    long single = 0;
                    for (var i : items) { single = single + probe.longValue(); }
                    long first = 0;
                    long second = 0;
                    for (var i : items) { first = first + 1; second = second + probe.longValue(); }
                    for (var i : items) { if (!probe.equals(BigInteger.valueOf(7))) { Builtins.error(); } }
                    long k = 0;
                    long w = 0;
                    while (k < 3) { k = k + 1; w = w + probe.longValue(); }
                    long before = 0;
                    for (var i : items) { if (i.equals(probe)) { break; } before = before + 1; }
                    boolean seen = items.any(v -> v.compareTo(probe) < 0);
                    long below = items.filter(v -> v.compareTo(probe) < 0).size();
                    boolean has = items.contains(probe);
                    boolean mapped = table.containsKey(probe);
                    long total = single + first + second + k + w + before + below + (seen ? 1 : 0);
                    return viaSwitch.add(BigInteger.valueOf(total)).add(items.get(1))
                            .add(has ? BigInteger.ONE : BigInteger.ZERO).add(mapped ? BigInteger.TWO : BigInteger.ZERO);
                }
            }
            """;

    /**
     * Statement sequencing (a while loop first, then if and for-each statements followed by more
     * code) and the list builders SHAPES does not use.
     */
    static final String STATEMENTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ListsLib;
            import org.julclang.ledger.Value;
            class Statements {
                static BigInteger m(JulcList<BigInteger> items, JulcList<PlutusData> datas, Value value) {
                    BigInteger probe = BigInteger.valueOf(7);
                    long k = 0;
                    while (k < 2) { k = k + probe.longValue(); }
                    byte[] policy = Builtins.integerToByteString(true, 1, probe);
                    byte[] token = Builtins.integerToByteString(true, 1, probe.add(BigInteger.ONE));
                    long nested = 0;
                    for (var i : items) {
                        long inner = 0;
                        for (var j : items) { inner = inner + 1; }
                        for (var j : items) { if (j.signum() < 0) { Builtins.error(); } }
                        nested = nested + inner + probe.longValue();
                    }
                    if (items.isEmpty()) { Builtins.error(); }
                    for (var i : items) { if (i.signum() < 0) { Builtins.error(); } }
                    while (k < 0) { Builtins.error(); }
                    long found = items.find(v -> v.compareTo(probe) < 0).isPresent() ? 1 : 0;
                    long mapped = items.map(v -> v.add(probe)).size();
                    long kept = items.take(probe.longValue() - 5).size() + items.drop(probe.longValue() - 6).size();
                    long listed = ListsLib.contains(datas, Builtins.iData(probe)) ? 1 : 0;
                    long joined = items.concat(items).size();
                    BigInteger held = value.assetOf(policy, token);
                    return probe.add(BigInteger.valueOf(k + found + mapped + kept + listed + nested + joined)).add(held);
                }
            }
            """;

    /** Multi-purpose validator: the purpose-tag dispatch binds names around both handlers. */
    static final String MULTI = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Purpose;
            @MultiValidator
            class Multi {
                @Param BigInteger limit;
                @Entrypoint(purpose = Purpose.MINT)
                static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                    return Builtins.unIData(redeemer).compareTo(limit) <= 0;
                }
                @Entrypoint(purpose = Purpose.SPEND)
                static boolean handleSpend(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                    return Builtins.equalsData(datum, redeemer) && limit.signum() > 0;
                }
            }
            """;

    private record Rename(String entry, String kind, String from, String to) {
        @Override public String toString() { return entry + " " + kind + " " + from + " -> " + to; }
    }

    @Test
    void renamingASourceNameDoesNotChangeTheProgram() {
        var failures = new ArrayList<String>();
        int renames = 0;
        int rotation = 0;
        var entries = new ArrayList<>(Adr060Corpus.entries());
        entries.addAll(CLASS_LEVEL);
        for (var entry : entries) {
            var cu = PARSER.parse(entry.source()).getResult().orElseThrow();
            var classLevel = CLASS_LEVEL.contains(entry);
            for (var candidate : candidates(cu, classLevel)) {
                // Class-level programs rename every name to every target; the corpus rotates.
                var targets = classLevel ? TARGETS : List.of(TARGETS.get(rotation++ % TARGETS.size()));
                for (var target : targets) {
                    if (appearsIn(entry.source(), target)) continue;
                    var rename = new Rename(entry.id(), candidate.kind(), candidate.name(), target);
                    var renamed = apply(entry, cu, candidate, target);
                    renames++;
                    // Class-level programs rename every name to every target, so they compare at one
                    // level without and one with the name-counting passes.
                    compare(entry, renamed, rename, classLevel
                            ? List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE) : LEVELS, failures);
                }
            }
        }
        System.out.println("ADR-060 G1: " + renames + " renames, " + evaluationComparisons
                + " PV11 comparisons by evaluation (new name already bound in the linked program)");
        assertTrue(renames > 400, "too few renames: " + renames);
        assertTrue(failures.isEmpty(), failures.size() + " renames changed the program:\n" + String.join("\n", failures));
    }

    private final Map<String, Optional<CompileResult>> originals = new HashMap<>();
    private int evaluationComparisons;

    private void compare(Adr060Corpus.Entry original, Adr060Corpus.Entry renamed, Rename rename,
                         List<OptimizationLevel> levels, List<String> failures) {
        for (var level : levels) {
            for (boolean maps : level == OptimizationLevel.PV11_SAFE && levels.size() > 2
                    ? new boolean[] {false, true} : new boolean[] {false}) {
                var before = originals.computeIfAbsent(original.id() + "/" + level + "/" + maps,
                        key -> Optional.ofNullable(compile(original, level, maps))).orElse(null);
                assertNotNull(before, () -> original.id() + " must compile at " + level);
                var after = compile(renamed, level, maps);
                if (after == null) {
                    failures.add(rename + " at " + level + ": no longer compiles");
                    continue;
                }
                boolean same = Arrays.equals(UplcFlatEncoder.encodeProgram(before.program()),
                        UplcFlatEncoder.encodeProgram(after.program()));
                if (same) continue;
                boolean countsNames = level.pv11SafeRulesEnabled();
                if (countsNames && binderNames(before).contains(rename.to())) {
                    evaluationComparisons++;
                    // The PV11 passes count binders by name: only the observable behaviour must agree.
                    for (var input : original.inputs())
                        if (!sameEvaluation(before, after, input))
                            failures.add(rename + " at " + level + " maps=" + maps + ": evaluation differs on " + input);
                    continue;
                }
                failures.add(rename + " at " + level + " maps=" + maps + ": FLAT bytes differ");
            }
        }
    }

    private static boolean sameEvaluation(CompileResult before, CompileResult after, List<PlutusData> args) {
        var vm = CompilerTestVm.pv11("Java");
        var a = args.isEmpty() ? vm.evaluate(before.program()) : vm.evaluateWithArgs(before.program(), args);
        var b = args.isEmpty() ? vm.evaluate(after.program()) : vm.evaluateWithArgs(after.program(), args);
        if (a instanceof EvalResult.Success sa && b instanceof EvalResult.Success sb)
            return sa.resultTerm().equals(sb.resultTerm());
        return a.isSuccess() == b.isSuccess();
    }

    private static CompileResult compile(Adr060Corpus.Entry entry, OptimizationLevel level, boolean maps) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(maps)
                .setOptimizationCostProfile(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1));
        try {
            var result = entry.validator() ? compiler.compileWithDetails(entry.source())
                    : compiler.compileMethod(entry.source(), entry.method());
            return result.hasErrors() ? null : result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every binder name in the program's PIR, library bodies included. */
    private static Set<String> binderNames(CompileResult result) {
        var names = new HashSet<String>();
        if (result.pirTerm() == null) return names;
        collectBinders(result.pirTerm(), names);
        return names;
    }

    private static void collectBinders(PirTerm term, Set<String> names) {
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> { names.add(l.param()); collectBinders(l.body(), names); }
            case PirTerm.Let l -> { names.add(l.name()); collectBinders(l.value(), names); collectBinders(l.body(), names); }
            case PirTerm.LetRec r -> {
                r.bindings().forEach(b -> { names.add(b.name()); collectBinders(b.value(), names); });
                collectBinders(r.body(), names);
            }
            case PirTerm.App a -> { collectBinders(a.function(), names); collectBinders(a.argument(), names); }
            case PirTerm.IfThenElse i -> {
                collectBinders(i.cond(), names); collectBinders(i.thenBranch(), names); collectBinders(i.elseBranch(), names);
            }
            case PirTerm.Trace t -> { collectBinders(t.message(), names); collectBinders(t.body(), names); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> collectBinders(f, names));
            case PirTerm.DataMatch m -> {
                collectBinders(m.scrutinee(), names);
                m.branches().forEach(b -> {
                    names.addAll(b.bindings());
                    if (b.patternVar() != null) names.add(b.patternVar());
                    collectBinders(b.body(), names);
                });
            }
            case PirTerm.ListMatch m -> {
                names.add(m.headName()); names.add(m.tailName());
                collectBinders(m.scrutinee(), names); collectBinders(m.nilBranch(), names); collectBinders(m.consBranch(), names);
            }
            case PirTerm.PairMatch m -> {
                names.add(m.firstName()); names.add(m.secondName());
                collectBinders(m.scrutinee(), names); collectBinders(m.body(), names);
            }
            case PirTerm.IntegerCase c -> { collectBinders(c.scrutinee(), names); c.branches().forEach(b -> collectBinders(b, names)); }
        }
    }

    // --- renaming ---

    private record Candidate(String kind, String name, MethodDeclaration method) {}

    /**
     * Renameable names: method-scoped names (a Java local cannot shadow another, so renaming every
     * occurrence inside the method is a consistent alpha-rename), and for class-level programs the
     * static methods, static fields and @Param fields. Names that are also a type, record
     * component, field or method elsewhere in the file are skipped as ambiguous.
     */
    private static List<Candidate> candidates(CompilationUnit cu, boolean classLevel) {
        var reserved = new HashSet<String>();
        cu.findAll(TypeDeclaration.class).forEach(t -> reserved.add(t.getNameAsString()));
        cu.findAll(RecordDeclaration.class).forEach(r -> r.getParameters().forEach(p -> reserved.add(p.getNameAsString())));
        var fields = new LinkedHashSet<String>();
        cu.findAll(FieldDeclaration.class).forEach(f -> f.getVariables().forEach(v -> fields.add(v.getNameAsString())));
        var methods = new LinkedHashSet<String>();
        cu.findAll(MethodDeclaration.class).forEach(m -> methods.add(m.getNameAsString()));
        var result = new ArrayList<Candidate>();
        for (var method : cu.findAll(MethodDeclaration.class)) {
            if (method.findAncestor(RecordDeclaration.class).isPresent()) continue;
            var names = new LinkedHashSet<String>();
            method.getParameters().forEach(p -> names.add(p.getNameAsString()));
            method.findAll(VariableDeclarator.class).forEach(v -> names.add(v.getNameAsString()));
            method.findAll(Parameter.class).forEach(p -> names.add(p.getNameAsString()));
            method.findAll(TypePatternExpr.class).forEach(p -> names.add(p.getNameAsString()));
            for (var name : names)
                if (!reserved.contains(name) && !fields.contains(name) && !methods.contains(name))
                    result.add(new Candidate("local", name, method));
        }
        if (classLevel) {
            var locals = new HashSet<String>();
            result.forEach(c -> locals.add(c.name()));
            for (var name : methods)
                if (!reserved.contains(name) && !name.equals("validate")) result.add(new Candidate("method", name, null));
            for (var name : fields)
                if (!reserved.contains(name) && !locals.contains(name)) result.add(new Candidate("field", name, null));
        }
        return result;
    }

    private static Adr060Corpus.Entry apply(Adr060Corpus.Entry entry, CompilationUnit cu, Candidate candidate, String to) {
        var spans = new ArrayList<SimpleName>();
        Node root = candidate.method() != null ? candidate.method() : cu;
        for (var name : root.findAll(SimpleName.class)) {
            if (!name.getIdentifier().equals(candidate.name())) continue;
            var parent = name.getParentNode().orElse(null);
            boolean variable = parent instanceof NameExpr || parent instanceof Parameter
                    || parent instanceof VariableDeclarator || parent instanceof TypePatternExpr;
            boolean method = parent instanceof MethodDeclaration
                    || parent instanceof MethodCallExpr call && call.getScope().isEmpty();
            if (candidate.kind().equals("method") ? method : variable) spans.add(name);
        }
        spans.sort(Comparator.comparing((SimpleName n) -> n.getBegin().orElseThrow()).reversed());
        var lines = new ArrayList<>(entry.source().lines().toList());
        for (var span : spans) {
            var begin = span.getBegin().orElseThrow();
            var line = lines.get(begin.line - 1);
            int column = begin.column - 1;
            assertEquals(candidate.name(), line.substring(column, column + candidate.name().length()), span.toString());
            lines.set(begin.line - 1, line.substring(0, column) + to + line.substring(column + candidate.name().length()));
        }
        String method = entry.method();
        if (candidate.kind().equals("method") && candidate.name().equals(method)) method = to;
        return new Adr060Corpus.Entry(entry.id(), String.join("\n", lines) + "\n", method, entry.inputs());
    }

    private static boolean appearsIn(String source, String name) {
        var matcher = IDENTIFIER.matcher(source);
        while (matcher.find()) if (matcher.group().equals(name)) return true;
        return false;
    }
}
