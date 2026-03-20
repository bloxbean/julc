package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * String operation builtins.
 */
public final class StringBuiltins {

    private StringBuiltins() {}

    public static CekValue appendString(List<CekValue> args) {
        var a = asString(args.get(0), "AppendString");
        var b = asString(args.get(1), "AppendString");
        return mkString(a + b);
    }

    public static CekValue equalsString(List<CekValue> args) {
        var a = asString(args.get(0), "EqualsString");
        var b = asString(args.get(1), "EqualsString");
        return mkBool(a.equals(b));
    }

    public static CekValue encodeUtf8(List<CekValue> args) {
        var s = asString(args.get(0), "EncodeUtf8");
        return mkByteString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static CekValue decodeUtf8(List<CekValue> args) {
        var bs = asByteString(args.get(0), "DecodeUtf8");
        // Validate UTF-8
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            var result = decoder.decode(java.nio.ByteBuffer.wrap(bs));
            return mkString(result.toString());
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new BuiltinException("DecodeUtf8: invalid UTF-8 byte sequence");
        }
    }
}
