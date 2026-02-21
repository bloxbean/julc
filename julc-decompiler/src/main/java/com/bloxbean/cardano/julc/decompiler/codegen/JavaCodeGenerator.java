package com.bloxbean.cardano.julc.decompiler.codegen;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates JuLC-style Java source code from typed HIR.
 */
public final class JavaCodeGenerator {

    private static final HexFormat HEX = HexFormat.of();

    private JavaCodeGenerator() {}

    /**
     * Generate Java source code from a typed HIR tree.
     */
    public static String generate(HirTerm hir, ScriptAnalyzer.ScriptStats stats) {
        var gen = new Generator();
        gen.generateClass(hir, stats);
        return gen.toString();
    }

    private static class Generator {
        private final StringBuilder sb = new StringBuilder();
        private final Set<String> imports = new LinkedHashSet<>();
        private int indent = 0;

        void generateClass(HirTerm hir, ScriptAnalyzer.ScriptStats stats) {
            // Collect imports first by traversing the tree
            collectImports(hir);

            // Package
            sb.append("package com.example.decompiled;\n\n");

            // Imports
            imports.add("com.bloxbean.cardano.julc.core.Builtins");
            imports.add("java.math.BigInteger");
            for (String imp : imports) {
                sb.append("import ").append(imp).append(";\n");
            }
            sb.append('\n');

            // Class header
            sb.append("/**\n");
            sb.append(" * Decompiled from ").append(stats.plutusVersion()).append(" script\n");
            sb.append(" * Program version: ").append(stats.programVersion()).append("\n");
            sb.append(" * Total AST nodes: ").append(stats.totalNodes()).append("\n");
            sb.append(" * Builtins used: ").append(stats.builtinsUsed().size()).append("\n");
            sb.append(" */\n");
            sb.append("public class DecompiledValidator {\n\n");
            indent++;

            // Main method
            emitIndent();
            sb.append("public static boolean validate(");
            if (hir instanceof HirTerm.Lambda lam) {
                for (int i = 0; i < lam.params().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("PlutusData ").append(lam.params().get(i));
                }
                sb.append(") {\n");
                indent++;
                emitBody(lam.body());
                indent--;
            } else {
                sb.append("PlutusData ctx) {\n");
                indent++;
                emitBody(hir);
                indent--;
            }
            emitIndent();
            sb.append("}\n");

            indent--;
            sb.append("}\n");
        }

        void emitBody(HirTerm term) {
            switch (term) {
                case HirTerm.Let let -> {
                    emitIndent();
                    sb.append("var ").append(let.name()).append(" = ");
                    emitExpr(let.value());
                    sb.append(";\n");
                    emitBody(let.body());
                }

                case HirTerm.LetRec letRec -> {
                    emitIndent();
                    sb.append("// Recursive binding: ").append(letRec.name()).append("\n");
                    emitBody(letRec.body());
                }

                case HirTerm.If iff -> {
                    emitIndent();
                    sb.append("if (");
                    emitExpr(iff.condition());
                    sb.append(") {\n");
                    indent++;
                    emitBody(iff.thenBranch());
                    indent--;
                    emitIndent();
                    sb.append("} else {\n");
                    indent++;
                    emitBody(iff.elseBranch());
                    indent--;
                    emitIndent();
                    sb.append("}\n");
                }

                case HirTerm.Switch sw -> {
                    emitIndent();
                    sb.append("switch (");
                    emitExpr(sw.scrutinee());
                    sb.append(") {\n");
                    indent++;
                    for (var branch : sw.branches()) {
                        emitIndent();
                        sb.append("case ").append(branch.constructorName());
                        if (!branch.fieldNames().isEmpty()) {
                            sb.append("(");
                            sb.append(String.join(", ", branch.fieldNames()));
                            sb.append(")");
                        }
                        sb.append(" -> {\n");
                        indent++;
                        emitBody(branch.body());
                        indent--;
                        emitIndent();
                        sb.append("}\n");
                    }
                    indent--;
                    emitIndent();
                    sb.append("}\n");
                }

                case HirTerm.ForEach fe -> {
                    emitIndent();
                    sb.append("for (var ").append(fe.itemVar()).append(" : ");
                    emitExpr(fe.list());
                    sb.append(") {\n");
                    indent++;
                    emitBody(fe.body());
                    indent--;
                    emitIndent();
                    sb.append("}\n");
                }

                case HirTerm.While w -> {
                    emitIndent();
                    sb.append("while (");
                    emitExpr(w.condition());
                    sb.append(") {\n");
                    indent++;
                    emitBody(w.body());
                    indent--;
                    emitIndent();
                    sb.append("}\n");
                }

                case HirTerm.Trace tr -> {
                    emitIndent();
                    sb.append("Builtins.trace(");
                    emitExpr(tr.message());
                    sb.append(");\n");
                    emitBody(tr.body());
                }

                case HirTerm.Error _ -> {
                    emitIndent();
                    sb.append("Builtins.error(); // reject transaction\n");
                }

                default -> {
                    // Expression in statement position — return it
                    emitIndent();
                    sb.append("return ");
                    emitExpr(term);
                    sb.append(";\n");
                }
            }
        }

        // --- Expression complexity helpers ---

        /** Check if an expression is "simple" enough to stay on one line. */
        boolean isSimpleExpr(HirTerm term) {
            return switch (term) {
                case HirTerm.Var _, HirTerm.IntLiteral _, HirTerm.BoolLiteral _,
                     HirTerm.UnitLiteral _, HirTerm.StringLiteral _,
                     HirTerm.Error _ -> true;
                case HirTerm.ByteStringLiteral bs -> bs.value().length <= 32;
                case HirTerm.DataLiteral _ -> true;
                case HirTerm.FieldAccess fa -> isSimpleExpr(fa.record());
                case HirTerm.DataEncode de -> isSimpleExpr(de.operand());
                case HirTerm.DataDecode dd -> isSimpleExpr(dd.operand());
                case HirTerm.BuiltinCall bc -> bc.args().size() <= 2
                        && bc.args().stream().allMatch(this::isSimpleExpr);
                case HirTerm.FunCall fc -> fc.args().isEmpty()
                        || (fc.args().size() <= 2 && fc.args().stream().allMatch(this::isSimpleExpr));
                default -> false;
            };
        }

        /** Estimate the inline length of an expression (returns -1 if too complex). */
        int estimateLength(HirTerm term) {
            return switch (term) {
                case HirTerm.Var v -> v.name().length();
                case HirTerm.IntLiteral i -> i.value().toString().length() + 1;
                case HirTerm.BoolLiteral _ -> 5;
                case HirTerm.UnitLiteral _ -> 2;
                case HirTerm.StringLiteral s -> s.value().length() + 2;
                case HirTerm.ByteStringLiteral bs -> bs.value().length * 2 + 30;
                case HirTerm.DataLiteral _ -> 16;
                case HirTerm.Error _ -> 15;
                case HirTerm.FieldAccess fa -> {
                    int r = estimateLength(fa.record());
                    yield r < 0 ? -1 : r + fa.fieldName().length() + 3;
                }
                case HirTerm.BuiltinCall bc -> {
                    int total = 10 + builtinToJava(bc.fun()).length();
                    for (var arg : bc.args()) {
                        int a = estimateLength(arg);
                        if (a < 0) yield -1;
                        total += a + 2;
                    }
                    yield total;
                }
                case HirTerm.FunCall fc -> {
                    int total = fc.name().length() + 2;
                    for (var arg : fc.args()) {
                        int a = estimateLength(arg);
                        if (a < 0) yield -1;
                        total += a + 2;
                    }
                    yield total;
                }
                default -> -1; // complex
            };
        }

        void emitExpr(HirTerm term) {
            switch (term) {
                case HirTerm.Var v -> sb.append(v.name());

                case HirTerm.IntLiteral i -> {
                    if (i.value().bitLength() < 63) {
                        sb.append(i.value()).append("L");
                    } else {
                        sb.append("new BigInteger(\"").append(i.value()).append("\")");
                    }
                }

                case HirTerm.ByteStringLiteral bs -> {
                    if (bs.value().length == 0) {
                        sb.append("new byte[0]");
                    } else {
                        sb.append("HexFormat.of().parseHex(\"").append(HEX.formatHex(bs.value())).append("\")");
                    }
                }

                case HirTerm.StringLiteral s -> {
                    sb.append('"');
                    sb.append(s.value()
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t"));
                    sb.append('"');
                }

                case HirTerm.BoolLiteral b -> sb.append(b.value() ? "true" : "false");
                case HirTerm.UnitLiteral _ -> sb.append("()");

                case HirTerm.DataLiteral dl -> {
                    // Show data literal as PlutusData construction
                    emitDataLiteral(dl);
                }

                case HirTerm.Lambda lam -> emitLambdaExpr(lam);

                case HirTerm.BuiltinCall bc -> emitBuiltinCall(bc);

                case HirTerm.FunCall fc -> {
                    sb.append(fc.name()).append("(");
                    emitArgListWrapped(fc.args());
                    sb.append(")");
                }

                case HirTerm.If iff -> {
                    if (isSimpleExpr(iff.thenBranch()) && isSimpleExpr(iff.elseBranch())) {
                        emitExpr(iff.condition());
                        sb.append(" ? ");
                        emitExpr(iff.thenBranch());
                        sb.append(" : ");
                        emitExpr(iff.elseBranch());
                    } else {
                        // Multi-line ternary
                        emitExpr(iff.condition());
                        sb.append("\n");
                        indent++;
                        emitIndent();
                        sb.append("? ");
                        emitExpr(iff.thenBranch());
                        sb.append("\n");
                        emitIndent();
                        sb.append(": ");
                        emitExpr(iff.elseBranch());
                        indent--;
                    }
                }

                case HirTerm.FieldAccess fa -> {
                    emitExpr(fa.record());
                    sb.append(".").append(fa.fieldName()).append("()");
                }

                case HirTerm.Constructor c -> {
                    sb.append("new ").append(c.typeName()).append("(");
                    emitArgListWrapped(c.fields());
                    sb.append(")");
                }

                case HirTerm.MethodCall mc -> {
                    emitExpr(mc.receiver());
                    sb.append(".").append(mc.methodName()).append("(");
                    emitArgListWrapped(mc.args());
                    sb.append(")");
                }

                case HirTerm.DataEncode de -> {
                    sb.append("Builtins.").append(encoderName(de.encoder())).append("(");
                    emitExpr(de.operand());
                    sb.append(")");
                }

                case HirTerm.DataDecode dd -> {
                    sb.append("Builtins.").append(decoderName(dd.decoder())).append("(");
                    emitExpr(dd.operand());
                    sb.append(")");
                }

                case HirTerm.Trace tr -> {
                    sb.append("Builtins.trace(");
                    emitExpr(tr.message());
                    sb.append(", ");
                    emitExpr(tr.body());
                    sb.append(")");
                }

                case HirTerm.Let let -> emitLetExpr(let);
                case HirTerm.LetRec letRec -> emitLetRecExpr(letRec);
                case HirTerm.Switch sw -> emitSwitchExpr(sw);

                case HirTerm.ConstValue cv -> sb.append("/* const ").append(cv.value().type()).append(" */");
                case HirTerm.Error _ -> sb.append("Builtins.error()");
                case HirTerm.RawUplc _ -> sb.append("/* unrecognized UPLC */");

                case HirTerm.ListLiteral ll -> {
                    sb.append("List.of(");
                    emitArgListWrapped(ll.elements());
                    sb.append(")");
                }

                case HirTerm.ForEach fe -> sb.append("/* for-each loop */");
                case HirTerm.While w -> sb.append("/* while loop */");
            }
        }

        // --- Complex expression helpers ---

        void emitLambdaExpr(HirTerm.Lambda lam) {
            // Params
            if (lam.params().size() == 1) {
                sb.append(lam.params().getFirst());
            } else {
                sb.append("(").append(String.join(", ", lam.params())).append(")");
            }
            sb.append(" -> ");

            // Simple body: inline
            if (isSimpleExpr(lam.body())) {
                emitExpr(lam.body());
                return;
            }

            // Complex body: use block
            sb.append("{\n");
            indent++;
            emitBody(lam.body());
            indent--;
            emitIndent();
            sb.append("}");
        }

        void emitLetExpr(HirTerm.Let let) {
            // Emit let bindings as a block that introduces local variables
            sb.append("((java.util.function.Supplier<Object>) () -> {\n");
            indent++;
            // Emit the binding chain
            emitLetBindingChain(let);
            indent--;
            emitIndent();
            sb.append("}).get()");
        }

        void emitLetBindingChain(HirTerm term) {
            if (term instanceof HirTerm.Let let) {
                emitIndent();
                sb.append("var ").append(let.name()).append(" = ");
                if (isSimpleExpr(let.value()) || estimateLength(let.value()) > 0 && estimateLength(let.value()) < 60) {
                    emitExpr(let.value());
                } else {
                    // Complex value — multi-line
                    sb.append("\n");
                    indent++;
                    emitIndent();
                    emitExpr(let.value());
                    indent--;
                }
                sb.append(";\n");
                emitLetBindingChain(let.body());
            } else if (term instanceof HirTerm.LetRec letRec) {
                emitIndent();
                sb.append("// recursive: ").append(letRec.name()).append("\n");
                emitLetBindingChain(letRec.body());
            } else {
                emitIndent();
                sb.append("return ");
                emitExpr(term);
                sb.append(";\n");
            }
        }

        void emitLetRecExpr(HirTerm.LetRec letRec) {
            sb.append("((java.util.function.Supplier<Object>) () -> {\n");
            indent++;
            emitIndent();
            sb.append("// recursive: ").append(letRec.name()).append("\n");
            emitLetBindingChain(letRec.body());
            indent--;
            emitIndent();
            sb.append("}).get()");
        }

        void emitSwitchExpr(HirTerm.Switch sw) {
            sb.append("switch (");
            emitExpr(sw.scrutinee());
            sb.append(") {\n");
            indent++;
            for (var branch : sw.branches()) {
                emitIndent();
                sb.append("case ").append(branch.constructorName());
                if (!branch.fieldNames().isEmpty()) {
                    sb.append("(").append(String.join(", ", branch.fieldNames())).append(")");
                }
                sb.append(" -> {\n");
                indent++;
                emitBody(branch.body());
                indent--;
                emitIndent();
                sb.append("}\n");
            }
            indent--;
            emitIndent();
            sb.append("}");
        }

        void emitDataLiteral(HirTerm.DataLiteral dl) {
            var data = dl.value();
            switch (data) {
                case com.bloxbean.cardano.julc.core.PlutusData.IntData i ->
                        sb.append("PlutusData.integer(").append(i.value()).append(")");
                case com.bloxbean.cardano.julc.core.PlutusData.BytesData b -> {
                    byte[] bytes = b.value();
                    if (bytes.length == 0) {
                        sb.append("PlutusData.bytes(new byte[0])");
                    } else if (bytes.length <= 40) {
                        sb.append("PlutusData.bytes(HexFormat.of().parseHex(\"")
                                .append(HEX.formatHex(bytes)).append("\"))");
                    } else {
                        // Truncate long byte strings
                        sb.append("PlutusData.bytes(HexFormat.of().parseHex(\"")
                                .append(HEX.formatHex(bytes, 0, 20)).append("...\"))");
                        sb.append(" /* ").append(bytes.length).append(" bytes */");
                    }
                }
                case com.bloxbean.cardano.julc.core.PlutusData.ListData l -> {
                    if (l.items().isEmpty()) {
                        sb.append("PlutusData.list()");
                    } else {
                        sb.append("PlutusData.list(/* ").append(l.items().size()).append(" items */)");
                    }
                }
                case com.bloxbean.cardano.julc.core.PlutusData.MapData m -> {
                    if (m.entries().isEmpty()) {
                        sb.append("PlutusData.map()");
                    } else {
                        sb.append("PlutusData.map(/* ").append(m.entries().size()).append(" entries */)");
                    }
                }
                case com.bloxbean.cardano.julc.core.PlutusData.ConstrData c -> {
                    sb.append("PlutusData.constr(").append(c.tag());
                    if (!c.fields().isEmpty()) {
                        sb.append(", /* ").append(c.fields().size()).append(" fields */");
                    }
                    sb.append(")");
                }
            }
        }

        void emitBuiltinCall(HirTerm.BuiltinCall bc) {
            String name = builtinToJava(bc.fun());
            List<HirTerm> args = bc.args();

            // Binary operators
            if (args.size() == 2 && isBinaryOp(bc.fun())) {
                sb.append("(");
                emitExpr(args.get(0));
                sb.append(" ").append(binaryOpSymbol(bc.fun())).append(" ");
                emitExpr(args.get(1));
                sb.append(")");
                return;
            }

            // Method-style for some builtins
            sb.append("Builtins.").append(name).append("(");
            emitArgListWrapped(args);
            sb.append(")");
        }

        void emitArgList(List<HirTerm> args) {
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                emitExpr(args.get(i));
            }
        }

        /** Emit argument list, wrapping to multiple lines if args are complex or numerous. */
        void emitArgListWrapped(List<HirTerm> args) {
            if (args.isEmpty()) return;

            // Check if all args fit on one line
            boolean allSimple = args.stream().allMatch(this::isSimpleExpr);
            int totalEstimate = args.stream().mapToInt(this::estimateLength).sum();
            boolean fitsOneLine = allSimple && totalEstimate >= 0 && totalEstimate < 80 && args.size() <= 4;

            if (fitsOneLine) {
                emitArgList(args);
                return;
            }

            // Multi-line: one arg per line
            sb.append("\n");
            indent++;
            for (int i = 0; i < args.size(); i++) {
                emitIndent();
                emitExpr(args.get(i));
                if (i < args.size() - 1) sb.append(",");
                sb.append("\n");
            }
            indent--;
            emitIndent();
        }

        void emitIndent() {
            sb.append("    ".repeat(indent));
        }

        void collectImports(HirTerm term) {
            switch (term) {
                case HirTerm.Let let -> { collectImports(let.value()); collectImports(let.body()); }
                case HirTerm.LetRec lr -> { collectImports(lr.value()); collectImports(lr.body()); }
                case HirTerm.If iff -> { collectImports(iff.condition()); collectImports(iff.thenBranch()); collectImports(iff.elseBranch()); }
                case HirTerm.Switch sw -> { collectImports(sw.scrutinee()); sw.branches().forEach(b -> collectImports(b.body())); }
                case HirTerm.Lambda lam -> collectImports(lam.body());
                case HirTerm.FunCall fc -> fc.args().forEach(this::collectImports);
                case HirTerm.BuiltinCall bc -> bc.args().forEach(this::collectImports);
                case HirTerm.FieldAccess fa -> collectImports(fa.record());
                case HirTerm.Constructor c -> c.fields().forEach(this::collectImports);
                case HirTerm.MethodCall mc -> { collectImports(mc.receiver()); mc.args().forEach(this::collectImports); }
                case HirTerm.DataEncode de -> collectImports(de.operand());
                case HirTerm.DataDecode dd -> collectImports(dd.operand());
                case HirTerm.Trace tr -> { collectImports(tr.message()); collectImports(tr.body()); }
                case HirTerm.ByteStringLiteral _ -> imports.add("java.util.HexFormat");
                default -> {}
            }
        }

        static String builtinToJava(DefaultFun fun) {
            return switch (fun) {
                case AddInteger -> "addInteger";
                case SubtractInteger -> "subtractInteger";
                case MultiplyInteger -> "multiplyInteger";
                case DivideInteger -> "divideInteger";
                case QuotientInteger -> "quotientInteger";
                case RemainderInteger -> "remainderInteger";
                case ModInteger -> "modInteger";
                case EqualsInteger -> "equalsInteger";
                case LessThanInteger -> "lessThanInteger";
                case LessThanEqualsInteger -> "lessThanEqualsInteger";
                case AppendByteString -> "appendByteString";
                case EqualsByteString -> "equalsByteString";
                case LessThanByteString -> "lessThanByteString";
                case LessThanEqualsByteString -> "lessThanEqualsByteString";
                case Sha2_256 -> "sha2_256";
                case Sha3_256 -> "sha3_256";
                case Blake2b_256 -> "blake2b_256";
                case VerifyEd25519Signature -> "verifyEd25519Signature";
                case EqualsString -> "equalsString";
                case EqualsData -> "equalsData";
                case NullList -> "nullList";
                case HeadList -> "headList";
                case TailList -> "tailList";
                case FstPair -> "fstPair";
                case SndPair -> "sndPair";
                case MkCons -> "mkCons";
                case LengthOfByteString -> "lengthOfByteString";
                case SliceByteString -> "sliceByteString";
                case IndexByteString -> "indexByteString";
                case ConsByteString -> "consByteString";
                case AppendString -> "appendString";
                case EncodeUtf8 -> "encodeUtf8";
                case DecodeUtf8 -> "decodeUtf8";
                case Trace -> "trace";
                case ConstrData -> "constrData";
                case UnConstrData -> "unConstrData";
                case MapData -> "mapData";
                case UnMapData -> "unMapData";
                case ListData -> "listData";
                case UnListData -> "unListData";
                case IData -> "iData";
                case UnIData -> "unIData";
                case BData -> "bData";
                case UnBData -> "unBData";
                case MkPairData -> "mkPairData";
                case MkNilData -> "mkNilData";
                case MkNilPairData -> "mkNilPairData";
                case SerialiseData -> "serialiseData";
                default -> {
                    String name = fun.name();
                    yield Character.toLowerCase(name.charAt(0)) + name.substring(1);
                }
            };
        }

        static boolean isBinaryOp(DefaultFun fun) {
            return switch (fun) {
                case EqualsInteger, EqualsData, EqualsByteString, EqualsString,
                     LessThanInteger, LessThanEqualsInteger,
                     LessThanByteString, LessThanEqualsByteString -> true;
                default -> false;
            };
        }

        static String binaryOpSymbol(DefaultFun fun) {
            return switch (fun) {
                case EqualsInteger, EqualsData, EqualsByteString, EqualsString -> "==";
                case LessThanInteger, LessThanByteString -> "<";
                case LessThanEqualsInteger, LessThanEqualsByteString -> "<=";
                default -> "??";
            };
        }

        static String encoderName(DefaultFun fun) {
            return switch (fun) {
                case IData -> "iData";
                case BData -> "bData";
                case ConstrData -> "constrData";
                case MapData -> "mapData";
                case ListData -> "listData";
                default -> fun.name();
            };
        }

        static String decoderName(DefaultFun fun) {
            return switch (fun) {
                case UnIData -> "unIData";
                case UnBData -> "unBData";
                case UnConstrData -> "unConstrData";
                case UnMapData -> "unMapData";
                case UnListData -> "unListData";
                default -> fun.name();
            };
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
