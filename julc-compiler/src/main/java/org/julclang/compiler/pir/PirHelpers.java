package org.julclang.compiler.pir;

import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;


import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Static utility methods for PIR term construction.
 * Used by both PirGenerator and TypeMethodRegistry.
 */
public final class PirHelpers {

    private PirHelpers() {}

    /**
     * Rebuild {@code term} with {@code map} applied to each direct child, preserving binders,
     * types and branch structure. Shared by the PIR-to-PIR passes that run before UPLC
     * generation (ADR-036 pair destructuring, ADR-042 value conversion sharing).
     */
    static PirTerm mapChildren(PirTerm term, UnaryOperator<PirTerm> map) {
        return switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> term;
            case PirTerm.Lam l -> new PirTerm.Lam(l.param(), l.paramType(), map.apply(l.body()));
            case PirTerm.Let l -> new PirTerm.Let(l.name(), map.apply(l.value()), map.apply(l.body()));
            case PirTerm.LetRec r -> new PirTerm.LetRec(r.bindings().stream()
                    .map(b -> new PirTerm.Binding(b.name(), map.apply(b.value()))).toList(), map.apply(r.body()));
            case PirTerm.App a -> new PirTerm.App(map.apply(a.function()), map.apply(a.argument()));
            case PirTerm.IfThenElse i -> new PirTerm.IfThenElse(map.apply(i.cond()),
                    map.apply(i.thenBranch()), map.apply(i.elseBranch()));
            case PirTerm.Trace t -> new PirTerm.Trace(map.apply(t.message()), map.apply(t.body()));
            case PirTerm.DataConstr c -> new PirTerm.DataConstr(c.tag(), c.dataType(), c.fields().stream().map(map).toList());
            case PirTerm.DataMatch m -> new PirTerm.DataMatch(map.apply(m.scrutinee()), m.branches().stream()
                    .map(b -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(), b.bindingTypes(),
                            map.apply(b.body()), b.patternVar())).toList());
            case PirTerm.ListMatch m -> new PirTerm.ListMatch(map.apply(m.scrutinee()), m.headName(), m.tailName(),
                    map.apply(m.nilBranch()), map.apply(m.consBranch()));
            case PirTerm.PairMatch m -> new PirTerm.PairMatch(map.apply(m.scrutinee()), m.pairType(),
                    m.firstName(), m.secondName(), map.apply(m.body()));
            case PirTerm.IntegerCase c -> new PirTerm.IntegerCase(map.apply(c.scrutinee()),
                    c.branches().stream().map(map).toList());
        };
    }

    /**
     * ADR-060: every binder the compiler invents is named {@code "#" + <historical name>}. No source
     * identifier contains {@code #}, so a user name can never resolve to a generated binder, and the
     * one-to-one spelling keeps every existing equality between generated names.
     */
    public static final String GENERATED_PREFIX = "#";

    /** Whether {@code name} is in the reserved namespace of compiler-generated binders. */
    public static boolean isGeneratedName(String name) {
        return name.startsWith(GENERATED_PREFIX);
    }

    /**
     * The free variables of caller-supplied terms that a builder places inside its own binders.
     * Pass the result to {@link #hygienicName} for every binder whose scope contains those terms.
     */
    public static Set<String> freeVariables(PirTerm... callerTerms) {
        var free = new HashSet<String>();
        for (var term : callerTerms) free.addAll(PirSubstitution.collectFreeVarNames(term));
        return free;
    }

    /**
     * A generated binder name that cannot capture a free variable of the terms placed in its scope
     * (ADR-060 R2). {@code base} must be a reserved {@code #} name; it is returned unless it is free
     * there, for example in another builder's term or in code from a non-Java frontend, and is
     * otherwise suffixed {@code #N}.
     */
    public static String hygienicName(String base, Set<String> callerFreeVariables) {
        if (!isGeneratedName(base)) {
            throw new IllegalArgumentException("Generated binder names must begin with '"
                    + GENERATED_PREFIX + "': " + base);
        }
        if (!callerFreeVariables.contains(base)) return base;
        int suffix = 1;
        while (callerFreeVariables.contains(base + "#" + suffix)) suffix++;
        return base + "#" + suffix;
    }

    /**
     * The recursive binding behind {@code JulcList.get}:
     * {@code go_get(lst, idx) = if idx == 0 then HeadList(lst) else go_get(TailList(lst), idx - 1)}.
     * Built once so that the lowering ({@link #recursiveListGet}) and the ADR-043 promotion pass
     * that recognises it structurally cannot drift apart.
     */
    public static final PirTerm.Binding RECURSIVE_LIST_GET = recursiveListGetBinding("#go_get");

    private static PirTerm.Binding recursiveListGetBinding(String goName) {
        var lstVar = new PirTerm.Var("#lst_get", new PirType.ListType(new PirType.DataType()));
        var idxVar = new PirTerm.Var("#idx_get", new PirType.IntegerType());
        var isZero = builtinApp2(DefaultFun.EqualsInteger, idxVar, new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var decIdx = builtinApp2(DefaultFun.SubtractInteger, idxVar, new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var recurse = new PirTerm.App(new PirTerm.App(recursiveListGetVar(goName), tailExpr), decIdx);
        var body = new PirTerm.IfThenElse(isZero, headExpr, recurse);
        var goBody = new PirTerm.Lam("#lst_get", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("#idx_get", new PirType.IntegerType(), body));
        return new PirTerm.Binding(goName, goBody);
    }

    private static PirTerm.Var recursiveListGetVar(String goName) {
        return new PirTerm.Var(goName, new PirType.FunType(
                new PirType.ListType(new PirType.DataType()),
                new PirType.FunType(new PirType.IntegerType(), new PirType.DataType())));
    }

    /**
     * {@code list.get(index)} as a per-site recursive traversal: O(index) {@code TailList} steps,
     * failing with {@code HeadList: empty list} when {@code index} equals the length and
     * {@code TailList: empty list} when it is negative or larger. Returns the raw element.
     * The list and index are applied inside the {@code LetRec}; if either mentions
     * {@code go_get}, the binding is renamed (and the ADR-043 promotion no longer applies).
     */
    public static PirTerm recursiveListGet(PirTerm list, PirTerm index) {
        String goName = hygienicName(RECURSIVE_LIST_GET.name(), freeVariables(list, index));
        var binding = goName.equals(RECURSIVE_LIST_GET.name())
                ? RECURSIVE_LIST_GET
                : recursiveListGetBinding(goName);
        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(recursiveListGetVar(goName), list), index));
    }

    public static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }

    /**
     * {@code fun y x}, with {@code x} evaluated first (ADR-068 D6, #295): {@code x} is bound by one {@code let} when both
     * operands are non-trivial; evaluating a variable or a constant has no effect, so its place does not matter.
     */
    public static PirTerm swappedInOrder(DefaultFun fun, PirTerm x, PirTerm y) {
        if (isTrivialOperand(x) || isTrivialOperand(y)) return builtinApp2(fun, y, x);
        var name = hygienicName("#lhs", freeVariables(y));
        return new PirTerm.Let(name, x, builtinApp2(fun, y, new PirTerm.Var(name, new PirType.IntegerType())));
    }

    /**
     * {@code operation(receiver, argument)} for a Java method that throws when {@code argument} is below {@code least}: the
     * script fails there too (ADR-068 B3). A constant that passes needs no check, and the operation takes the terms as they
     * are. Otherwise the receiver is evaluated, then the argument, then the check, as in Java, each bound once unless
     * evaluating it has no effect.
     *
     * @param resultType the type of the operation's result, which the failing branch takes
     */
    public static PirTerm failingBelow(long least, PirTerm receiver, PirType receiverType, PirTerm argument, PirType resultType,
                                       BinaryOperator<PirTerm> operation) {
        if (isConstantAtLeast(argument, least)) return operation.apply(receiver, argument);
        boolean bindArgument = !isTrivialOperand(argument);
        boolean bindReceiver = !isTrivialOperand(receiver);
        var avoid = freeVariables(receiver, argument);
        PirTerm receiverRead = bindReceiver ? new PirTerm.Var(hygienicName("#receiver", avoid), receiverType) : receiver;
        PirTerm argumentRead = bindArgument ? new PirTerm.Var(hygienicName("#argument", avoid), new PirType.IntegerType()) : argument;
        PirTerm result = failIfBelow(least, argumentRead, resultType, operation.apply(receiverRead, argumentRead));
        if (bindArgument) result = new PirTerm.Let(((PirTerm.Var) argumentRead).name(), argument, result);
        if (bindReceiver) result = new PirTerm.Let(((PirTerm.Var) receiverRead).name(), receiver, result);
        return result;
    }

    /** {@code body}, or a failure if {@code argument}, which has no effect to evaluate, is below {@code least}. */
    public static PirTerm failIfBelow(long least, PirTerm argument, PirType type, PirTerm body) {
        var below = builtinApp2(DefaultFun.LessThanInteger, argument, new PirTerm.Const(Constant.integer(least)));
        return new PirTerm.IfThenElse(below, new PirTerm.Error(type), body);
    }

    /** Whether {@code term} is an integer constant of at least {@code least}, which no check can fail. */
    public static boolean isConstantAtLeast(PirTerm term, long least) {
        return term instanceof PirTerm.Const constant && constant.value() instanceof Constant.IntegerConst integer
                && integer.value().compareTo(BigInteger.valueOf(least)) >= 0;
    }

    /**
     * {@code body} of an operand it evaluates more than once, ahead of all else and on every path. Java evaluates the
     * operand once, so an operand that is not a variable or a constant is bound once, where {@code body} would first have
     * run it, which moves no failure. Read twice, it would repeat its traces and its cost, and each level of a nest of such
     * handlers would double the size and cost of the level below (#146). A variable or a constant goes in as it is.
     */
    public static PirTerm readOnce(PirTerm operand, PirType type, UnaryOperator<PirTerm> body) {
        return boundOnce("#operand", !isTrivialOperand(operand), operand, type, freeVariables(operand), body);
    }

    /**
     * {@code body} of a receiver and an argument it may each read more than once, as {@link #readOnce(PirTerm, PirType, UnaryOperator)}.
     * {@code body} runs the receiver first, then the argument, as Java does, so the receiver is bound ahead of the argument.
     */
    public static PirTerm readOnce(PirTerm receiver, PirTerm argument, PirType type, BinaryOperator<PirTerm> body) {
        var avoid = freeVariables(receiver, argument);
        return boundOnce("#receiver", !isTrivialOperand(receiver), receiver, type, avoid, receiverRead ->
                boundOnce("#argument", !isTrivialOperand(argument), argument, type, avoid,
                        argumentRead -> body.apply(receiverRead, argumentRead)));
    }

    private static PirTerm boundOnce(String base, boolean bind, PirTerm operand, PirType type, Set<String> avoid,
                                     UnaryOperator<PirTerm> body) {
        if (!bind) return body.apply(operand);
        var read = new PirTerm.Var(hygienicName(base, avoid), type);
        return new PirTerm.Let(read.name(), operand, body.apply(read));
    }

    /**
     * Whether evaluating {@code term} has no effect and next to no cost, so that it can be read more than once: a variable,
     * a constant, or {@link #isConstantArithmetic constant arithmetic}, which the optimizer folds to a constant at each read.
     */
    private static boolean isTrivialOperand(PirTerm term) {
        return term instanceof PirTerm.Var && !isMethodRead(term) || term instanceof PirTerm.Const || isConstantArithmetic(term);
    }

    /**
     * Integer addition, subtraction or multiplication of integer constants, or of such terms: a negative literal such as
     * {@code -5} ({@code 0 - 5}, as the frontend lowers it), or {@code 1 + 2}. It cannot fail and has no effect. Bound, it
     * would fold to a constant that the optimizer does not copy into each read, so it is left to fold where it is read.
     */
    private static boolean isConstantArithmetic(PirTerm term) {
        return term instanceof PirTerm.App(PirTerm.App(PirTerm.Builtin(var fun), var left), var right)
                && (fun == DefaultFun.AddInteger || fun == DefaultFun.SubtractInteger || fun == DefaultFun.MultiplyInteger)
                && isIntegerConstant(left) && isIntegerConstant(right);
    }

    private static boolean isIntegerConstant(PirTerm term) {
        return term instanceof PirTerm.Const(Constant.IntegerConst _) || isConstantArithmetic(term);
    }

    /**
     * Whether {@code term} reads a method's binder: a method with no parameters, called. A method is bound under a qualified
     * name, in a namespace of their own, which no variable
     * can have. Such a read is no variable: if its body may fail, {@link DelayedMethodPass} makes it a call, so evaluating
     * it has an effect, and a term that reads it twice runs the body twice.
     */
    public static boolean isMethodRead(PirTerm term) {
        return term instanceof PirTerm.Var variable && variable.name().indexOf('.') > 0;
    }

    /**
     * Generate type-aware equality comparison.
     * Dispatches to the correct builtin based on operand type.
     */
    public static PirTerm equality(PirTerm left, PirTerm right, PirType leftType, boolean negate) {
        DefaultFun eqFun;
        if (leftType instanceof PirType.ByteStringType) {
            eqFun = DefaultFun.EqualsByteString;
        } else if (leftType instanceof PirType.StringType) {
            eqFun = DefaultFun.EqualsString;
        } else if (leftType instanceof PirType.BoolType) {
            // Bool equality: IfThenElse(a, b, !b)
            var eq = new PirTerm.IfThenElse(left, right,
                    new PirTerm.IfThenElse(right,
                            new PirTerm.Const(Constant.bool(false)),
                            new PirTerm.Const(Constant.bool(true))));
            if (negate) return new PirTerm.IfThenElse(eq,
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(true)));
            return eq;
        } else if (leftType instanceof PirType.DataType
                || leftType instanceof PirType.RecordType
                || leftType instanceof PirType.SumType) {
            eqFun = DefaultFun.EqualsData;
        } else {
            eqFun = DefaultFun.EqualsInteger; // default: integer
        }
        var result = builtinApp2(eqFun, left, right);
        if (negate) return new PirTerm.IfThenElse(result,
                new PirTerm.Const(Constant.bool(false)),
                new PirTerm.Const(Constant.bool(true)));
        return result;
    }

    /**
     * Generate PIR for extracting a field from a Constr-encoded Data value.
     * UnConstrData(data) → SndPair → TailList^n → HeadList → decode
     */
    public static PirTerm fieldExtraction(PirTerm data, int fieldIndex, PirType fieldType) {
        // SndPair(UnConstrData(data)) to get fields list
        var fields = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.SndPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));

        // TailList^fieldIndex then HeadList
        PirTerm current = fields;
        for (int i = 0; i < fieldIndex; i++) {
            current = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), current);
        }
        PirTerm rawField = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), current);

        // Decode based on field type
        return wrapDecode(rawField, fieldType);
    }

    /**
     * Wrap a raw Data term with the appropriate decode builtin for the target type.
     */
    public static PirTerm wrapDecode(PirTerm data, PirType targetType) {
        if (PirType.containsNativeOpaque(targetType)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch(
                    "Data decoding", new PirType.DataType(), targetType, null);
        }
        if (targetType instanceof PirType.IntegerType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), data);
        }
        if (targetType instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), data);
        }
        if (targetType instanceof PirType.ListType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), data);
        }
        if (targetType instanceof PirType.MapType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), data);
        }
        if (targetType instanceof PirType.BoolType) {
            // Bool encoded as Constr: False=Constr(0,[]), True=Constr(1,[])
            // Decode: FstPair(UnConstrData(data)) == 1
            var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));
            return builtinApp2(DefaultFun.EqualsInteger, tag,
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        }
        if (targetType instanceof PirType.StringType) {
            // String encoded as BData(EncodeUtf8(s)) in Data. Decode: DecodeUtf8(UnBData(data))
            var byteString = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), data);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.DecodeUtf8), byteString);
        }
        // For Data, RecordType, SumType, etc., pass through as raw Data
        return data;
    }

    /**
     * The type of a value whose type the lowering does not know, as opposed to a value known to be
     * Data (review F710): a {@code switch} expression whose arms have different, not all Data-shaped,
     * types. It equals {@code DataType} everywhere, so it changes no other decision; encoding it, or
     * taking it as a diamond component, is {@code JULC0012}, decided by identity.
     */
    public static final PirType UNKNOWN = new PirType.DataType();

    /** JULC0012 for a value of {@link #UNKNOWN} type that would be Data-encoded; located by {@link PirGenerator}. */
    public static void requireKnownType(PirType type) {
        if (type == UNKNOWN) throw CompilerTypeDiagnostics.unknownTypeEncoded(null);
    }

    /**
     * JULC0091 (ADR-068 D6, #298): a value of {@code type} is about to be Data-encoded, and a native
     * array has no Data encoding. The diagnostic has no position; the lowering attaches the
     * expression whose value is encoded.
     */
    public static void requireDataEncodable(PirType type) {
        var array = PirType.nativeArrayIn(type);
        if (array != null) {
            throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("The value encoded here", array, null);
        }
    }

    /**
     * Wrap a value with the appropriate encode builtin for the target type (inverse of wrapDecode).
     */
    public static PirTerm wrapEncode(PirTerm value, PirType type) {
        requireKnownType(type);
        requireDataEncodable(type);
        if (PirType.containsNativeOpaque(type)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch(
                    "Data encoding", type, new PirType.DataType(), null);
        }
        if (type instanceof PirType.IntegerType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), value);
        }
        if (type instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData), value);
        }
        if (type instanceof PirType.BoolType) {
            // Bool → ConstrData: False=Constr(0,[]), True=Constr(1,[])
            var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var trueData = builtinApp2(DefaultFun.ConstrData,
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)), nilData);
            var falseData = builtinApp2(DefaultFun.ConstrData,
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), nilData);
            return new PirTerm.IfThenElse(value, trueData, falseData);
        }
        if (type instanceof PirType.StringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EncodeUtf8), value));
        }
        if (type instanceof PirType.ListType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListData), value);
        }
        if (type instanceof PirType.MapType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), value);
        }
        // For Data, RecordType, SumType, etc., already Data — pass through
        return value;
    }

    /**
     * Generate PIR for list.size() — foldl(\acc _ -> acc + 1, 0, list).
     */
    static PirTerm generateListLength(PirTerm list) {
        var accVar = new PirTerm.Var("#acc__len", new PirType.IntegerType());
        var xVar = new PirTerm.Var("#_x__len", new PirType.DataType());
        var addOne = builtinApp2(DefaultFun.AddInteger, accVar,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var foldFn = new PirTerm.Lam("#acc__len", new PirType.IntegerType(),
                new PirTerm.Lam("#_x__len", new PirType.DataType(), addOne));
        return generateFoldl(foldFn, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), list);
    }

    /**
     * {@code String.length()}: the UTF-16 code units of {@code string}, which a script holds as Unicode scalars. Counted from
     * its UTF-8 bytes: a continuation byte ({@code 0x80..0xBF}) counts 0, a lead byte of a supplementary character
     * ({@code 0xF0} and up) counts 2, and any other byte counts 1. A constant folds to Java's value. No term is in the scope
     * of the binders here but {@code string}, in the first one's value, which that binder does not reach.
     */
    static PirTerm utf16Length(PirTerm string) {
        if (string instanceof PirTerm.Const constant && constant.value() instanceof Constant.StringConst text) {
            return new PirTerm.Const(Constant.integer(BigInteger.valueOf(text.value().length())));
        }
        var integer = new PirType.IntegerType();
        var utf8 = new PirTerm.Var("#utf8", new PirType.ByteStringType());
        var size = new PirTerm.Var("#utf8_size", integer);
        var index = new PirTerm.Var("#utf8_index", integer);
        var count = new PirTerm.Var("#utf8_count", integer);
        var unit = new PirTerm.Var("#utf8_byte", integer);
        var go = new PirTerm.Var("#utf8_go", new PirType.FunType(integer, new PirType.FunType(integer, integer)));
        var units = new PirTerm.IfThenElse(below(unit, 0xC0),
                new PirTerm.IfThenElse(below(unit, 0x80), integerConstant(1), integerConstant(0)),
                new PirTerm.IfThenElse(below(unit, 0xF0), integerConstant(1), integerConstant(2)));
        var step = new PirTerm.Let(unit.name(), builtinApp2(DefaultFun.IndexByteString, utf8, index),
                new PirTerm.App(new PirTerm.App(go, builtinApp2(DefaultFun.AddInteger, index, integerConstant(1))),
                        builtinApp2(DefaultFun.AddInteger, count, units)));
        var body = new PirTerm.IfThenElse(builtinApp2(DefaultFun.EqualsInteger, index, size), count, step);
        var function = new PirTerm.Lam(index.name(), integer, new PirTerm.Lam(count.name(), integer, body));
        var loop = new PirTerm.LetRec(List.of(new PirTerm.Binding(go.name(), function)),
                new PirTerm.App(new PirTerm.App(go, integerConstant(0)), integerConstant(0)));
        return new PirTerm.Let(utf8.name(), new PirTerm.App(new PirTerm.Builtin(DefaultFun.EncodeUtf8), string),
                new PirTerm.Let(size.name(), new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), utf8), loop));
    }

    private static PirTerm below(PirTerm term, int bound) {
        return builtinApp2(DefaultFun.LessThanInteger, term, integerConstant(bound));
    }

    private static PirTerm integerConstant(long value) {
        return new PirTerm.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    /**
     * Generate PIR for list.contains(target) — recursive search with typed equality.
     */
    static PirTerm generateListContains(PirTerm list, PirTerm target,
                                         PirType elemType, PirType targetType) {
        var lstVar = new PirTerm.Var("#lst_c", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("#go_c", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()), new PirType.BoolType()));
        // The list is evaluated inside the target's binder.
        var targetVar = new PirTerm.Var(hygienicName("#target_c", freeVariables(list)), targetType);
        var listVar = new PirTerm.Var("#list_c", new PirType.ListType(new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var hVar = new PirTerm.Var("#h_c", new PirType.DataType());

        // Build equality: decode list element, but only decode target if it's still Data
        PirTerm equalCheck = buildContainsEquality(hVar, targetVar, elemType, targetType);

        var recurse = new PirTerm.App(goVar, tailExpr);
        var innerIf = new PirTerm.IfThenElse(equalCheck,
                new PirTerm.Const(Constant.bool(true)), recurse);
        var letHead = new PirTerm.Let("#h_c", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck,
                new PirTerm.Const(Constant.bool(false)), letHead);

        var goBody = new PirTerm.Lam("#lst_c", new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding("#go_c", goBody);

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, listVar));

        return new PirTerm.Let(targetVar.name(), target,
                new PirTerm.Let("#list_c", list, search));
    }

    /**
     * Build equality check for contains(). Always decodes the list element (it's raw Data).
     * Only decodes the target if it's still Data (not already decoded by field extraction).
     */
    static PirTerm buildContainsEquality(PirTerm listElem, PirTerm target,
                                          PirType elemType, PirType targetType) {
        if (elemType instanceof PirType.ByteStringType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), listElem);
            var decodedTarget = (targetType instanceof PirType.ByteStringType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), target);
            return builtinApp2(DefaultFun.EqualsByteString, decodedElem, decodedTarget);
        }
        if (elemType instanceof PirType.IntegerType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), listElem);
            var decodedTarget = (targetType instanceof PirType.IntegerType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), target);
            return builtinApp2(DefaultFun.EqualsInteger, decodedElem, decodedTarget);
        }
        // Default: EqualsData — both are Data
        return builtinApp2(DefaultFun.EqualsData, listElem, target);
    }

    /**
     * Callback for pair list search body construction.
     * Called with the head pair variable, key variable, and recursive call on tail.
     */
    @FunctionalInterface
    public interface PairSearchBody {
        PirTerm build(PirTerm.Var headPair, PirTerm.Var keyVar, PirTerm recurseOnTail);
    }

    /**
     * Generate a LetRec search over a pair list, matching by key.
     * <p>
     * Produces:
     * <pre>{@code
     * Let ps_<suffix> = list in
     * Let k_<suffix> = key in
     * LetRec go_<suffix>(lst) =
     *     if null(lst) then baseCase
     *     else let h_<suffix> = head(lst) in
     *       bodyBuilder(h_<suffix>, k_<suffix>, go(tail(lst)))
     * in go_<suffix>(ps_<suffix>)
     * }</pre>
     *
     * @param suffix      unique suffix for variable names (e.g., "get", "ck", "del")
     * @param list        the pair list to search (scope term)
     * @param key         the key to search for (pre-encoded as Data)
     * @param baseCase    result when list is exhausted
     * @param bodyBuilder produces the body for the non-null case; it may reference only the
     *                    supplied variables and closed terms (caller terms belong in {@code key}
     *                    or {@code baseCase}, whose free variables the binders avoid)
     * @return the complete LetRec search term
     */
    public static PirTerm pairListSearch(String suffix, PirTerm list, PirTerm key,
                                          PirTerm baseCase, PairSearchBody bodyBuilder) {
        var pairType = new PirType.PairType(new PirType.DataType(), new PirType.DataType());
        var pairListType = new PirType.ListType(pairType);
        var avoid = freeVariables(key, baseCase);

        var pairsVar = new PirTerm.Var(hygienicName("#ps_" + suffix, avoid), pairListType);
        var keyVar = new PirTerm.Var(hygienicName("#k_" + suffix, avoid), new PirType.DataType());
        var lstVar = new PirTerm.Var(hygienicName("#lst_" + suffix, avoid), pairListType);
        var goVar = new PirTerm.Var(hygienicName("#go_" + suffix, avoid),
                new PirType.FunType(pairListType, new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var hVar = new PirTerm.Var(hygienicName("#h_" + suffix, avoid), pairType);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerBody = bodyBuilder.build(hVar, keyVar, recurse);
        var letHead = new PirTerm.Let(hVar.name(), headExpr, innerBody);
        var outerIf = new PirTerm.IfThenElse(nullCheck, baseCase, letHead);

        var goBody = new PirTerm.Lam(lstVar.name(), pairListType, outerIf);
        var binding = new PirTerm.Binding(goVar.name(), goBody);
        var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, pairsVar));

        return new PirTerm.Let(pairsVar.name(), list,
                new PirTerm.Let(keyVar.name(), key, search));
    }

    /**
     * Callback for regular list search body construction.
     * Called with the head element, target value, and recursive call on tail.
     */
    @FunctionalInterface
    public interface ListSearchBody {
        PirTerm build(PirTerm element, PirTerm target, PirTerm recurseOnTail);
    }

    /**
     * Generate a LetRec search over a regular Data list.
     * <p>
     * Produces:
     * <pre>{@code
     * Let xs_<suffix> = list in
     * Let t_<suffix> = target in
     * LetRec go_<suffix>(lst) =
     *     if null(lst) then baseCase
     *     else let e_<suffix> = head(lst) in
     *       bodyBuilder(e_<suffix>, t_<suffix>, go(tail(lst)))
     * in go_<suffix>(xs_<suffix>)
     * }</pre>
     *
     * @param suffix      unique suffix for variable names
     * @param list        the list to search
     * @param target      the target value (pre-encoded as Data)
     * @param baseCase    result when list is exhausted
     * @param bodyBuilder produces the body for the non-null case; it may reference only the
     *                    supplied variables and closed terms (caller terms belong in
     *                    {@code target} or {@code baseCase}, whose free variables the binders avoid)
     * @return the complete LetRec search term
     */
    public static PirTerm listSearch(String suffix, PirTerm list, PirTerm target,
                                      PirTerm baseCase, ListSearchBody bodyBuilder) {
        var dataListType = new PirType.ListType(new PirType.DataType());
        var avoid = freeVariables(target, baseCase);

        var xsVar = new PirTerm.Var(hygienicName("#xs_" + suffix, avoid), dataListType);
        var tVar = new PirTerm.Var(hygienicName("#t_" + suffix, avoid), new PirType.DataType());
        var lstVar = new PirTerm.Var(hygienicName("#lst_" + suffix, avoid), dataListType);
        var goVar = new PirTerm.Var(hygienicName("#go_" + suffix, avoid),
                new PirType.FunType(dataListType, new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var eVar = new PirTerm.Var(hygienicName("#e_" + suffix, avoid), new PirType.DataType());
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerBody = bodyBuilder.build(eVar, tVar, recurse);
        var letHead = new PirTerm.Let(eVar.name(), headExpr, innerBody);
        var outerIf = new PirTerm.IfThenElse(nullCheck, baseCase, letHead);

        var goBody = new PirTerm.Lam(lstVar.name(), dataListType, outerIf);
        var binding = new PirTerm.Binding(goVar.name(), goBody);
        var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, xsVar));

        return new PirTerm.Let(xsVar.name(), list,
                new PirTerm.Let(tVar.name(), target, search));
    }

    /** Check if a PIR term contains a Var reference with the given name (for self-recursion detection). */
    public static boolean containsVarRef(PirTerm term, String name) {
        return switch (term) {
            case PirTerm.Var v -> v.name().equals(name);
            case PirTerm.Let l -> containsVarRef(l.value(), name) || containsVarRef(l.body(), name);
            case PirTerm.LetRec lr -> lr.bindings().stream().anyMatch(b -> containsVarRef(b.value(), name))
                    || containsVarRef(lr.body(), name);
            case PirTerm.Lam lam -> containsVarRef(lam.body(), name);
            case PirTerm.App app -> containsVarRef(app.function(), name) || containsVarRef(app.argument(), name);
            case PirTerm.IfThenElse ite -> containsVarRef(ite.cond(), name)
                    || containsVarRef(ite.thenBranch(), name) || containsVarRef(ite.elseBranch(), name);
            case PirTerm.DataConstr dc -> dc.fields().stream().anyMatch(f -> containsVarRef(f, name));
            case PirTerm.IntegerCase c -> containsVarRef(c.scrutinee(), name)
                    || c.branches().stream().anyMatch(b -> containsVarRef(b, name));
            case PirTerm.PairMatch m -> containsVarRef(m.scrutinee(), name)
                    || (!m.firstName().equals(name) && !m.secondName().equals(name)
                        && containsVarRef(m.body(), name));
            case PirTerm.ListMatch m -> containsVarRef(m.scrutinee(), name)
                    || containsVarRef(m.nilBranch(), name) || containsVarRef(m.consBranch(), name);
            case PirTerm.DataMatch dm -> containsVarRef(dm.scrutinee(), name)
                    || dm.branches().stream().anyMatch(b -> containsVarRef(b.body(), name));
            case PirTerm.Trace t -> containsVarRef(t.message(), name) || containsVarRef(t.body(), name);
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> false;
        };
    }

    /** Convenience: None value (ConstrData(1, [])). */
    public static PirTerm mkNone() {
        var mkNil = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return builtinApp2(DefaultFun.ConstrData,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)), mkNil);
    }

    /** Convenience: Some(value) = ConstrData(0, [value]). */
    public static PirTerm mkSome(PirTerm value) {
        var mkNil = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var fields = builtinApp2(DefaultFun.MkCons, value, mkNil);
        return builtinApp2(DefaultFun.ConstrData,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)), fields);
    }

    /** Convenience: empty pair list (MkNilPairData(unit)). */
    public static PirTerm mkNilPairData() {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
    }

    /**
     * Generate PIR foldl using LetRec — same pattern as ListsLib.foldl.
     */
    static PirTerm generateFoldl(PirTerm f, PirTerm init, PirTerm list) {
        // f is applied under all three binders; init and list under go.
        var avoid = freeVariables(f, init, list);
        var accVar = new PirTerm.Var(hygienicName("#acc__f", avoid), new PirType.DataType());
        var lstVar = new PirTerm.Var(hygienicName("#lst__f", avoid), new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var(hygienicName("#go__f", avoid), new PirType.FunType(new PirType.DataType(),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()), new PirType.DataType())));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);

        var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
        var recurse = new PirTerm.App(new PirTerm.App(goVar, fApp), tailExpr);

        var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

        var goBody = new PirTerm.Lam(accVar.name(), new PirType.DataType(),
                new PirTerm.Lam(lstVar.name(), new PirType.ListType(new PirType.DataType()), ifExpr));
        var binding = new PirTerm.Binding(goVar.name(), goBody);

        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, init), list));
    }
}
