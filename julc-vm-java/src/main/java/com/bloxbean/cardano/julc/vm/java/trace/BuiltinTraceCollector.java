package com.bloxbean.cardano.julc.vm.java.trace;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Ring buffer that records the last N builtin executions during CEK machine evaluation.
 * <p>
 * Stores raw references on the hot path and defers string formatting to {@link #getEntries()},
 * which runs once after evaluation completes.
 */
public final class BuiltinTraceCollector {

    private static final HexFormat HEX = HexFormat.of();

    private record RawEntry(DefaultFun fun, List<CekValue> args, CekValue result, String errorSummary) {}

    private final RawEntry[] buffer;
    private int head = 0;  // next write position
    private int size = 0;

    /**
     * Create a collector with the given ring buffer capacity.
     *
     * @param capacity maximum number of recent builtin executions to retain
     */
    public BuiltinTraceCollector(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.buffer = new RawEntry[capacity];
    }

    /**
     * Record a builtin execution. Only stores references — no string formatting on the hot path.
     *
     * @param fun    the builtin function
     * @param args   the arguments (as CekValues)
     * @param result the result (as CekValue)
     */
    public void record(DefaultFun fun, List<CekValue> args, CekValue result) {
        addEntry(new RawEntry(fun, args, result, null));
    }

    /**
     * Record a builtin execution that threw an exception.
     *
     * @param fun  the builtin function
     * @param args the arguments (as CekValues)
     * @param e    the exception thrown
     */
    public void recordError(DefaultFun fun, List<CekValue> args, Exception e) {
        addEntry(new RawEntry(fun, args, null, e.getClass().getSimpleName()));
    }

    private void addEntry(RawEntry entry) {
        buffer[head] = entry;
        head = (head + 1) % buffer.length;
        if (size < buffer.length) size++;
    }

    /**
     * Return the recorded builtin executions in chronological order,
     * formatting argument/result summaries lazily.
     */
    public List<BuiltinExecution> getEntries() {
        if (size == 0) return List.of();
        var result = new ArrayList<BuiltinExecution>(size);
        int start = (size < buffer.length) ? 0 : head;
        for (int i = 0; i < size; i++) {
            var raw = buffer[(start + i) % buffer.length];
            String resultSummary = raw.result != null
                    ? formatValue(raw.result)
                    : "<ERROR: " + raw.errorSummary + ">";
            result.add(new BuiltinExecution(raw.fun, formatArgs(raw.args), resultSummary));
        }
        return List.copyOf(result);
    }

    private static String formatArgs(List<CekValue> args) {
        if (args.isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatValue(args.get(i)));
        }
        return sb.toString();
    }

    /**
     * Convert a CekValue to a short human-readable string.
     */
    static String formatValue(CekValue value) {
        return switch (value) {
            case CekValue.VCon vcon -> formatConstant(vcon.constant());
            case CekValue.VDelay _ -> "<Delay>";
            case CekValue.VLam _ -> "<Lambda>";
            case CekValue.VConstr vc -> "<Constr:" + vc.tag() + ">";
            case CekValue.VBuiltin vb -> "<Builtin:" + vb.fun() + ">";
        };
    }

    private static String formatConstant(Constant c) {
        return switch (c) {
            case Constant.IntegerConst ic -> ic.value().toString();
            case Constant.BoolConst bc -> bc.value() ? "True" : "False";
            case Constant.ByteStringConst bs -> formatByteString(bs.value());
            case Constant.StringConst sc -> formatString(sc.value());
            case Constant.UnitConst _ -> "()";
            case Constant.DataConst _ -> "<Data>";
            case Constant.ListConst lc -> "[" + lc.values().size() + " elems]";
            case Constant.PairConst _ -> "<Pair>";
            case Constant.ArrayConst ac -> "[" + ac.values().size() + " elems]";
            case Constant.ValueConst vc -> "<Value:" + vc.entries().size() + " policies>";
            case Constant.Bls12_381_G1Element _ -> "<G1>";
            case Constant.Bls12_381_G2Element _ -> "<G2>";
            case Constant.Bls12_381_MlResult _ -> "<MlResult>";
        };
    }

    private static String formatByteString(byte[] bytes) {
        if (bytes.length == 0) return "#";
        String hex = HEX.formatHex(bytes);
        if (bytes.length > 8) {
            return "#" + hex.substring(0, 16) + "...";
        }
        return "#" + hex;
    }

    private static String formatString(String s) {
        if (s.length() > 20) {
            return "\"" + s.substring(0, 17) + "...\"";
        }
        return "\"" + s + "\"";
    }
}
