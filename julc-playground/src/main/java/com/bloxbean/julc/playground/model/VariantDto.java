package com.bloxbean.julc.playground.model;

import com.bloxbean.cardano.julc.jrl.ast.VariantNode;

import java.util.List;

public record VariantDto(String name, int tag, List<FieldDto> fields) {

    public static VariantDto from(VariantNode v, int tag) {
        return new VariantDto(
                v.name(),
                tag,
                v.fields().stream().map(FieldDto::from).toList()
        );
    }
}
