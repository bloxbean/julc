package org.julclang.compiler.frontend.javac.lower;

import javax.lang.model.element.ExecutableElement;

/**
 * What a method invocation is, decided from what javac bound and before any term is built (ADR-068's plan, rule 1):
 * {@link CallTargets} resolves, {@link Calls} lowers. A name binds only to what javac bound (I12), so no target is reached
 * by a spelling. The last three are what the reference does not lower here: a target, not an exception, so that resolving
 * stays a function of the tree.
 */
sealed interface CallTarget {

    /** The compiler's own meaning of an API method. */
    enum Intrinsic implements CallTarget {
        /** {@code BigInteger.valueOf(n)}: the argument. */
        IDENTITY,
        /** {@code PlutusData.cast(d, T.class)}: a view of the value, decoded only into a map. */
        CAST,
        /** {@code x.toPlutusData()}: {@code x} encoded by its type. */
        TO_DATA
    }

    /** What the stdlib lookup is asked for, by a key {@code CallTargets} takes from the owner's identity. */
    sealed interface Keyed extends CallTarget permits Stdlib, Library {
        String className();

        String method();
    }

    /** A static method of an API class, lowered by the stdlib lookup, and the overload javac chose. */
    record Stdlib(String className, String method, ExecutableElement element) implements Keyed {
    }

    /** A static method of a library unit: the stdlib lookup first, as in the reference, then a call of its binder. */
    record Library(String className, String method, ExecutableElement element) implements Keyed {
    }

    /** A static method of the class being lowered, called by its simple name. */
    record Local(String binder, ExecutableElement element) implements CallTarget {
    }

    /** A record component read from Data. */
    record Accessor(String component) implements CallTarget {
    }

    /** The accessor of a type that is its one component: a ledger hash or a {@code @NewType}. */
    record Wrapper(String component) implements CallTarget {
    }

    /** An instance method lowered by {@code TypeMethodRegistry}, under the exact key the method's owner gives. */
    record TypeMethod(String key, String method) implements CallTarget {
    }

    /** {@code equals(Object)}, which belongs to the receiver's type whatever class declares it (D6). */
    record Equals() implements CallTarget {
    }

    /** A list method that takes a function. */
    record Hof(String method) implements CallTarget {
        /** The key of its handler. */
        static final String KEY = "ListType";
    }

    /** A member with no lowering on its receiver's type: {@code JULC0055}, the reference's rejection. */
    record Unresolved(String member) implements CallTarget {
    }

    /** What the reference rejects in these words. */
    record Rejected(String message) implements CallTarget {
    }

    /** {@code JULC0084}: not ported, or not to be lowered as the reference lowers it. */
    record NotLowered(String construct) implements CallTarget {
    }
}
