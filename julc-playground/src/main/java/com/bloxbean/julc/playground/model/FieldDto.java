package com.bloxbean.julc.playground.model;

import com.bloxbean.cardano.julc.jrl.ast.FieldNode;
import com.bloxbean.cardano.julc.jrl.ast.TypeRef;

public record FieldDto(String name, String type) {

    public static FieldDto from(FieldNode f) {
        return new FieldDto(f.name(), typeRefToString(f.type()));
    }

    public static String typeRefToString(TypeRef ref) {
        return switch (ref) {
            case TypeRef.SimpleType s -> s.name();
            case TypeRef.ListType l -> "List<" + typeRefToString(l.elementType()) + ">";
            case TypeRef.OptionalType o -> "Optional<" + typeRefToString(o.elementType()) + ">";
        };
    }
}
