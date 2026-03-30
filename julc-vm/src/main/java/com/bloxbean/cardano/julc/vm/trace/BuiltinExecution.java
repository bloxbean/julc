package com.bloxbean.cardano.julc.vm.trace;

import com.bloxbean.cardano.julc.core.DefaultFun;

/**
 * Records a single builtin function execution during CEK machine evaluation.
 * <p>
 * Uses String summaries (not raw CekValue) so this record can live in the SPI
 * layer ({@code julc-vm}) without depending on implementation-specific types.
 *
 * @param fun           the builtin function that was executed
 * @param argSummary    short string summarizing the arguments (e.g., "5, 3")
 * @param resultSummary short string summarizing the result (e.g., "False")
 */
public record BuiltinExecution(DefaultFun fun, String argSummary, String resultSummary) {

    @Override
    public String toString() {
        return fun + "(" + argSummary + ") → " + resultSummary;
    }
}
