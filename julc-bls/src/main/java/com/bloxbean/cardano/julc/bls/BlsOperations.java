package com.bloxbean.cardano.julc.bls;

import supranational.blst.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * BLS12-381 cryptographic operations using the blst native library.
 * <p>
 * Simple point operations use the SWIG Java bindings from blst-java.
 * Hash-to-group uses Panama FFM to pass raw byte[] DST (the SWIG binding
 * converts DST to modified UTF-8, corrupting bytes &gt; 0x7F).
 * Pairing operations use SWIG with a thread-local PT cache (the blst 0.3.2
 * library does not export {@code blst_fp12_from_bendian}).
 */
public final class BlsOperations {

    private BlsOperations() {}

    public static final int G1_COMPRESSED_SIZE = 48;
    public static final int G2_COMPRESSED_SIZE = 96;
    public static final int ML_RESULT_SIZE = 576;

    // --- Struct sizes ---
    private static final long SIZEOF_P1 = 144;
    private static final long SIZEOF_P2 = 288;

    // --- Panama FFM handles for hash_to and compress ---
    private static final MethodHandle HASH_TO_G1;
    private static final MethodHandle HASH_TO_G2;
    private static final MethodHandle P1_COMPRESS;
    private static final MethodHandle P2_COMPRESS;

    // --- PT cache for pairing operations ---
    private static final ThreadLocal<Map<ByteBuffer, PT>> PT_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    static {
        // Trigger blst native library loading via SWIG class initialization
        try { new P1(); } catch (Exception ignored) {}

        SymbolLookup lookup = loadBlstLibrary();

        HASH_TO_G1 = downcall(lookup, "blst_hash_to_g1",
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        HASH_TO_G2 = downcall(lookup, "blst_hash_to_g2",
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        P1_COMPRESS = downcall(lookup, "blst_p1_compress",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        P2_COMPRESS = downcall(lookup, "blst_p2_compress",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private static SymbolLookup loadBlstLibrary() {
        String os = System.getProperty("os.name", "").replaceFirst(" .*", "");
        String arch = System.getProperty("os.arch");
        String libName = System.mapLibraryName("blst");
        String resPath = "/supranational/blst/" + os + "/" + arch + "/" + libName;
        try (InputStream in = P1.class.getResourceAsStream(resPath)) {
            if (in == null) {
                throw new IllegalStateException("blst native library not found: " + resPath);
            }
            Path tempLib = Files.createTempFile("blst_panama_", libName);
            tempLib.toFile().deleteOnExit();
            Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
            return SymbolLookup.libraryLookup(tempLib, Arena.global());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract blst native library", e);
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(() ->
                        new IllegalStateException("blst symbol not found: " + name)),
                desc);
    }

    // ====== G1 Operations (SWIG) ======

    public static byte[] g1Add(byte[] a, byte[] b) {
        return run("G1 add", () -> {
            P1 pa = new P1(a);
            pa.add(new P1_Affine(b));
            return pa.compress();
        });
    }

    public static byte[] g1Neg(byte[] a) {
        return run("G1 neg", () -> {
            P1 p = new P1(a);
            p.neg();
            return p.compress();
        });
    }

    public static byte[] g1ScalarMul(BigInteger scalar, byte[] point) {
        return run("G1 scalarMul", () -> {
            P1 p = new P1(point);
            if (scalar.signum() < 0) {
                p.neg();
                p.mult(scalar.negate());
            } else if (scalar.signum() == 0) {
                return new P1().compress();
            } else {
                p.mult(scalar);
            }
            return p.compress();
        });
    }

    public static boolean g1Equal(byte[] a, byte[] b) {
        return run("G1 equal", () -> {
            P1_Affine pa = new P1_Affine(a);
            P1_Affine pb = new P1_Affine(b);
            return pa.is_equal(pb);
        });
    }

    public static byte[] g1Compress(byte[] element) {
        return element.clone();
    }

    public static byte[] g1Uncompress(byte[] compressed) {
        if (compressed.length != G1_COMPRESSED_SIZE) {
            throw new BlsException("G1 uncompress: expected " + G1_COMPRESSED_SIZE +
                    " bytes, got " + compressed.length);
        }
        return run("G1 uncompress", () -> {
            P1_Affine affine = new P1_Affine(compressed);
            if (!affine.in_group()) {
                throw new BlsException("G1 uncompress: point not in subgroup");
            }
            return compressed.clone();
        });
    }

    // ====== G1 hashToGroup (Panama FFM) ======

    public static byte[] g1HashToGroup(byte[] msg, byte[] dst) {
        if (dst.length > 255) {
            throw new BlsException("G1 hashToGroup: DST must be <= 255 bytes, got " + dst.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p1Out = arena.allocate(SIZEOF_P1);
            MemorySegment msgSeg = allocateBytes(arena, msg);
            MemorySegment dstSeg = allocateBytes(arena, dst);

            HASH_TO_G1.invoke(p1Out, msgSeg, (long) msg.length,
                              dstSeg, (long) dst.length,
                              MemorySegment.NULL, 0L);

            MemorySegment compressed = arena.allocate(G1_COMPRESSED_SIZE);
            P1_COMPRESS.invoke(compressed, p1Out);
            return compressed.toArray(ValueLayout.JAVA_BYTE);
        } catch (BlsException e) {
            throw e;
        } catch (Throwable t) {
            throw new BlsException("G1 hashToGroup failed: " + t.getMessage(), t);
        }
    }

    // ====== G1 Multi-Scalar Multiplication ======

    /** Maximum scalar byte size for MSM (512 bytes = 4096 bits signed). */
    public static final int MSM_MAX_SCALAR_BYTES = 512;

    /**
     * Multi-scalar multiplication for G1: sum(scalars[i] * points[i]).
     * Uses zip semantics (shorter list determines count). Empty → identity.
     * Scalars must fit in 512 bytes (signed two's complement).
     */
    public static byte[] g1MultiScalarMul(BigInteger[] scalars, byte[][] points) {
        int len = Math.min(scalars.length, points.length);
        // Validate scalar sizes
        for (int i = 0; i < len; i++) {
            checkMsmScalarSize(scalars[i]);
        }
        return run("G1 multiScalarMul", () -> {
            P1 acc = new P1();  // identity
            for (int i = 0; i < len; i++) {
                P1 p = new P1(points[i]);
                if (scalars[i].signum() < 0) {
                    p.neg();
                    p.mult(scalars[i].negate());
                } else if (scalars[i].signum() == 0) {
                    continue;
                } else {
                    p.mult(scalars[i]);
                }
                acc.add(new P1_Affine(p.compress()));
            }
            return acc.compress();
        });
    }

    // ====== G2 Operations (SWIG) ======

    public static byte[] g2Add(byte[] a, byte[] b) {
        return run("G2 add", () -> {
            P2 pa = new P2(a);
            pa.add(new P2_Affine(b));
            return pa.compress();
        });
    }

    public static byte[] g2Neg(byte[] a) {
        return run("G2 neg", () -> {
            P2 p = new P2(a);
            p.neg();
            return p.compress();
        });
    }

    public static byte[] g2ScalarMul(BigInteger scalar, byte[] point) {
        return run("G2 scalarMul", () -> {
            P2 p = new P2(point);
            if (scalar.signum() < 0) {
                p.neg();
                p.mult(scalar.negate());
            } else if (scalar.signum() == 0) {
                return new P2().compress();
            } else {
                p.mult(scalar);
            }
            return p.compress();
        });
    }

    public static boolean g2Equal(byte[] a, byte[] b) {
        return run("G2 equal", () -> {
            P2_Affine pa = new P2_Affine(a);
            P2_Affine pb = new P2_Affine(b);
            return pa.is_equal(pb);
        });
    }

    public static byte[] g2Compress(byte[] element) {
        return element.clone();
    }

    public static byte[] g2Uncompress(byte[] compressed) {
        if (compressed.length != G2_COMPRESSED_SIZE) {
            throw new BlsException("G2 uncompress: expected " + G2_COMPRESSED_SIZE +
                    " bytes, got " + compressed.length);
        }
        return run("G2 uncompress", () -> {
            P2_Affine affine = new P2_Affine(compressed);
            if (!affine.in_group()) {
                throw new BlsException("G2 uncompress: point not in subgroup");
            }
            return compressed.clone();
        });
    }

    // ====== G2 hashToGroup (Panama FFM) ======

    public static byte[] g2HashToGroup(byte[] msg, byte[] dst) {
        if (dst.length > 255) {
            throw new BlsException("G2 hashToGroup: DST must be <= 255 bytes, got " + dst.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p2Out = arena.allocate(SIZEOF_P2);
            MemorySegment msgSeg = allocateBytes(arena, msg);
            MemorySegment dstSeg = allocateBytes(arena, dst);

            HASH_TO_G2.invoke(p2Out, msgSeg, (long) msg.length,
                              dstSeg, (long) dst.length,
                              MemorySegment.NULL, 0L);

            MemorySegment compressed = arena.allocate(G2_COMPRESSED_SIZE);
            P2_COMPRESS.invoke(compressed, p2Out);
            return compressed.toArray(ValueLayout.JAVA_BYTE);
        } catch (BlsException e) {
            throw e;
        } catch (Throwable t) {
            throw new BlsException("G2 hashToGroup failed: " + t.getMessage(), t);
        }
    }

    // ====== G2 Multi-Scalar Multiplication ======

    /**
     * Multi-scalar multiplication for G2: sum(scalars[i] * points[i]).
     * Uses zip semantics (shorter list determines count). Empty → identity.
     * Scalars must fit in 512 bytes (signed two's complement).
     */
    public static byte[] g2MultiScalarMul(BigInteger[] scalars, byte[][] points) {
        int len = Math.min(scalars.length, points.length);
        for (int i = 0; i < len; i++) {
            checkMsmScalarSize(scalars[i]);
        }
        return run("G2 multiScalarMul", () -> {
            P2 acc = new P2();  // identity
            for (int i = 0; i < len; i++) {
                P2 p = new P2(points[i]);
                if (scalars[i].signum() < 0) {
                    p.neg();
                    p.mult(scalars[i].negate());
                } else if (scalars[i].signum() == 0) {
                    continue;
                } else {
                    p.mult(scalars[i]);
                }
                acc.add(new P2_Affine(p.compress()));
            }
            return acc.compress();
        });
    }

    // ====== Pairing Operations (SWIG + PT cache) ======

    public static byte[] millerLoop(byte[] g1, byte[] g2) {
        return run("millerLoop", () -> {
            P1_Affine p1 = new P1_Affine(g1);
            P2_Affine p2 = new P2_Affine(g2);
            PT pt = new PT(p1, p2);
            byte[] serialized = pt.to_bendian();
            cachePt(serialized, pt);
            return serialized;
        });
    }

    public static byte[] mulMlResult(byte[] a, byte[] b) {
        PT ptA = getCachedPt(a);
        PT ptB = getCachedPt(b);
        PT result = ptA.dup().mul(ptB);
        byte[] serialized = result.to_bendian();
        cachePt(serialized, result);
        return serialized;
    }

    public static boolean finalVerify(byte[] a, byte[] b) {
        PT ptA = getCachedPt(a);
        PT ptB = getCachedPt(b);
        return PT.finalverify(ptA, ptB);
    }

    // ====== PT Cache ======

    private static void cachePt(byte[] key, PT pt) {
        PT_CACHE.get().put(ByteBuffer.wrap(key.clone()), pt);
    }

    private static PT getCachedPt(byte[] bytes) {
        PT pt = PT_CACHE.get().get(ByteBuffer.wrap(bytes));
        if (pt == null) {
            throw new BlsException(
                    "MlResult not found in cache. The blst 0.3.2 library does not expose " +
                    "blst_fp12_from_bendian, so MlResult must originate from millerLoop or " +
                    "mulMlResult within the same evaluation.");
        }
        return pt;
    }

    /**
     * Clear the PT cache. Call after evaluation to release native memory.
     */
    public static void clearPtCache() {
        PT_CACHE.get().clear();
    }

    // ====== Internal ======

    private static void checkMsmScalarSize(BigInteger scalar) {
        if (scalar.toByteArray().length > MSM_MAX_SCALAR_BYTES) {
            throw new BlsException("multiScalarMul: scalar too large (" +
                    scalar.toByteArray().length + " bytes, max " + MSM_MAX_SCALAR_BYTES + ")");
        }
    }

    private static MemorySegment allocateBytes(Arena arena, byte[] bytes) {
        if (bytes.length == 0) {
            return arena.allocate(1);
        }
        return arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
    }

    @FunctionalInterface
    private interface BlsOp<T> {
        T execute();
    }

    private static <T> T run(String op, BlsOp<T> fn) {
        try {
            return fn.execute();
        } catch (BlsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BlsException(op + " failed: " + e.getMessage(), e);
        }
    }
}
