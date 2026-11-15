package org.julclang.compiler.uplc;

import java.util.Set;

/**
 * The methods a multi-purpose validator's handlers may share (ADR-076 D1): their binder names, a helper's or a library
 * method's {@code Owner.name}, and how many handlers there are. The assembly finds them in the PIR; the optimizer shares
 * those its own output binds the same way in every handler ({@link HandlerBindingSharing}).
 *
 * @param names    the candidate methods' binder names
 * @param handlers the number of handlers, each of which binds every candidate
 */
public record HandlerBindings(Set<String> names, int handlers) {

    /** Nothing to share: a program with one entry point, or one whose handlers share no method. */
    public static final HandlerBindings NONE = new HandlerBindings(Set.of(), 0);

    public HandlerBindings {
        names = Set.copyOf(names);
    }

    /** Whether there is anything to share. */
    public boolean any() {
        return handlers >= 2 && !names.isEmpty();
    }
}
