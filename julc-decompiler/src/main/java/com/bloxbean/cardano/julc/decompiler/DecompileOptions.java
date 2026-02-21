package com.bloxbean.cardano.julc.decompiler;

import java.nio.file.Path;

/**
 * Configuration for the decompiler.
 *
 * @param outputLevel   the detail level of the decompilation output
 * @param blueprintPath optional path to a CIP-57 Blueprint JSON file for type hints
 */
public record DecompileOptions(OutputLevel outputLevel, Path blueprintPath) {

    public enum OutputLevel {
        /** Pretty-printed UPLC text + statistics */
        DISASSEMBLY,
        /** HIR with control flow but generic names */
        STRUCTURED,
        /** HIR with inferred types and ledger field names */
        TYPED,
        /** Complete JuLC-style Java validator class */
        FULL_JAVA
    }

    public static DecompileOptions defaults() {
        return new DecompileOptions(OutputLevel.FULL_JAVA, null);
    }

    public static DecompileOptions disassembly() {
        return new DecompileOptions(OutputLevel.DISASSEMBLY, null);
    }

    public static DecompileOptions structured() {
        return new DecompileOptions(OutputLevel.STRUCTURED, null);
    }

    public static DecompileOptions typed() {
        return new DecompileOptions(OutputLevel.TYPED, null);
    }

    public DecompileOptions withBlueprint(Path path) {
        return new DecompileOptions(outputLevel, path);
    }
}
