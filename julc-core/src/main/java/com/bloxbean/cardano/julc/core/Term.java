package com.bloxbean.cardano.julc.core;

import java.util.List;
import java.util.Objects;

/**
 * The UPLC (Untyped Plutus Lambda Calculus) term representation.
 * <p>
 * This is the core AST used for all Plutus scripts on Cardano. Each variant
 * corresponds to a construct in the UPLC specification:
 * <ul>
 *   <li>{@link Var} — variable reference (De Bruijn indexed)</li>
 *   <li>{@link Lam} — lambda abstraction</li>
 *   <li>{@link Apply} — function application</li>
 *   <li>{@link Force} — force evaluation of a delayed term</li>
 *   <li>{@link Delay} — delay evaluation (create a thunk)</li>
 *   <li>{@link Const} — constant value</li>
 *   <li>{@link Builtin} — built-in function reference</li>
 *   <li>{@link Error} — error term (halts evaluation)</li>
 *   <li>{@link Constr} — constructor application (Plutus V3, SOPs)</li>
 *   <li>{@link Case} — case/pattern matching (Plutus V3, SOPs)</li>
 * </ul>
 */
public sealed interface Term {

    /**
     * Variable reference using a De Bruijn index.
     *
     * @param name the named De Bruijn index
     */
    record Var(NamedDeBruijn name) implements Term {
        public Var { Objects.requireNonNull(name); }
    }

    /**
     * Lambda abstraction — binds a variable in the body.
     *
     * @param paramName the parameter name (for debugging; not used in evaluation)
     * @param body      the body term
     */
    record Lam(String paramName, Term body) implements Term {
        public Lam { Objects.requireNonNull(paramName); Objects.requireNonNull(body); }
    }

    /**
     * Function application.
     *
     * @param function the function to apply
     * @param argument the argument
     */
    record Apply(Term function, Term argument) implements Term {
        public Apply { Objects.requireNonNull(function); Objects.requireNonNull(argument); }
    }

    /**
     * Force — triggers evaluation of a delayed (thunked) term.
     *
     * @param term the term to force
     */
    record Force(Term term) implements Term {
        public Force { Objects.requireNonNull(term); }
    }

    /**
     * Delay — creates a thunk (suspended computation).
     *
     * @param term the term to delay
     */
    record Delay(Term term) implements Term {
        public Delay { Objects.requireNonNull(term); }
    }

    /**
     * Constant value.
     *
     * @param value the constant
     */
    record Const(Constant value) implements Term {
        public Const { Objects.requireNonNull(value); }
    }

    /**
     * Built-in function reference.
     *
     * @param fun the built-in function
     */
    record Builtin(DefaultFun fun) implements Term {
        public Builtin { Objects.requireNonNull(fun); }
    }

    /**
     * Error term — halts evaluation immediately.
     */
    record Error() implements Term {}

    /**
     * Constructor application (Plutus V3, Sums of Products).
     * Creates a constructor value with the given tag and fields.
     * Tag is an unsigned 64-bit value (Java long, where negative values represent values &gt; Long.MAX_VALUE).
     *
     * @param tag    the constructor tag (unsigned 64-bit)
     * @param fields the constructor fields
     */
    record Constr(long tag, List<Term> fields) implements Term {
        public Constr {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Case expression (Plutus V3, Sums of Products).
     * Matches on a constructor and dispatches to the appropriate branch.
     *
     * @param scrutinee the term to match on (should evaluate to a Constr)
     * @param branches  the branch terms, indexed by constructor tag
     */
    record Case(Term scrutinee, List<Term> branches) implements Term {
        public Case {
            Objects.requireNonNull(scrutinee);
            branches = List.copyOf(branches);
        }
    }

    // Convenience factory methods

    static Term var(NamedDeBruijn name) { return new Var(name); }
    static Term var(int index) { return new Var(new NamedDeBruijn(index)); }
    static Term lam(String paramName, Term body) { return new Lam(paramName, body); }
    static Term apply(Term function, Term argument) { return new Apply(function, argument); }
    static Term force(Term term) { return new Force(term); }
    static Term delay(Term term) { return new Delay(term); }
    static Term const_(Constant value) { return new Const(value); }
    static Term builtin(DefaultFun fun) { return new Builtin(fun); }
    static Term error() { return new Error(); }
    static Term constr(long tag, Term... fields) { return new Constr(tag, List.of(fields)); }
    static Term case_(Term scrutinee, Term... branches) { return new Case(scrutinee, List.of(branches)); }
}
