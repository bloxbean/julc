/* Example sources for the javac-in-Wasm demo. Classic script: defines globalThis.JAVAC_EXAMPLES. */
globalThis.JAVAC_EXAMPLES = [
  {
    name: 'Probe class (compiles)',
    expect: [],
    source: `import java.math.BigInteger;
public class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    static BigInteger m(S s, BigInteger k) {
        return switch (s) { case A a when a.v().compareTo(k) > 0 -> a.v().add(k); case A a -> a.v(); case B b -> b.v().negate(); };
    }
}
`,
  },
  {
    name: 'Undefined symbol',
    expect: ['compiler.err.cant.resolve.location.args'],
    source: `import java.math.BigInteger;
public class Probe {
    record A(BigInteger v) {}
    static BigInteger m(A a, BigInteger k) {
        return a.v().plus(k);
    }
}
`,
  },
  {
    name: 'Incompatible types (return "x")',
    expect: ['compiler.err.prob.found.req'],
    source: `import java.math.BigInteger;
public class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    static BigInteger m(S s, BigInteger k) {
        return "x";
    }
}
`,
  },
  {
    name: 'Non-exhaustive switch over a sealed interface',
    expect: ['compiler.err.not.exhaustive'],
    source: `import java.math.BigInteger;
public class Probe {
    sealed interface S permits A, B, C {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    record C(BigInteger v) implements S {}
    static BigInteger m(S s) {
        return switch (s) {
            case A a -> a.v();
            case B b -> b.v().negate();
        };
    }
}
`,
  },
  {
    name: 'Dominated case label',
    expect: ['compiler.err.pattern.dominated'],
    source: `import java.math.BigInteger;
public class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    static BigInteger m(S s, BigInteger k) {
        return switch (s) {
            case A a -> a.v();
            case A a when a.v().compareTo(k) > 0 -> a.v().add(k);
            case B b -> b.v().negate();
        };
    }
}
`,
  },
  {
    name: 'Guard reads a non-effectively-final variable',
    expect: ['compiler.err.cant.ref.non.effectively.final.var'],
    source: `import java.math.BigInteger;
public class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    static BigInteger m(S s, BigInteger k) {
        BigInteger limit = k;
        limit = limit.add(BigInteger.ONE);
        return switch (s) {
            case A a when a.v().compareTo(limit) > 0 -> a.v();
            case A a -> BigInteger.ZERO;
            case B b -> b.v();
        };
    }
}
`,
  },
  {
    name: 'JuLC validator with ledger types (compiles)',
    expect: [],
    source: `import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.*;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;

@SpendingValidator
public class Vesting {
    public record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    public static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo info = ctx.txInfo();
        JulcList<TxOut> outputs = info.outputs();
        boolean signed = ContextsLib.signedBy(info, datum.beneficiary());
        ContextsLib.trace("vesting check");
        return signed && !outputs.isEmpty();
    }
}
`,
  },
  {
    name: 'JuLC-only syntax: BigInteger with > (javac rejects)',
    expect: ['compiler.err.operator.cant.be.applied.1'],
    source: `import java.math.BigInteger;
public class VestingValidator {
    static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
        return currentTime > deadline;
    }
}
`,
  },
];
