package com.bloxbean.julc.playground.model;

import java.util.List;

public record VariantDto(String name, int tag, List<FieldDto> fields) {
}
