package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.nio.charset.StandardCharsets;

/**
 * Plutus D/E argument wrapper for selected {@code Text} builtin arguments.
 * Memory usage is the UTF-8 byte length divided by four, using integer
 * truncation, exactly like Haskell's {@code TextCostedByByteLength}.
 */
final class TextCostedByByteLength {

    private TextCostedByByteLength() {
    }

    /** Return the wrapped size, or {@code null} when the value is not Text. */
    static Long sizeOf(CekValue value) {
        if (value instanceof CekValue.VCon(Constant.StringConst text)) {
            return (long) text.value().getBytes(StandardCharsets.UTF_8).length / 4;
        }
        return null;
    }
}
