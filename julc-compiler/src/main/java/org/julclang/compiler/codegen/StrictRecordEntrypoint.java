package org.julclang.compiler.codegen;

import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.DefaultFun;
import org.julclang.core.recursion.Trampoline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fuses strict record-root checking with the entrypoint's top-level projections. */
public final class StrictRecordEntrypoint {
    private StrictRecordEntrypoint() { }

    /**
     * Told what the transform builds, for a frontend that keeps a side table keyed by term identity. It rebuilds the
     * function's lambdas and every binder of its body, under the same names.
     */
    public interface Rebuilds {
        Rebuilds NONE = new Rebuilds() {
            @Override
            public void rebuilt(PirTerm original, PirTerm copy) {
            }

            @Override
            public void parameterRead(PirTerm.Var read) {
            }
        };

        /** {@code copy} stands for {@code original}, with the same binders. */
        void rebuilt(PirTerm original, PirTerm copy);

        /** {@code read} is a new read of the root parameter of its name. */
        void parameterRead(PirTerm.Var read);
    }

    public static Result transform(
            PirTerm function,
            List<Root> roots,
            Map<String, PirType> namedDefinitions) {
        return transform(function, roots, namedDefinitions, Rebuilds.NONE);
    }

    public static Result transform(
            PirTerm function,
            List<Root> roots,
            Map<String, PirType> namedDefinitions,
            Rebuilds rebuilds) {
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

            String pairName = "#__boundary-pair-" + root.parameter();
            String fieldsName = "#__boundary-fields-" + root.parameter();
            var pair = new PirTerm.Var(pairName, new PirType.PairType(new PirType.IntegerType(),
                    new PirType.ListType(new PirType.DataType())));
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
                body = replace(body, extraction, new PirTerm.Var(cachedName, field.type()), rebuilds);
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
            var parameter = new PirTerm.Var(root.parameter(), new PirType.DataType());
            rebuilds.parameterRead(parameter);
            body = new PirTerm.Let(pairName, builtin1(DefaultFun.UnConstrData, parameter), body);
        }

        for (int i = lambdas.size() - 1; i >= 0; i--) {
            var lambda = lambdas.get(i);
            body = new PirTerm.Lam(lambda.param(), lambda.paramType(), body);
            rebuilds.rebuilt(lambda, body);
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
        return "#__boundary-field-" + parameter + "-" + index;
    }

    private static String rawField(String parameter, int index) {
        return "#__boundary-raw-field-" + parameter + "-" + index;
    }

    private static String remainingFields(String parameter, int index) {
        return index == 0
                ? "#__boundary-fields-" + parameter
                : "#__boundary-fields-" + parameter + "-" + index;
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

    private static PirTerm replace(PirTerm term, PirTerm target, PirTerm replacement, Rebuilds rebuilds) {
        return replaceWalk(term, target, replacement, rebuilds).run();
    }

    /**
     * {@code term} with every occurrence of {@code target} replaced, each binder form rebuilt and reported to
     * {@code rebuilds} after its children, in a left-to-right walk. The walk keeps its pending work on the heap, as an
     * entrypoint's body can nest as deeply as the method is long (issue #327, ADR-074).
     */
    private static Trampoline<PirTerm> replaceWalk(PirTerm term, PirTerm target, PirTerm replacement, Rebuilds rebuilds) {
        return Trampoline.defer(() -> {
            if (term.equals(target)) return Trampoline.done(replacement);
            var copy = PirHelpers.traverseChildren(term, child -> replaceWalk(child, target, replacement, rebuilds));
            return switch (term) {
                case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> Trampoline.done(term);
                // The binder forms are reported to rebuilds; the others are not.
                case PirTerm.Let _, PirTerm.LetRec _, PirTerm.Lam _, PirTerm.PairMatch _, PirTerm.ListMatch _,
                     PirTerm.DataMatch _ -> copy.map(rebuiltTerm -> rebuilt(term, rebuiltTerm, rebuilds));
                case PirTerm.App _, PirTerm.IfThenElse _, PirTerm.DataConstr _, PirTerm.IntegerCase _,
                     PirTerm.Trace _ -> copy;
            };
        });
    }

    private static PirTerm rebuilt(PirTerm original, PirTerm copy, Rebuilds rebuilds) {
        rebuilds.rebuilt(original, copy);
        return copy;
    }

    private static PirTerm builtin1(DefaultFun fun, PirTerm argument) {
        return new PirTerm.App(new PirTerm.Builtin(fun), argument);
    }
}
