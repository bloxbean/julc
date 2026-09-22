package org.julclang.playground.uplc;

import org.julclang.core.Term;
import org.julclang.core.text.UplcPrinter;
import org.julclang.vm.java.CekValue;

/**
 * Short, bounded descriptions of CEK machine values for the debugger. Closures are not expanded, so describing a
 * value never walks its environment.
 */
final class DebugValues {

    private DebugValues() {}

    static String value(CekValue value, int maxChars) {
        if (value == null) return null;
        String text = switch (value) {
            case CekValue.VCon c -> UplcPrinter.print(new Term.Const(c.constant()));
            case CekValue.VLam l -> "(lam " + l.paramName() + " …)";
            case CekValue.VDelay ignored -> "(delay …)";
            case CekValue.VConstr c -> constr(c, maxChars);
            case CekValue.VBuiltin b -> "(builtin " + UplcToolsService.builtinName(b.fun()) + ")"
                    + (b.argsRemaining() > 0 ? " awaiting " + b.argsRemaining() + " argument(s)" : "")
                    + (b.forcesRemaining() > 0 ? " awaiting force" : "");
        };
        return UplcToolsService.truncate(text, maxChars);
    }

    private static String constr(CekValue.VConstr c, int maxChars) {
        var sb = new StringBuilder("(constr ").append(Long.toUnsignedString(c.tag()));
        for (CekValue field : c.fields()) {
            if (sb.length() > maxChars) break;
            sb.append(' ').append(value(field, Math.max(16, maxChars / 2)));
        }
        return sb.append(')').toString();
    }
}
