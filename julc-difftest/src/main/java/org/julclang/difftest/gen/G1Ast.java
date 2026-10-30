package org.julclang.difftest.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * The typed AST of a G1 program (ADR-066 D2): {@link G1}'s productions build it and {@link G1Printer} prints it as
 * Java. Each node keeps the generated types it is about, so that a second printer (M4's twin, D8) can print the
 * same program with instrumented sites.
 */
final class G1Ast {

    private G1Ast() {
    }

    // ---------------------------------------------------------------- types and bindings

    enum Kind { BIG, BOOL, RECORD, SEALED }

    /** A generated type: {@code BigInteger}, {@code boolean}, a record (standalone or a variant), or a sealed family. */
    static final class Type {
        final Kind kind;
        final String name;
        final List<Component> components = new ArrayList<>();
        /** A family's variants in {@code permits} order: a variant's tag is its index. */
        final List<Type> variants = new ArrayList<>();
        Type family;
        int depth;

        Type(Kind kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        boolean isValue() {
            return kind == Kind.RECORD || kind == Kind.SEALED;
        }

        /** Whether a value of this type can stand where {@code target} is expected. */
        boolean fits(Type target) {
            return this == target || target.kind == Kind.SEALED && family == target;
        }

        /** The constructor tag of a record: its index in its family's {@code permits} clause, or 0. */
        int tag() {
            return family == null ? 0 : family.variants.indexOf(this);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final Type BIG = new Type(Kind.BIG, "BigInteger");
    static final Type BOOL = new Type(Kind.BOOL, "boolean");
    /**
     * The type of a name being declared: a local's scope includes its own initializer, where it hides a field of
     * the same name but cannot be read yet. No expression has this type.
     */
    static final Type UNREADABLE = new Type(Kind.BOOL, "unreadable");

    record Component(String name, Type type) {
    }

    /**
     * A variable in scope (ADR-066 D2, bindings). {@code assignable}: a method-level accumulator loops may update;
     * {@code guardEligible}: a parameter, pattern binding, field or a local that is never assigned, which M3's guards
     * may read; a counter is a {@code long} read as {@code BigInteger.valueOf}.
     */
    record Var(String name, Type type, boolean counter, boolean assignable, boolean guardEligible, boolean field) {
    }

    // ---------------------------------------------------------------- expressions

    sealed interface Expr permits IntConst, BoolConst, Read, Arith, Scale, Cond, Compare, Logic, Not, Equals, New,
            Access, Call, Trace, Fail {
    }

    /** {@code BigInteger.valueOf(value)}. */
    record IntConst(int value) implements Expr {
    }

    record BoolConst(boolean value) implements Expr {
    }

    record Read(Var var) implements Expr {
    }

    /** {@code left.add(right)} or {@code left.subtract(right)}. */
    record Arith(Expr left, String method, Expr right) implements Expr {
    }

    /** {@code left.multiply(BigInteger.valueOf(factor))}. */
    record Scale(Expr left, int factor) implements Expr {
    }

    /** {@code (test ? then : otherwise)}. */
    record Cond(Expr test, Expr then, Expr otherwise) implements Expr {
    }

    /** {@code left.compareTo(right) operator 0}. */
    record Compare(Expr left, Expr right, String operator) implements Expr {
    }

    /** {@code (left && right)} or {@code (left || right)}. */
    record Logic(Expr left, String operator, Expr right) implements Expr {
    }

    record Not(Expr operand) implements Expr {
    }

    record Equals(Expr receiver, Expr argument) implements Expr {
    }

    record New(Type record, List<Expr> arguments) implements Expr {
    }

    /** {@code var.c1().c2()}: a component read from a variable, one or two deep. */
    record Access(Var var, List<String> components) implements Expr {
    }

    record Call(String helper, Type result, List<Expr> arguments) implements Expr {
    }

    /** {@code t("label", value)}. */
    record Trace(String label, Expr value) implements Expr {
    }

    /** {@code fail(value)}. */
    record Fail(Expr value) implements Expr {
    }

    // ---------------------------------------------------------------- statements

    sealed interface Stmt permits Declare, Assign, If, BreakIf, IfInstance, Return, While, ForEach {
    }

    record Declare(Type type, String name, Expr init) implements Stmt {
    }

    record Assign(String target, Expr value) implements Stmt {
    }

    /** {@code if (test) { then } else { otherwise }}; no {@code else} when {@code otherwise} is null. */
    record If(Expr test, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
    }

    /** {@code if (test) { break; }}. */
    record BreakIf(Expr test) implements Stmt {
    }

    /** {@code if (subject instanceof variant binding) { body }}. */
    record IfInstance(Var subject, Type variant, String binding, List<Stmt> body) implements Stmt {
    }

    record Return(Expr value) implements Stmt {
    }

    /** {@code long counter = 0; while (counter < bound) { body counter = counter + 1; }}. */
    record While(String counter, int bound, List<Stmt> body) implements Stmt {
    }

    /** {@code for (var element : xs) { body }}. */
    record ForEach(String element, List<Stmt> body) implements Stmt {
    }

    // ---------------------------------------------------------------- declarations

    record Field(Type type, String name, Expr init) {
    }

    record Helper(String name, List<Var> parameters, Type result, Expr body) {
    }

    /** {@code public static result m(BigInteger a, BigInteger b, JulcList<BigInteger> xs, parameters…)}. */
    record Method(Type result, List<Var> parameters, List<Stmt> body) {
    }

    /**
     * A whole program: its class, its type declarations in printed order (a sealed family prints its interface, a
     * record its record declaration), its fields, its generated helpers and its entry method.
     */
    record Unit(String className, List<Type> declarations, List<Field> fields, List<Helper> helpers, Method method) {
    }
}
