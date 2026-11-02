package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import java.util.HashMap;
import java.util.Map;

/**
 * The binders visible at a point in the PIR being built: an immutable chain, so a scope cannot leak out of a method or
 * survive an exception. The key is javac's {@link Element}, never a name: a name resolves to the binder of the element
 * javac bound it to (I3).
 * <p>
 * {@link #bind} chooses the name while the binder is built (I13, ADR-060): the source name, unless a binder of another
 * element with that name is visible here, and then {@code name'k} with the smallest {@code k >= 1} not visible.
 */
public final class Scope {

    /** The mark of a renamed binder, which the binder-namespace check reads as its source name (ADR-060). */
    public static final char MARK = '\'';

    /**
     * A source element's PIR binder: the element, the name chosen for it when it was bound (I13), and its one type, which is
     * what it holds, so its reads and every encoding of it agree with what was decoded. {@code seen} is what the reference
     * looks a member up on, which can be Data where javac knows better (I2d); it says nothing of the value. Only a
     * {@link Scope} makes one, so a name cannot be chosen anywhere else.
     */
    public static final class Binder {
        private final Element element;
        private final String name;
        private final PirType type;
        private final PirType seen;

        private Binder(Element element, String name, PirType type, PirType seen) {
            this.element = element;
            this.name = name;
            this.type = type;
            this.seen = seen;
        }

        public Element element() {
            return element;
        }

        public String name() {
            return name;
        }

        public PirType type() {
            return type;
        }

        public PirType seen() {
            return seen;
        }
    }

    /** The source name a binder's name stands for: {@code name'k} is {@code name}. */
    static String sourceName(String binder) {
        int mark = binder.indexOf(MARK);
        return mark < 0 ? binder : binder.substring(0, mark);
    }

    private static final Scope EMPTY = new Scope(null, null);

    private final Scope parent;
    private final Binder binder;

    private Scope(Scope parent, Binder binder) {
        this.parent = parent;
        this.binder = binder;
    }

    public static Scope empty() {
        return EMPTY;
    }

    /** This scope with {@code element} bound. */
    public Scope bind(Element element, PirType type) {
        return bind(element, type, type);
    }

    /** This scope with {@code element} bound, holding {@code type}, which the reference reads as {@code seen}. */
    public Scope bind(Element element, PirType type, PirType seen) {
        return new Scope(this, new Binder(element, freeName(element), type, seen));
    }

    /**
     * This scope with {@code element} bound again, at {@code type}, under the name it has here: a decoded view of a value,
     * which shadows the binder it reads. A binder that rebinds a source name keeps it (ADR-060).
     */
    public Scope rebind(Element element, PirType type) {
        var binder = lookup(element);
        return rebind(element, type, type.equals(binder.type()) ? binder.seen() : type);
    }

    /** {@link #rebind(Element, PirType)}, which the reference reads as {@code seen}. */
    public Scope rebind(Element element, PirType type, PirType seen) {
        return new Scope(this, new Binder(element, lookup(element).name(), type, seen));
    }

    /** The binder of {@code element}, or null if none is visible. */
    public Binder lookup(Element element) {
        for (Scope s = this; s != EMPTY; s = s.parent) {
            if (s.binder.element().equals(element)) return s.binder;
        }
        return null;
    }

    /** Every binder visible here, by name: where one element is bound again, the innermost. */
    Map<String, Binder> binders() {
        var binders = new HashMap<String, Binder>();
        for (Scope s = this; s != EMPTY; s = s.parent) binders.putIfAbsent(s.binder.name(), s.binder);
        return binders;
    }

    private String freeName(Element element) {
        String name = element.getSimpleName().toString();
        if (!visible(name)) return name;
        for (int k = 1; ; k++) {
            String marked = name + MARK + k;
            if (!visible(marked)) return marked;
        }
    }

    private boolean visible(String name) {
        for (Scope s = this; s != EMPTY; s = s.parent) {
            if (s.binder.name().equals(name)) return true;
        }
        return false;
    }
}
