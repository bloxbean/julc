package com.bloxbean.cardano.julc.verification.dsl.type;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Closed structural type reference admitted by schema-4 properties. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BuiltinTypeRef.class, name = "builtin"),
        @JsonSubTypes.Type(value = NominalTypeRef.class, name = "nominal"),
        @JsonSubTypes.Type(value = OptionalTypeRef.class, name = "optional"),
        @JsonSubTypes.Type(value = ListTypeRef.class, name = "list"),
        @JsonSubTypes.Type(value = AssocMapTypeRef.class, name = "assoc-map")
})
public sealed interface VerificationTypeRef permits BuiltinTypeRef, NominalTypeRef,
        OptionalTypeRef, ListTypeRef, AssocMapTypeRef { }
