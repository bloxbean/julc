package com.bloxbean.cardano.julc.jrl.ast;

import java.util.List;

/**
 * JRL expression AST. Used in conditions, comparisons, arithmetic, and references.
 */
public sealed interface Expression {

    SourceRange sourceRange();

    record BinaryExpr(Expression left, BinaryOp op, Expression right,
                      SourceRange sourceRange) implements Expression {}

    record UnaryExpr(UnaryOp op, Expression operand,
                     SourceRange sourceRange) implements Expression {}

    record FieldAccessExpr(Expression target, String field,
                           SourceRange sourceRange) implements Expression {}

    record FunctionCallExpr(String name, List<Expression> args,
                            SourceRange sourceRange) implements Expression {}

    record VarRefExpr(String name, SourceRange sourceRange) implements Expression {}

    record IdentRefExpr(String name, SourceRange sourceRange) implements Expression {}

    record IntLiteralExpr(long value, SourceRange sourceRange) implements Expression {}

    record StringLiteralExpr(String value, SourceRange sourceRange) implements Expression {}

    record HexLiteralExpr(byte[] value, SourceRange sourceRange) implements Expression {}

    record BoolLiteralExpr(boolean value, SourceRange sourceRange) implements Expression {}

    record OwnAddressExpr(SourceRange sourceRange) implements Expression {}

    record OwnPolicyIdExpr(SourceRange sourceRange) implements Expression {}

    enum BinaryOp { EQ, NEQ, GT, GTE, LT, LTE, ADD, SUB, MUL, AND, OR }

    enum UnaryOp { NOT }
}
