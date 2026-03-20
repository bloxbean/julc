package com.bloxbean.cardano.julc.clientlib.eval;

/**
 * Configuration for converting between Cardano slot numbers and POSIX time (milliseconds).
 * <p>
 * Plutus validity ranges use POSIX time, but CCL transactions use slot numbers.
 * This record bridges the two representations.
 *
 * @param zeroSlot       the reference slot (e.g., Shelley start slot)
 * @param zeroSlotPosixMs POSIX time in milliseconds at the reference slot
 * @param slotLengthMs   duration of one slot in milliseconds
 */
public record SlotConfig(long zeroSlot, long zeroSlotPosixMs, long slotLengthMs) {

    /**
     * Convert a slot number to POSIX time in milliseconds.
     */
    public long slotToPosixMs(long slot) {
        return zeroSlotPosixMs + (slot - zeroSlot) * slotLengthMs;
    }

    /**
     * Mainnet Shelley config: slot 4492800, epoch start 1596059091000ms, 1000ms per slot.
     */
    public static SlotConfig mainnet() {
        return new SlotConfig(4492800, 1596059091000L, 1000);
    }

    /**
     * Preprod config: slot 86400, start 1654041600000ms + 86400s, 1000ms per slot.
     */
    public static SlotConfig preprod() {
        return new SlotConfig(86400, 1654041600000L + 86400 * 1000L, 1000);
    }
}
