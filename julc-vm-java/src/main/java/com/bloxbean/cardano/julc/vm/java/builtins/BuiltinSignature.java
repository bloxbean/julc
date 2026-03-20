package com.bloxbean.cardano.julc.vm.java.builtins;

/**
 * The shape of a builtin function: how many forces and arguments it requires.
 *
 * @param forceCount number of {@code Force} applications needed before any arguments
 * @param arity      number of value arguments needed to execute
 */
public record BuiltinSignature(int forceCount, int arity) {}
