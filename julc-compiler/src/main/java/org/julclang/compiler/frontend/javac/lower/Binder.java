package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;

/** A source element's PIR binder: the element, the name chosen for it when it was bound (I13), and its type. */
public record Binder(Element element, String name, PirType type) {
}
