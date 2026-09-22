package org.julclang.playground.uplc;

import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.text.UplcParser;
import org.julclang.playground.model.MockTransaction.DataInput;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads Plutus data typed by users in one of three notations:
 * <ul>
 *   <li>{@code json}: the cardano-cli detailed schema, e.g. {@code {"constructor":0,"fields":[{"int":42}]}}</li>
 *   <li>{@code cbor}: CBOR hex</li>
 *   <li>{@code uplc}: UPLC data text, e.g. {@code Constr 0 [I 42, B #cafe]}</li>
 * </ul>
 * With format {@code auto} (or none) the notation is detected: JSON objects, plain integers ({@code 42} means
 * {@code I 42}), hex (CBOR) and otherwise UPLC data text.
 */
public final class DataInputs {

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern HEX = Pattern.compile("(0x)?([0-9a-fA-F]{2})+");

    private DataInputs() {}

    /** Parses the input; {@code null} when there is no input. */
    public static PlutusData parse(DataInput input) {
        if (input == null || input.value() == null || input.value().isBlank()) {
            return null;
        }
        String value = input.value().strip();
        String format = input.format() == null ? "auto" : input.format().toLowerCase(Locale.ROOT);
        if (format.equals("auto")) {
            if (value.startsWith("{") || value.startsWith("[")) format = "json";
            else if (INTEGER.matcher(value).matches()) return PlutusData.integer(new BigInteger(value));
            else if (HEX.matcher(value).matches()) format = "cbor";
            else format = "uplc";
        }
        try {
            return switch (format) {
                case "json" -> PlutusDataAdapter.fromClientLib(PlutusDataJsonConverter.toPlutusData(value));
                case "cbor" -> PlutusDataCborDecoder.decode(
                        HexFormat.of().parseHex(value.startsWith("0x") ? value.substring(2) : value));
                case "uplc" -> parseUplcData(value);
                default -> throw new IllegalArgumentException("Unknown data format: " + input.format());
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new IllegalArgumentException("Invalid " + format + " data: " + reason, e);
        }
    }

    /** Parses the input, or returns {@code fallback} when there is no input. */
    public static PlutusData parseOr(DataInput input, PlutusData fallback) {
        PlutusData data = parse(input);
        return data != null ? data : fallback;
    }

    private static PlutusData parseUplcData(String text) {
        Term term = UplcParser.parseTerm("(con data (" + text + "))");
        if (term instanceof Term.Const(Constant.DataConst(PlutusData data))) {
            return data;
        }
        throw new IllegalArgumentException("Expected Plutus data such as Constr 0 [I 42]");
    }
}
