package com.bloxbean.cardano.julc.compiler.codegen;

import com.bloxbean.cardano.julc.compiler.pir.PirHelpers;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fuses strict record-root checking with the entrypoint's top-level projections. */
public final class StrictRecordEntrypoint {
    private StrictRecordEntrypoint() { }

    public static Result transform(
            PirTerm function,
            List<Root> roots,
            Map<String, PirType> namedDefinitions) {
        if (roots.isEmpty()) return new Result(function, List.of());

        var lambdas = new ArrayList<PirTerm.Lam>();
        PirTerm cursor = function;
        while (cursor instanceof PirTerm.Lam lambda) {
            lambdas.add(lambda);
            cursor = lambda.body();
        }

        var errors = new ArrayList<PirTerm.Error>();
        PirTerm body = cursor;
        for (var root : roots) {
            PirType resolved = resolve(root.type(), namedDefinitions);
            if (!(resolved instanceof PirType.RecordType record)) continue;

            String pairName = "__boundary-pair-" + root.parameter();
            String fieldsName = "__boundary-fields-" + root.parameter();
            var pair = new PirTerm.Var(pairName, new PirType.DataType());
            var fields = new PirTerm.Var(fieldsName,
                    new PirType.ListType(new PirType.DataType()));
            var rawFields = new ArrayList<PirTerm>();

            for (int i = 0; i < record.fields().size(); i++) {
                var field = record.fields().get(i);
                String cachedName = cachedField(root.parameter(), i);
                String rawName = rawField(root.parameter(), i);
                rawFields.add(new PirTerm.Var(rawName, new PirType.DataType()));
                PirTerm extraction = extraction(
                        new PirTerm.Var(root.parameter(), root.type()), i, field.type());
                body = replace(body, extraction, new PirTerm.Var(cachedName, field.type()));
            }

            for (int i = record.fields().size() - 1; i >= 0; i--) {
                var field = record.fields().get(i);
                body = new PirTerm.Let(cachedField(root.parameter(), i),
                        PirHelpers.wrapDecode(rawFields.get(i), field.type()), body);
            }

            var error = new PirTerm.Error(new PirType.UnitType());
            errors.add(error);
            var generator = new StrictBoundaryGenerator(namedDefinitions);
            var remainingFields = new PirTerm.Var(
                    remainingFields(root.parameter(), record.fields().size()),
                    new PirType.ListType(new PirType.DataType()));
            body = new PirTerm.IfThenElse(
                    generator.checkRecordComponents(pair, rawFields, remainingFields, record),
                    body,
                    error);

            for (int i = record.fields().size() - 1; i >= 0; i--) {
                var currentFields = new PirTerm.Var(remainingFields(root.parameter(), i),
                        new PirType.ListType(new PirType.DataType()));
                body = new PirTerm.Let(rawField(root.parameter(), i),
                        builtin1(DefaultFun.HeadList, currentFields),
                        new PirTerm.Let(remainingFields(root.parameter(), i + 1),
                                builtin1(DefaultFun.TailList, currentFields), body));
            }
            body = new PirTerm.Let(fieldsName,
                    builtin1(DefaultFun.SndPair, pair), body);
            body = new PirTerm.Let(pairName,
                    builtin1(DefaultFun.UnConstrData,
                            new PirTerm.Var(root.parameter(), new PirType.DataType())), body);
        }

        for (int i = lambdas.size() - 1; i >= 0; i--) {
            var lambda = lambdas.get(i);
            body = new PirTerm.Lam(lambda.param(), lambda.paramType(), body);
        }
        return new Result(body, errors);
    }

    public record Root(String parameter, PirType type) { }
    public record Result(PirTerm function, List<PirTerm.Error> errors) { }

    private static PirType resolve(PirType type, Map<String, PirType> namedDefinitions) {
        if (!(type instanceof PirType.NamedTypeRef ref)) return type;
        return namedDefinitions.getOrDefault(ref.stableId(), type);
    }

    private static String cachedField(String parameter, int index) {
        return "__boundary-field-" + parameter + "-" + index;
    }

    private static String rawField(String parameter, int index) {
        return "__boundary-raw-field-" + parameter + "-" + index;
    }

    private static String remainingFields(String parameter, int index) {
        return index == 0
                ? "__boundary-fields-" + parameter
                : "__boundary-fields-" + parameter + "-" + index;
    }

    private static PirTerm extraction(PirTerm data, int index, PirType fieldType) {
        var fields = builtin1(DefaultFun.SndPair, builtin1(DefaultFun.UnConstrData, data));
        return PirHelpers.wrapDecode(listIndex(fields, index), fieldType);
    }

    private static PirTerm listIndex(PirTerm list, int index) {
        PirTerm current = list;
        for (int i = 0; i < index; i++) current = builtin1(DefaultFun.TailList, current);
        return builtin1(DefaultFun.HeadList, current);
    }

    private static PirTerm replace(PirTerm term, PirTerm target, PirTerm replacement) {
        if (term.equals(target)) return replacement;
        return switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> term;
            case PirTerm.Let(var name, var value, var body) -> new PirTerm.Let(name,
                    replace(value, target, replacement), replace(body, target, replacement));
            case PirTerm.LetRec(var bindings, var body) -> new PirTerm.LetRec(
                    bindings.stream().map(binding -> new PirTerm.Binding(binding.name(),
                            replace(binding.value(), target, replacement))).toList(),
                    replace(body, target, replacement));
            case PirTerm.Lam(var param, var type, var body) ->
                    new PirTerm.Lam(param, type, replace(body, target, replacement));
            case PirTerm.App(var function, var argument) -> new PirTerm.App(
                    replace(function, target, replacement),
                    replace(argument, target, replacement));
            case PirTerm.IfThenElse(var cond, var yes, var no) -> new PirTerm.IfThenElse(
                    replace(cond, target, replacement),
                    replace(yes, target, replacement), replace(no, target, replacement));
            case PirTerm.DataConstr(var tag, var type, var fields) -> new PirTerm.DataConstr(
                    tag, type, fields.stream().map(field ->
                            replace(field, target, replacement)).toList());
            case PirTerm.DataMatch(var scrutinee, var branches) -> new PirTerm.DataMatch(
                    replace(scrutinee, target, replacement),
                    branches.stream().map(branch -> new PirTerm.MatchBranch(
                            branch.constructorName(), branch.bindings(), branch.bindingTypes(),
                            replace(branch.body(), target, replacement), branch.patternVar())).toList());
            case PirTerm.Trace(var message, var body) -> new PirTerm.Trace(
                    replace(message, target, replacement),
                    replace(body, target, replacement));
        };
    }

    private static PirTerm builtin1(DefaultFun fun, PirTerm argument) {
        return new PirTerm.App(new PirTerm.Builtin(fun), argument);
    }
}
