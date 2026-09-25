package org.julclang.testkit.property;

import org.julclang.compiler.pir.PirType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How deep the values of PIR types must be, for generators of recursive types: the
 * <em>height</em> of a type is the least number of named-type references on a path through
 * its smallest value (lists and maps may be empty and optionals absent). Choosing only the
 * shallowest constructors of a sum strictly lowers the height at every reference, so
 * generation that does so once out of depth always terminates.
 */
public final class DataShapes {
    /** The height of a type that has no finite value. */
    public static final int UNBOUNDED = Integer.MAX_VALUE / 2;

    private final Map<String, PirType> named;
    private final Map<String, Integer> heights = new HashMap<>();

    /** @param namedTypes named type definitions by stable id */
    public DataShapes(Map<String, PirType> namedTypes) {
        this.named = Map.copyOf(namedTypes);
        named.keySet().forEach(id -> heights.put(id, UNBOUNDED));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : named.entrySet()) {
                int height = height(entry.getValue());
                if (height < heights.get(entry.getKey())) {
                    heights.put(entry.getKey(), height);
                    changed = true;
                }
            }
        }
    }

    public PirType definition(PirType.NamedTypeRef ref) {
        var definition = named.get(ref.stableId());
        if (definition == null) throw unsupported(ref, "no definition for named type " + ref.stableId());
        return definition;
    }

    /** The least depth of a value of {@code type}, or {@link #UNBOUNDED}. */
    public int height(PirType type) {
        return switch (type) {
            case PirType.RecordType record -> fields(record.fields());
            case PirType.SumType sum -> sum.constructors().stream().mapToInt(c -> fields(c.fields())).min().orElse(UNBOUNDED);
            case PirType.NamedTypeRef ref -> {
                definition(ref);
                int height = heights.get(ref.stableId());
                yield height >= UNBOUNDED ? UNBOUNDED : height + 1;
            }
            default -> 0;
        };
    }

    /**
     * The constructors of {@code sum} with the least height, in declaration order.
     *
     * @throws IllegalArgumentException if no constructor has a finite value
     */
    public List<PirType.Constructor> shallowest(PirType.SumType sum) {
        int least = height(sum);
        if (least >= UNBOUNDED) throw unsupported(sum, "it has no finite value");
        return sum.constructors().stream().filter(c -> fields(c.fields()) == least).toList();
    }

    /** @throws IllegalArgumentException if a value of {@code ref} would be infinite */
    public void requireFinite(PirType.NamedTypeRef ref) {
        if (height(ref) >= UNBOUNDED)
            throw unsupported(ref, "the recursive type " + ref.name() + " has no finite value");
    }

    private int fields(List<PirType.Field> fields) {
        int height = 0;
        for (var field : fields) height = Math.max(height, height(field.type()));
        return height;
    }

    public static IllegalArgumentException unsupported(PirType type, String reason) {
        return new IllegalArgumentException("Cannot generate values of " + type + ": " + reason);
    }
}
