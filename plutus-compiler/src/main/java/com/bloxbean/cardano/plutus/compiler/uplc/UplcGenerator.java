package com.bloxbean.cardano.plutus.compiler.uplc;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.bloxbean.cardano.plutus.core.Term;

import java.math.BigInteger;
import java.util.*;

/**
 * Translates PIR terms to UPLC terms.
 * Performs type erasure and De Bruijn index computation.
 */
public class UplcGenerator {

    private final Deque<String> scope = new ArrayDeque<>();

    public Term generate(PirTerm pir) {
        return switch (pir) {
            case PirTerm.Var(var name, _) -> {
                // Field accessor pseudo-variables are handled by their containing App
                if (name.startsWith(".")) {
                    throw new CompilerException("Bare field accessor not supported: " + name);
                }
                yield Term.var(deBruijnIndex(name));
            }

            case PirTerm.Const(var value) -> Term.const_(value);

            case PirTerm.Builtin(var fun) -> wrapForces(Term.builtin(fun), forceCount(fun));

            case PirTerm.Lam(var param, _, var body) -> {
                scope.push(param);
                var bodyTerm = generate(body);
                scope.pop();
                yield Term.lam(param, bodyTerm);
            }

            case PirTerm.App(var function, var argument) -> {
                // Handle field accessor: App(Var(".field"), scope) -> field extraction
                if (function instanceof PirTerm.Var(var name, _) && name.startsWith(".")) {
                    // For MVP, field access on Data-typed values is just passed through
                    // The ValidatorWrapper/DataCodecGenerator handles the actual field extraction
                    yield Term.apply(
                            Term.var(deBruijnIndex(name.substring(1))),
                            generate(argument));
                }
                yield Term.apply(generate(function), generate(argument));
            }

            case PirTerm.Let(var name, var value, var body) -> {
                // Let(name, val, body) -> Apply(Lam(name, body'), val')
                var valTerm = generate(value);
                scope.push(name);
                var bodyTerm = generate(body);
                scope.pop();
                yield Term.apply(Term.lam(name, bodyTerm), valTerm);
            }

            case PirTerm.LetRec letRec -> generateLetRec(letRec);

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) -> {
                // Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))
                var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifBuiltin, generate(cond)),
                                        Term.delay(generate(thenBranch))),
                                Term.delay(generate(elseBranch))));
            }

            case PirTerm.DataConstr(var tag, var dataType, var fields) -> {
                // Get field types from the record/sum type
                var fieldTypes = getFieldTypes(dataType, tag);

                // Build the Data list from right to left: MkCons(last, MkNilData())
                Term fieldList = Term.apply(
                        wrapForces(Term.builtin(DefaultFun.MkNilData), 0),
                        Term.const_(Constant.unit()));
                for (int i = fields.size() - 1; i >= 0; i--) {
                    var fieldTerm = generate(fields.get(i));
                    // Wrap with Data encoding based on field type
                    if (i < fieldTypes.size()) {
                        fieldTerm = wrapDataEncode(fieldTerm, fieldTypes.get(i));
                    }
                    fieldList = Term.apply(
                            Term.apply(
                                    wrapForces(Term.builtin(DefaultFun.MkCons), forceCount(DefaultFun.MkCons)),
                                    fieldTerm),
                            fieldList);
                }

                // ConstrData(tag, fieldList)
                yield Term.apply(
                        Term.apply(Term.builtin(DefaultFun.ConstrData),
                                Term.const_(Constant.integer(BigInteger.valueOf(tag)))),
                        fieldList);
            }

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                yield generateDataMatch(scrutinee, branches);
            }

            case PirTerm.Error _ -> Term.error();

            case PirTerm.Trace(var message, var body) -> {
                // Apply(Apply(Force(Builtin(Trace)), msg), body)
                // Trace is polymorphic (1 Force), so: Force(Builtin(Trace))
                // Unlike IfThenElse, Trace evaluates its second arg eagerly (no Delay/Force needed)
                var traceBuiltin = Term.force(Term.builtin(DefaultFun.Trace));
                yield Term.apply(
                        Term.apply(traceBuiltin, generate(message)),
                        generate(body));
            }
        };
    }

    private Term generateLetRec(PirTerm.LetRec letRec) {
        // Z-combinator implementation for recursive bindings.
        // For single binding: LetRec([name = body], expr)
        //   → Let(name, Apply(fix, Lam(name, body')), expr')
        // where fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))

        if (letRec.bindings().size() == 1) {
            var binding = letRec.bindings().getFirst();
            var name = binding.name();
            var value = binding.value();

            // Build the Z-combinator:
            // fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
            // In UPLC with De Bruijn indices:
            // fix = Lam("f", Apply(
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1))))),
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1)))))))

            var innerBody = Term.lam("v",
                    Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
            var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
            var fix = Term.lam("f", Term.apply(branch, branch));

            // Generate the recursive function body: Lam(name, body')
            // The body references 'name' which is the recursive reference
            scope.push(name);
            var bodyTerm = generate(value);
            scope.pop();
            var recursiveLam = Term.lam(name, bodyTerm);

            // Apply fix to the recursive lambda
            var fixedFn = Term.apply(fix, recursiveLam);

            // Now bind name = fixedFn and generate the expression
            scope.push(name);
            var exprTerm = generate(letRec.body());
            scope.pop();

            return Term.apply(Term.lam(name, exprTerm), fixedFn);
        }

        // For multiple bindings, treat as sequential lets (fallback)
        PirTerm result = letRec.body();
        for (int i = letRec.bindings().size() - 1; i >= 0; i--) {
            var binding = letRec.bindings().get(i);
            result = new PirTerm.Let(binding.name(), binding.value(), result);
        }
        return generate(result);
    }

    /**
     * Generate Data-based pattern matching for DataMatch.
     * Builds the equivalent PIR using UnConstrData + FstPair + SndPair +
     * IfThenElse tag dispatch + HeadList/TailList field extraction,
     * then generates UPLC from that PIR.
     */
    private Term generateDataMatch(PirTerm scrutinee, List<PirTerm.MatchBranch> branches) {
        var pairName = "__match_pair";
        var tagName = "__match_tag";
        var fieldsName = "__match_fields";

        // Build the dispatch chain: IfThenElse(tag==0, branch0, IfThenElse(tag==1, branch1, ...Error))
        PirTerm dispatch;
        if (branches.size() == 1) {
            dispatch = buildBranchFieldExtraction(branches.get(0), fieldsName);
        } else {
            dispatch = new PirTerm.Error(new PirType.UnitType());
            for (int i = branches.size() - 1; i >= 0; i--) {
                var branchBody = buildBranchFieldExtraction(branches.get(i), fieldsName);
                var tagCheck = new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                new PirTerm.Var(tagName, new PirType.IntegerType())),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(i))));
                dispatch = new PirTerm.IfThenElse(tagCheck, branchBody, dispatch);
            }
        }

        // Wrap in: let pair = UnConstrData(scrutinee)
        //          let tag = FstPair(pair)
        //          let fields = SndPair(pair)
        //          dispatch
        var pairVar = new PirTerm.Var(pairName, new PirType.DataType());
        var matchPir = new PirTerm.Let(pairName,
                pirApp1(DefaultFun.UnConstrData, scrutinee),
                new PirTerm.Let(tagName,
                        pirApp1(DefaultFun.FstPair, pairVar),
                        new PirTerm.Let(fieldsName,
                                pirApp1(DefaultFun.SndPair, pairVar),
                                dispatch)));
        return generate(matchPir);
    }

    /**
     * Build PIR for extracting fields from a Data list and binding them in the branch body.
     * HeadList/TailList for extraction, UnIData/UnBData for decoding.
     */
    private PirTerm buildBranchFieldExtraction(PirTerm.MatchBranch branch, String fieldsName) {
        var bindings = branch.bindings();
        var bindingTypes = branch.bindingTypes();

        if (bindings.isEmpty()) {
            return branch.body();
        }

        // Build Let chain for field extractions
        PirTerm result = branch.body();
        var lets = new ArrayList<PirTerm.Let>();

        for (int j = 0; j < bindings.size(); j++) {
            var listVar = (j == 0) ? fieldsName : "__rest_" + (j - 1);
            var listRef = new PirTerm.Var(listVar, new PirType.DataType());

            // Decode field: UnIData(HeadList(fields)) for Integer, etc.
            var headExpr = pirApp1(DefaultFun.HeadList, listRef);
            var decodedExpr = pirWrapDecode(headExpr, bindingTypes.get(j));
            lets.add(new PirTerm.Let(bindings.get(j), decodedExpr, null)); // body filled later

            if (j + 1 < bindings.size()) {
                var tailExpr = pirApp1(DefaultFun.TailList, listRef);
                lets.add(new PirTerm.Let("__rest_" + j, tailExpr, null)); // body filled later
            }
        }

        // Build nested Let chain from inside out
        for (int j = lets.size() - 1; j >= 0; j--) {
            var let = lets.get(j);
            result = new PirTerm.Let(let.name(), let.value(), result);
        }
        return result;
    }

    /** Create a PIR Builtin application with 1 arg. */
    private static PirTerm pirApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    /** Wrap a PIR Data value with the appropriate decoding based on type. */
    private static PirTerm pirWrapDecode(PirTerm data, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> pirApp1(DefaultFun.UnIData, data);
            case PirType.ByteStringType _ -> pirApp1(DefaultFun.UnBData, data);
            case PirType.ListType _ -> pirApp1(DefaultFun.UnListData, data);
            case PirType.MapType _ -> pirApp1(DefaultFun.UnMapData, data);
            default -> data; // DataType, RecordType, SumType etc. — already Data
        };
    }

    private int deBruijnIndex(String name) {
        int index = 1; // De Bruijn indices are 1-based
        for (var n : scope) {
            if (n.equals(name)) return index;
            index++;
        }
        throw new CompilerException("Unbound variable: " + name);
    }

    /**
     * Get the number of Force wrappers needed for a polymorphic builtin.
     */
    static int forceCount(DefaultFun fun) {
        return switch (fun) {
            // 2 Forces (2 type variables: ∀ a b)
            case FstPair, SndPair, ChooseList -> 2;
            // 1 Force (1 type variable: ∀ a)
            case IfThenElse, ChooseUnit, Trace, ChooseData,
                 SerialiseData, MkCons, HeadList, TailList, NullList -> 1;
            // 0 Forces (monomorphic)
            default -> 0;
        };
    }

    private static Term wrapForces(Term term, int count) {
        for (int i = 0; i < count; i++) {
            term = Term.force(term);
        }
        return term;
    }

    /**
     * Get the PIR types of fields for a DataConstr's data type.
     */
    private static List<PirType> getFieldTypes(PirType dataType, int tag) {
        if (dataType instanceof PirType.RecordType rt) {
            return rt.fields().stream().map(PirType.Field::type).toList();
        }
        if (dataType instanceof PirType.SumType st) {
            for (var ctor : st.constructors()) {
                if (ctor.tag() == tag) {
                    return ctor.fields().stream().map(PirType.Field::type).toList();
                }
            }
        }
        return List.of();
    }

    /**
     * Wrap a UPLC term with the appropriate Data encoding based on PIR type.
     * Integer → IData, ByteString → BData, List → ListData, Map → MapData, etc.
     */
    private static Term wrapDataEncode(Term value, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> Term.apply(Term.builtin(DefaultFun.IData), value);
            case PirType.ByteStringType _ -> Term.apply(Term.builtin(DefaultFun.BData), value);
            case PirType.ListType _ -> Term.apply(Term.builtin(DefaultFun.ListData), value);
            case PirType.MapType _ -> Term.apply(Term.builtin(DefaultFun.MapData), value);
            case PirType.BoolType _ -> {
                // Bool: True → ConstrData(1,[]), False → ConstrData(0,[])
                var nilData = Term.apply(
                        wrapForces(Term.builtin(DefaultFun.MkNilData), 0),
                        Term.const_(Constant.unit()));
                var ifThenElse = wrapForces(Term.builtin(DefaultFun.IfThenElse), 1);
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifThenElse, value),
                                        Term.delay(Term.apply(
                                                Term.apply(Term.builtin(DefaultFun.ConstrData),
                                                        Term.const_(Constant.integer(BigInteger.ONE))),
                                                nilData))),
                                Term.delay(Term.apply(
                                        Term.apply(Term.builtin(DefaultFun.ConstrData),
                                                Term.const_(Constant.integer(BigInteger.ZERO))),
                                        nilData))));
            }
            case PirType.StringType _ -> Term.apply(Term.builtin(DefaultFun.BData),
                    Term.apply(Term.builtin(DefaultFun.EncodeUtf8), value));
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _ -> value; // Already Data
            default -> value; // Pass through for unknown types
        };
    }
}
