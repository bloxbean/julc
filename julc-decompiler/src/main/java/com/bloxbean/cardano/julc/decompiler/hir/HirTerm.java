package com.bloxbean.cardano.julc.decompiler.hir;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;

import java.util.List;

/**
 * High-level Intermediate Representation for decompiled UPLC.
 * <p>
 * This is the core AST produced by the decompiler's pattern recognizers.
 * Each node represents a higher-level construct recovered from raw UPLC.
 */
public sealed interface HirTerm {

    /** Variable reference with a meaningful name. */
    record Var(String name, HirType type) implements HirTerm {}

    /** Let binding: {@code let name = value in body}. */
    record Let(String name, HirTerm value, HirTerm body) implements HirTerm {}

    /** Recursive let binding. */
    record LetRec(String name, HirTerm value, HirTerm body) implements HirTerm {}

    /** Integer literal. */
    record IntLiteral(java.math.BigInteger value) implements HirTerm {}

    /** ByteString literal. */
    record ByteStringLiteral(byte[] value) implements HirTerm {}

    /** String literal. */
    record StringLiteral(String value) implements HirTerm {}

    /** Boolean literal. */
    record BoolLiteral(boolean value) implements HirTerm {}

    /** Unit literal. */
    record UnitLiteral() implements HirTerm {}

    /** PlutusData literal. */
    record DataLiteral(com.bloxbean.cardano.julc.core.PlutusData value) implements HirTerm {}

    /** Lambda abstraction. */
    record Lambda(List<String> params, HirTerm body) implements HirTerm {
        public Lambda { params = List.copyOf(params); }
    }

    /** Function call (user-defined or recognized helper). */
    record FunCall(String name, List<HirTerm> args) implements HirTerm {
        public FunCall { args = List.copyOf(args); }
    }

    /** Direct builtin call. */
    record BuiltinCall(DefaultFun fun, List<HirTerm> args) implements HirTerm {
        public BuiltinCall { args = List.copyOf(args); }
    }

    /** If-then-else expression. */
    record If(HirTerm condition, HirTerm thenBranch, HirTerm elseBranch) implements HirTerm {}

    /** Switch on a constructor tag (data match or SOP case). */
    record Switch(HirTerm scrutinee, String typeName, List<SwitchBranch> branches) implements HirTerm {
        public Switch { branches = List.copyOf(branches); }
    }

    /** A single branch in a switch statement. */
    record SwitchBranch(int tag, String constructorName, List<String> fieldNames, HirTerm body) {
        public SwitchBranch { fieldNames = List.copyOf(fieldNames); }
    }

    /** For-each loop over a list. */
    record ForEach(String itemVar, HirTerm list, String accVar, HirTerm init, HirTerm body) implements HirTerm {}

    /** While loop. */
    record While(HirTerm condition, List<String> accVars, List<HirTerm> accInits, HirTerm body) implements HirTerm {
        public While { accVars = List.copyOf(accVars); accInits = List.copyOf(accInits); }
    }

    /** Field access on a known record type. */
    record FieldAccess(HirTerm record, String fieldName, int fieldIndex, String typeName) implements HirTerm {}

    /** Constructor call. */
    record Constructor(String typeName, int tag, List<HirTerm> fields) implements HirTerm {
        public Constructor { fields = List.copyOf(fields); }
    }

    /** Instance method call (e.g., list.head(), value.lovelaceOf()). */
    record MethodCall(HirTerm receiver, String methodName, List<HirTerm> args) implements HirTerm {
        public MethodCall { args = List.copyOf(args); }
    }

    /** Data encoding (e.g., IData, BData, ConstrData). */
    record DataEncode(DefaultFun encoder, HirTerm operand) implements HirTerm {}

    /** Data decoding (e.g., UnIData, UnBData, UnConstrData). */
    record DataDecode(DefaultFun decoder, HirTerm operand) implements HirTerm {}

    /** Trace call. */
    record Trace(HirTerm message, HirTerm body) implements HirTerm {}

    /** Error term. */
    record Error() implements HirTerm {}

    /** Fallback for unrecognized UPLC patterns — preserves the raw term. */
    record RawUplc(Term term) implements HirTerm {}

    /** Constant from the original UPLC (not yet classified as a specific literal type). */
    record ConstValue(Constant value) implements HirTerm {}

    /** List literal built from MkCons/MkNilData. */
    record ListLiteral(List<HirTerm> elements) implements HirTerm {
        public ListLiteral { elements = List.copyOf(elements); }
    }
}
