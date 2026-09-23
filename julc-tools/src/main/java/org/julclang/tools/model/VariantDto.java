package org.julclang.tools.model;

import java.util.List;

public record VariantDto(String name, int tag, List<FieldDto> fields) {
}
