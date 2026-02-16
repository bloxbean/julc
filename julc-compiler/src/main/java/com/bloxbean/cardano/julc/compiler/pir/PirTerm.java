package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.util.List;

/**
 * PIR (Plutus Intermediate Representation) term AST.
 * A typed lambda calculus between Java and UPLC.
 */
public sealed interface PirTerm {

    record Var(String name, PirType type) implements PirTerm {}
    record Let(String name, PirTerm value, PirTerm body) implements PirTerm {}
    record LetRec(List<Binding> bindings, PirTerm body) implements PirTerm {
        public LetRec { bindings = List.copyOf(bindings); }
    }
    record Lam(String param, PirType paramType, PirTerm body) implements PirTerm {}
    record App(PirTerm function, PirTerm argument) implements PirTerm {}
    record Const(Constant value) implements PirTerm {}
    record Builtin(DefaultFun fun) implements PirTerm {}
    record IfThenElse(PirTerm cond, PirTerm thenBranch, PirTerm elseBranch) implements PirTerm {}
    record DataConstr(int tag, PirType dataType, List<PirTerm> fields) implements PirTerm {
        public DataConstr { fields = List.copyOf(fields); }
    }
    record DataMatch(PirTerm scrutinee, List<MatchBranch> branches) implements PirTerm {
        public DataMatch { branches = List.copyOf(branches); }
    }
    record Error(PirType type) implements PirTerm {}
    record Trace(PirTerm message, PirTerm body) implements PirTerm {}

    record Binding(String name, PirTerm value) {}
    record MatchBranch(String constructorName, List<String> bindings, List<PirType> bindingTypes, PirTerm body, String patternVar) {
        public MatchBranch { bindings = List.copyOf(bindings); bindingTypes = List.copyOf(bindingTypes); }
        /** Backward-compatible constructor (no pattern var) */
        public MatchBranch(String constructorName, List<String> bindings, List<PirType> bindingTypes, PirTerm body) {
            this(constructorName, bindings, bindingTypes, body, null);
        }
        /** Backward-compatible constructor (no type info, no pattern var — fields treated as Data) */
        public MatchBranch(String constructorName, List<String> bindings, PirTerm body) {
            this(constructorName, bindings, bindings.stream().map(_ -> (PirType) new PirType.DataType()).toList(), body, null);
        }
    }
}
