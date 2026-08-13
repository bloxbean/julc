package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.HashSet;
import java.util.List;

public record DslPropertySet(int schemaVersion, List<DslProperty> properties) {
    public static final int SCHEMA_VERSION = 1;

    public DslPropertySet {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported DSL property schema " + schemaVersion);
        }
        properties = List.copyOf(properties == null ? List.of() : properties);
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("At least one property is required");
        }
        var ids = new HashSet<String>();
        for (DslProperty property : properties) {
            if (!ids.add(property.id())) {
                throw new IllegalArgumentException("Duplicate property ID: " + property.id());
            }
        }
    }

    public static DslPropertySet of(DslProperty... properties) {
        return new DslPropertySet(SCHEMA_VERSION, List.of(properties));
    }
}
