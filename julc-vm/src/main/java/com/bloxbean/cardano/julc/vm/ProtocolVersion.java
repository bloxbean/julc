package com.bloxbean.cardano.julc.vm;

/**
 * A Cardano protocol version used to select ledger evaluation behavior.
 *
 * @param major the non-negative major version
 * @param minor the non-negative minor version
 */
public record ProtocolVersion(int major, int minor) {

    public static final ProtocolVersion PV10 = new ProtocolVersion(10, 0);
    public static final ProtocolVersion PV11 = new ProtocolVersion(11, 0);

    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException(
                    "Protocol version must be non-negative: " + major + "." + minor);
        }
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
