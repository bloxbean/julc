package com.bloxbean.cardano.julc.verification.dsl.worker;

import com.bloxbean.cardano.julc.verification.dsl.PropertyIrCodec;
import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;

import java.nio.file.Path;

/** Minimal no-shell worker protocol: specification class in, canonical AST file out. */
public final class DslWorkerMain {
    private DslWorkerMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DslWorkerMain <spec-class> <output-json>");
        }
        Class<?> type = Class.forName(args[0]);
        if (!VerificationSpecification.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(args[0] + " is not a VerificationSpecification");
        }
        var constructor = type.getDeclaredConstructor();
        if (!java.lang.reflect.Modifier.isPublic(constructor.getModifiers())) {
            throw new IllegalArgumentException("Specification requires a public no-arg constructor");
        }
        var specification = (VerificationSpecification) constructor.newInstance();
        PropertyIrCodec.write(Path.of(args[1]), specification.properties());
    }
}
