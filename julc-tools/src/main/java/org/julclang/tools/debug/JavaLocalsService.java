package org.julclang.tools.debug;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.tools.model.SourceDebugModels.ChildValue;
import org.julclang.tools.model.SourceDebugModels.ChildrenResponse;
import org.julclang.tools.model.SourceDebugModels.DebugValue;
import org.julclang.tools.model.SourceDebugModels.LocalValue;
import org.julclang.tools.model.SourceDebugModels.LocalsResponse;
import org.julclang.tools.model.SourceDebugModels.ScopeValue;
import org.julclang.tools.model.SourceDebugModels.SourceRange;
import org.julclang.tools.uplc.UplcToolsService;
import org.julclang.vm.java.CekValue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only, transport-neutral projection of proven Java bindings from a CEK observation.
 * It never evaluates a closure, forces a delay, invokes a builtin or accepts an environment index from a client.
 */
public final class JavaLocalsService {
    private static final int MAX_CHILDREN_PER_PAGE = 50;
    private static final int MAX_NODES_PER_REQUEST = 256;
    private static final int MAX_LOCALS_PER_REQUEST = 256;
    private static final int MAX_HANDLES_PER_GENERATION = 2_048;
    private static final int MAX_BYTES_PER_REQUEST = 512;
    private static final int MAX_OUTPUT_CHARS = 4096;
    private static final SecureRandom HANDLE_RANDOM = new SecureRandom();

    private sealed interface NodeRef permits DataRef, MapEntryRef {}
    private record DataRef(PlutusData value) implements NodeRef {}
    private record MapEntryRef(PlutusData.Pair value) implements NodeRef {}
    private record Handle(long generation, NodeRef node, String typeId, String layoutId) {}

    private static final class Budget {
        int nodes;
        int bytes;
        int chars;
    }

    private final DebugRuntimeIndex index;
    private final String handleScope = newHandleScope();
    private final Map<String, Handle> handles = new LinkedHashMap<>();
    private long generation = -1;
    private long nextHandle;

    /** Bind a validated metadata sidecar to the exact decoded program used by a debug runtime. */
    public JavaLocalsService(DebugMetadata metadata, Program decodedProgram) {
        this(new DebugRuntimeIndex(metadata, decodedProgram));
    }

    JavaLocalsService(DebugRuntimeIndex index) {
        this.index = index;
    }

    public LocalsResponse locals(UplcToolsService.DebugObservation observation, long requestedGeneration,
                                 long currentGeneration) {
        if (requestedGeneration != currentGeneration) return staleLocals(currentGeneration);
        moveTo(currentGeneration);
        if (observation == null || !observation.computing()) {
            String reason = observation == null ? "debug machine is unavailable" : observation.unavailableReason();
            return new LocalsResponse(true, null, currentGeneration, "unavailable", reason, List.of());
        }
        var lookup = index.lookup(observation.term());
        if (lookup.point() == null) {
            return new LocalsResponse(true, null, currentGeneration, "unavailable",
                    lookup.unavailableReason(), List.of());
        }
        var point = lookup.point();
        int environmentSize = observation.environment().size();
        if (environmentSize != point.expectedBinderIds().size()) {
            return new LocalsResponse(true, null, currentGeneration, "unavailable",
                    "CEK environment shape does not match the verified lexical binder vector", List.of());
        }

        var groups = new LinkedHashMap<String, List<LocalValue>>();
        var budget = new Budget();
        int projectedLocals = 0;
        for (var visible : point.visibleBindings()) {
            if (projectedLocals++ >= MAX_LOCALS_PER_REQUEST) break;
            var binding = index.bindings.get(visible.bindingId());
            if (binding == null) continue; // The metadata validator normally makes this impossible.
            var values = groups.computeIfAbsent(binding.scopeId(), ignored -> new ArrayList<>());
            if (visible.slot() == null || visible.availability() != DebugMetadata.Availability.AVAILABLE) {
                values.add(local(binding, visible, null));
                continue;
            }
            CekValue value;
            try {
                value = observation.environment().lookup(visible.slot().index());
            } catch (RuntimeException e) {
                values.add(local(binding, new DebugMetadata.VisibleBinding(binding.id(),
                        DebugMetadata.Availability.NOT_MATERIALIZED, null,
                        "verified environment slot is absent", visible.shadowed()), null));
                continue;
            }
            values.add(local(binding, visible, project(value, binding.typeId(), visible.slot().layoutId(),
                    currentGeneration, budget)));
        }
        var scopes = new ArrayList<ScopeValue>();
        groups.forEach((scopeId, values) -> {
            var scope = index.scopes.get(scopeId);
            scopes.add(new ScopeValue(scopeId, scope == null ? "unknown" : scope.kind().name().toLowerCase(), values));
        });
        return new LocalsResponse(true, null, currentGeneration, "available", null, scopes);
    }

    /** Whether the observation has an exact sidecar occurrence and complete matching CEK environment. */
    public boolean isAvailable(UplcToolsService.DebugObservation observation) {
        if (observation == null || !observation.computing()) return false;
        var lookup = index.lookup(observation.term());
        return lookup.point() != null
                && observation.environment().size() == lookup.point().expectedBinderIds().size();
    }

    public ChildrenResponse children(long requestedGeneration, long currentGeneration, String handle,
                                     int start, int count) {
        if (requestedGeneration != currentGeneration) return staleChildren(currentGeneration, handle);
        moveTo(currentGeneration);
        if (start < 0 || count < 1 || count > MAX_CHILDREN_PER_PAGE) {
            return new ChildrenResponse(false, "start must be non-negative and count must be 1.."
                    + MAX_CHILDREN_PER_PAGE, currentGeneration, handle, start, null, List.of());
        }
        Handle value = handles.get(handle);
        if (handle == null || !handle.startsWith(handleScope + ".")
                || value == null || value.generation() != currentGeneration) {
            return new ChildrenResponse(false, "Value reference is stale or unknown", currentGeneration,
                    handle, start, null, List.of());
        }
        int childCount = childCount(value.node());
        if (start > childCount) {
            return new ChildrenResponse(false, "Child start exceeds the value's child count", currentGeneration,
                    handle, start, null, List.of());
        }
        int end = (int) Math.min(childCount, (long) start + count);
        var budget = new Budget();
        var result = new ArrayList<ChildValue>();
        for (int i = start; i < end && budget.nodes < MAX_NODES_PER_REQUEST; i++) {
            var child = childAt(value.node(), i);
            result.add(new ChildValue(child.name(), projectNode(child.node(), value.typeId(), value.layoutId(),
                    currentGeneration, budget)));
        }
        Integer next = end < childCount ? end : null;
        return new ChildrenResponse(true, null, currentGeneration, handle, start, next, result);
    }

    public void invalidate(long nextGeneration) {
        generation = nextGeneration;
        handles.clear();
        nextHandle = 0;
    }

    private LocalValue local(DebugMetadata.Binding binding, DebugMetadata.VisibleBinding visible, DebugValue value) {
        var type = index.types.get(binding.typeId());
        return new LocalValue(binding.id(), binding.name(), binding.declaredSpelling(),
                type == null ? binding.typeId() : type.displayName(), visible.shadowed(),
                availability(visible.availability()), visible.reason(), range(binding.declarationRange()), value);
    }

    private DebugValue project(CekValue value, String typeId, String layoutId, long currentGeneration, Budget budget) {
        var layout = index.layouts.get(layoutId);
        if (layout == null) return unavailable("unknown", "unknown physical layout", typeId, layoutId);
        if (layout.kind() == DebugMetadata.LayoutKind.OPAQUE) {
            String summary = opaque(value);
            String bounded = bounded(summary, budget);
            boolean truncated = bounded.length() < summary.length();
            return new DebugValue("opaque", bounded, typeId, layoutId,
                    "unsupportedRepresentation", truncated,
                    truncated ? "character limit reached" : null, null, null);
        }
        if (!(value instanceof CekValue.VCon constant)) {
            return unavailable("mismatch", "runtime value is " + value.getClass().getSimpleName()
                    + ", not the recorded constant layout", typeId, layoutId);
        }
        return switch (layout.kind()) {
            case INTEGER -> constant.constant() instanceof Constant.IntegerConst integer
                    ? integer("integer", integer.value(), typeId, layoutId, budget)
                    : mismatch(typeId, layoutId);
            case BYTE_STRING -> constant.constant() instanceof Constant.ByteStringConst bytes
                    ? bytes(bytes, typeId, layoutId, budget) : mismatch(typeId, layoutId);
            case STRING -> constant.constant() instanceof Constant.StringConst string
                    ? string(string.value(), typeId, layoutId, budget) : mismatch(typeId, layoutId);
            case BOOLEAN -> constant.constant() instanceof Constant.BoolConst bool
                    ? scalar("boolean", Boolean.toString(bool.value()), typeId, layoutId, budget)
                    : mismatch(typeId, layoutId);
            case UNIT -> constant.constant() instanceof Constant.UnitConst
                    ? scalar("unit", "unit", typeId, layoutId, budget) : mismatch(typeId, layoutId);
            case RAW_DATA -> constant.constant() instanceof Constant.DataConst data
                    ? projectNode(new DataRef(data.value()), typeId, layoutId, currentGeneration, budget)
                    : mismatch(typeId, layoutId);
            case OPAQUE -> throw new IllegalStateException("handled above");
        };
    }

    private DebugValue projectNode(NodeRef node, String typeId, String layoutId,
                                   long currentGeneration, Budget budget) {
        if (++budget.nodes > MAX_NODES_PER_REQUEST) {
            return truncated("data", "node limit reached", typeId, layoutId);
        }
        String kind;
        String summary;
        int childCount;
        boolean truncated = false;
        String truncationReason = null;
        if (node instanceof MapEntryRef) {
            kind = "dataMapEntry";
            summary = "map entry";
            childCount = 2;
        } else {
            PlutusData data = ((DataRef) node).value();
            switch (data) {
                case PlutusData.IntData value -> {
                    return integer("dataInteger", value.value(), typeId, layoutId, budget);
                }
                case PlutusData.BytesData value -> {
                    return dataBytes(value, typeId, layoutId, budget);
                }
                case PlutusData.ConstrData value -> {
                    kind = "dataConstr";
                    String tag = boundedInteger(value.constructorTag(), 128);
                    truncated = tag.endsWith("…");
                    truncationReason = truncated ? "constructor tag exceeds character limit" : null;
                    summary = "Constr " + tag
                            + " (" + value.fields().size() + " fields)";
                    childCount = value.fields().size();
                }
                case PlutusData.ListData value -> {
                    kind = "dataList";
                    summary = "List (" + value.items().size() + " items)";
                    childCount = value.items().size();
                }
                case PlutusData.MapData value -> {
                    kind = "dataMap";
                    summary = "Map (" + value.entries().size() + " entries)";
                    childCount = value.entries().size();
                }
            }
        }
        String handle = childCount == 0 ? null : newHandle(currentGeneration, node, typeId, layoutId);
        if (childCount > 0 && handle == null) {
            truncated = true;
            truncationReason = "value-reference limit reached";
        }
        String boundedSummary = bounded(summary, budget);
        if (boundedSummary.length() < summary.length()) {
            truncated = true;
            truncationReason = "character limit reached";
        }
        return new DebugValue(kind, boundedSummary, typeId, layoutId, "available",
                truncated, truncationReason, childCount, handle);
    }

    private record NamedNode(String name, NodeRef node) {}

    private static int childCount(NodeRef node) {
        if (node instanceof MapEntryRef) return 2;
        return switch (((DataRef) node).value()) {
            case PlutusData.ConstrData value -> value.fields().size();
            case PlutusData.ListData value -> value.items().size();
            case PlutusData.MapData value -> value.entries().size();
            default -> 0;
        };
    }

    private static NamedNode childAt(NodeRef node, int index) {
        if (node instanceof MapEntryRef entry) {
            return index == 0
                    ? new NamedNode("key", new DataRef(entry.value().key()))
                    : new NamedNode("value", new DataRef(entry.value().value()));
        }
        return switch (((DataRef) node).value()) {
            case PlutusData.ConstrData value ->
                    new NamedNode("[" + index + "]", new DataRef(value.fields().get(index)));
            case PlutusData.ListData value ->
                    new NamedNode("[" + index + "]", new DataRef(value.items().get(index)));
            case PlutusData.MapData value ->
                    new NamedNode("[" + index + "]", new MapEntryRef(value.entries().get(index)));
            default -> throw new IllegalArgumentException("Scalar Data has no children");
        };
    }

    private DebugValue bytes(Constant.ByteStringConst value, String typeId, String layoutId, Budget budget) {
        int remaining = Math.max(0, MAX_BYTES_PER_REQUEST - budget.bytes);
        int read = Math.min(value.size(), remaining);
        byte[] prefix = value.prefix(read);
        budget.bytes += read;
        boolean truncated = read < value.size();
        String summary = "#" + HexFormat.of().formatHex(prefix) + (truncated ? "…" : "");
        String boundedSummary = bounded(summary, budget);
        boolean charactersTruncated = boundedSummary.length() < summary.length();
        return new DebugValue("bytes", boundedSummary, typeId, layoutId, "available",
                truncated || charactersTruncated,
                charactersTruncated ? "character limit reached" : truncated ? "byte limit reached" : null,
                null, null);
    }

    private DebugValue dataBytes(PlutusData.BytesData value, String typeId, String layoutId, Budget budget) {
        int remaining = Math.max(0, MAX_BYTES_PER_REQUEST - budget.bytes);
        int read = Math.min(value.size(), remaining);
        byte[] prefix = value.prefix(read);
        budget.bytes += read;
        boolean truncated = read < value.size();
        String summary = "B " + HexFormat.of().formatHex(prefix) + (truncated ? "…" : "");
        String boundedSummary = bounded(summary, budget);
        boolean charactersTruncated = boundedSummary.length() < summary.length();
        return new DebugValue("dataBytes", boundedSummary, typeId, layoutId, "available",
                truncated || charactersTruncated,
                charactersTruncated ? "character limit reached" : truncated ? "byte limit reached" : null,
                null, null);
    }

    private static DebugValue string(String value, String typeId, String layoutId, Budget budget) {
        int remaining = Math.max(0, MAX_OUTPUT_CHARS - budget.chars);
        boolean truncated = value.length() + 2 > remaining;
        String summary;
        if (!truncated) {
            summary = '"' + value + '"';
        } else if (remaining >= 3) {
            summary = '"' + value.substring(0, Math.min(value.length(), remaining - 3)) + "…\"";
        } else if (remaining == 2) {
            summary = "\"\"";
        } else if (remaining == 1) {
            summary = "…";
        } else {
            summary = "";
        }
        budget.chars += summary.length();
        return new DebugValue("string", summary, typeId, layoutId, "available", truncated,
                truncated ? "character limit reached" : null, null, null);
    }

    private static DebugValue scalar(String kind, String summary, String typeId, String layoutId, Budget budget) {
        String bounded = bounded(summary, budget);
        boolean truncated = bounded.length() < summary.length();
        return new DebugValue(kind, bounded, typeId, layoutId, "available", truncated,
                truncated ? "character limit reached" : null, null, null);
    }

    private static DebugValue integer(String kind, java.math.BigInteger value, String typeId,
                                      String layoutId, Budget budget) {
        int remaining = Math.max(0, MAX_OUTPUT_CHARS - budget.chars);
        String summary = boundedInteger(value, remaining);
        boolean truncated = summary.isEmpty() || summary.endsWith("…");
        budget.chars += summary.length();
        return new DebugValue(kind, summary, typeId, layoutId, "available", truncated,
                truncated ? "character limit reached" : null, null, null);
    }

    /** Avoid materializing an unbounded decimal string merely to truncate it afterwards. */
    private static String boundedInteger(java.math.BigInteger value, int maximumChars) {
        if (maximumChars <= 0) return "";
        // bitLength/log2(10), rounded conservatively upward. If that cannot fit, do not call
        // BigInteger.toString(), whose allocation would itself violate the observation budget.
        long estimatedDigits = value.signum() == 0 ? 1
                : ((long) value.abs().bitLength() * 30103L + 99_999L) / 100_000L;
        long required = estimatedDigits + (value.signum() < 0 ? 1 : 0);
        if (required > maximumChars) return integerLimitMarker(maximumChars);
        String exact = value.toString();
        return exact.length() <= maximumChars ? exact : integerLimitMarker(maximumChars);
    }

    private static String integerLimitMarker(int maximumChars) {
        String marker = "<integer exceeds character limit>";
        if (maximumChars == 1) return "…";
        int prefix = Math.min(marker.length(), maximumChars - 1);
        return marker.substring(0, prefix) + "…";
    }

    private static String bounded(String value, Budget budget) {
        int remaining = Math.max(0, MAX_OUTPUT_CHARS - budget.chars);
        String result = value.length() <= remaining ? value : value.substring(0, remaining);
        budget.chars += result.length();
        return result;
    }

    private static DebugValue mismatch(String typeId, String layoutId) {
        return unavailable("mismatch", "runtime value does not match the recorded physical layout", typeId, layoutId);
    }

    private static DebugValue unavailable(String kind, String reason, String typeId, String layoutId) {
        return new DebugValue(kind, reason, typeId, layoutId, "representationMismatch",
                false, null, null, null);
    }

    private static DebugValue truncated(String kind, String reason, String typeId, String layoutId) {
        return new DebugValue(kind, reason, typeId, layoutId, "available", true, reason, null, null);
    }

    private static String opaque(CekValue value) {
        return switch (value) {
            case CekValue.VLam ignored -> "closure";
            case CekValue.VDelay ignored -> "delay";
            case CekValue.VBuiltin builtin -> "builtin " + builtin.fun().name();
            case CekValue.VConstr constr -> "UPLC constructor " + Long.toUnsignedString(constr.tag())
                    + " (" + constr.fields().size() + " fields)";
            case CekValue.VCon constant -> "constant " + constant.constant().type();
        };
    }

    private static String availability(DebugMetadata.Availability availability) {
        String name = availability.name().toLowerCase(java.util.Locale.ROOT);
        var parts = name.split("_");
        var result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }

    private static SourceRange range(DebugMetadata.SourceRange value) {
        return new SourceRange(value.sourceId(), value.startUtf16(), value.endUtf16(),
                value.startLine(), value.startColumn(), value.endLine(), value.endColumn());
    }

    private String newHandle(long currentGeneration, NodeRef node, String typeId, String layoutId) {
        if (handles.size() >= MAX_HANDLES_PER_GENERATION) return null;
        String id = handleScope + "." + Long.toUnsignedString(currentGeneration, 36)
                + "." + Long.toUnsignedString(++nextHandle, 36);
        handles.put(id, new Handle(currentGeneration, node, typeId, layoutId));
        return id;
    }

    private static String newHandleScope() {
        byte[] nonce = new byte[16];
        HANDLE_RANDOM.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    private void moveTo(long currentGeneration) {
        if (generation != currentGeneration) invalidate(currentGeneration);
    }

    private static LocalsResponse staleLocals(long currentGeneration) {
        return new LocalsResponse(false, "Stop generation is stale", currentGeneration,
                "stale", "the debugger moved after this reference was issued", List.of());
    }

    private static ChildrenResponse staleChildren(long currentGeneration, String handle) {
        return new ChildrenResponse(false, "Stop generation is stale", currentGeneration,
                handle, 0, null, List.of());
    }
}
