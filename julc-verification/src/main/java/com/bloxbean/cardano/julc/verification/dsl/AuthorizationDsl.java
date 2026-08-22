package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Builders for opt-in schema-6 compositional authorization properties. */
public final class AuthorizationDsl {
    public static final int MAX_STATIC_AUTHORITIES = 16;

    public AuthorityExpr fixed(String canonicalKeyHashHex) {
        validateFixedKeyHashHex(canonicalKeyHashHex);
        var literal = new BytesLiteralNode(
                DslType.BYTE_STRING, BytesLiteralKind.KEY_HASH,
                Objects.requireNonNull(canonicalKeyHashHex, "canonicalKeyHashHex"));
        return new AuthorityExpr(new AuthorityKeyHashNode(
                AuthoritySourceKind.FIXED, literal));
    }

    public AuthorityExpr fromContractBytes(ByteStringExpr bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new AuthorityExpr(new AuthorityKeyHashNode(
                AuthoritySourceKind.CONTRACT_BYTES, bytes.node()));
    }

    public AuthoritySetExpr authorities(AuthorityExpr... authorities) {
        Objects.requireNonNull(authorities, "authorities");
        if (authorities.length == 0) {
            throw new IllegalArgumentException("A static authority set must not be empty");
        }
        if (authorities.length > MAX_STATIC_AUTHORITIES) {
            throw new IllegalArgumentException("A static authority set supports at most "
                    + MAX_STATIC_AUTHORITIES + " members");
        }
        var nodes = java.util.Arrays.stream(authorities)
                .map(authority -> Objects.requireNonNull(authority, "authority").node())
                .toList();
        rejectDuplicateFixedAuthorities(nodes);
        return new AuthoritySetExpr(new AuthorityListNode(nodes));
    }

    /** Dynamic byte-string lists may be empty; authorization relations retain that fact. */
    public AuthoritySetExpr fromContractBytes(TypedListExpr bytes) {
        Objects.requireNonNull(bytes, "bytes");
        var expected = new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING);
        if (!expected.equals(bytes.elementType())) {
            throw new IllegalArgumentException(
                    "Dynamic authority source must be a contract byte-string list");
        }
        return new AuthoritySetExpr(new AuthorityListFromBytesNode(bytes.node()));
    }

    public BoolExpr noSigners() {
        return new BoolExpr(new NoSignersNode());
    }

    private static void rejectDuplicateFixedAuthorities(List<PropertyNode> nodes) {
        var fixed = new HashSet<String>();
        for (PropertyNode node : nodes) {
            if (node instanceof AuthorityKeyHashNode key
                    && key.sourceKind() == AuthoritySourceKind.FIXED
                    && key.bytes() instanceof BytesLiteralNode literal
                    && !fixed.add(literal.hex())) {
                throw new IllegalArgumentException(
                        "Duplicate fixed authority key hash: " + literal.hex());
            }
        }
    }

    static void validateFixedKeyHashHex(String hex) {
        Objects.requireNonNull(hex, "canonicalKeyHashHex");
        if (!hex.matches("[0-9a-f]{56}")) {
            throw new IllegalArgumentException(
                    "Fixed authority key hash must be exactly 28 canonical lowercase hex bytes");
        }
        for (int index = 0; index < hex.length(); index += 2) {
            if (hex.charAt(index) == '0' && hex.charAt(index + 1) == '0') {
                throw new IllegalArgumentException(
                        "Fixed authority key hashes containing byte 00 are unsupported by "
                        + "the pinned exact-UPLC symbolic translation; use a compiler-owned "
                        + "contract byte source or a zero-free fixed key");
            }
        }
    }
}
