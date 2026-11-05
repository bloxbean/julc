package org.julclang.wasm;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;

import java.util.function.BooleanSupplier;

/**
 * Limits this module's substitutions to Web Image builds. The BLS builtins and BLS constant validation need a native
 * library that WebAssembly cannot load, so a Web Image replaces them. An ordinary native image with this jar on its
 * class path keeps them: without this check, it would fail valid BLS builtins and accept invalid BLS constants.
 */
final class OnlyWebImage implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        return ImageSingletons.lookup(Platform.class).getClass().getName().contains("webimage");
    }
}
