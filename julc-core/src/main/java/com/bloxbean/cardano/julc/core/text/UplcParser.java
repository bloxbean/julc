package com.bloxbean.cardano.julc.core.text;

import com.bloxbean.cardano.julc.core.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses UPLC programs and terms from the standard text format.
 * <p>
 * Format examples:
 * <pre>
 * (program 1.1.0 (lam x x))
 * (con integer 42)
 * [(builtin addInteger) (con integer 1) (con integer 2)]
 * </pre>
 * <p>
 * This is a recursive descent parser that consumes a string input.
 */
public final class UplcParser {

    private static final HexFormat HEX = HexFormat.of();

    /** Maximum recursion depth to prevent stack overflow on malicious input. */
    private static final int MAX_DEPTH = 1000;

    /** Maximum number of digits in an integer literal. */
    private static final int MAX_INTEGER_DIGITS = 100_000;

    private final String input;
    private int pos;
    private int depth;
    /** Lambda binder scope for resolving variable names to de Bruijn indices. */
    private final List<String> scope = new ArrayList<>();
    /** Program version (set during program parsing, used for version-gated features). */
    private int versionMajor;
    private int versionMinor;

    private UplcParser(String input) {
        this.input = input;
        this.pos = 0;
        this.depth = 0;
    }

    /**
     * Parse a UPLC program from its text representation.
     */
    public static Program parseProgram(String input) {
        var parser = new UplcParser(input);
        parser.skipWhitespace();
        var program = parser.program();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw parser.error("Unexpected content after program");
        }
        return program;
    }

    /**
     * Parse a UPLC term from its text representation.
     */
    public static Term parseTerm(String input) {
        var parser = new UplcParser(input);
        // Default to highest version so all term types parse without restriction.
        // Version gating only applies when parsing a full program with an explicit version.
        parser.versionMajor = Integer.MAX_VALUE;
        parser.versionMinor = Integer.MAX_VALUE;
        parser.skipWhitespace();
        var term = parser.term();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw parser.error("Unexpected content after term");
        }
        return term;
    }

    // ---- Program ----

    private Program program() {
        expect('(');
        skipWhitespace();
        expectKeyword("program");
        skipWhitespace();

        // Parse version: major.minor.patch
        int major = parseNonNegativeInt();
        expect('.');
        int minor = parseNonNegativeInt();
        expect('.');
        int patch = parseNonNegativeInt();
        skipWhitespace();

        // Store version for version-gated feature checks
        this.versionMajor = major;
        this.versionMinor = minor;

        var term = term();
        skipWhitespace();
        expect(')');

        return new Program(major, minor, patch, term);
    }

    // ---- Term ----

    private Term term() {
        depth++;
        if (depth > MAX_DEPTH) {
            throw error("Maximum nesting depth exceeded (" + MAX_DEPTH + ")");
        }
        try {
            skipWhitespace();
            if (pos >= input.length()) {
                throw error("Unexpected end of input, expected a term");
            }

            char ch = input.charAt(pos);
            if (ch == '(') {
                return parenTerm();
            } else if (ch == '[') {
                return applyTerm();
            } else {
                // Must be a variable name
                return varTerm();
            }
        } finally {
            depth--;
        }
    }

    /**
     * Parse a parenthesized term: (keyword ...)
     */
    private Term parenTerm() {
        expect('(');
        skipWhitespace();

        // Peek at the keyword
        String keyword = peekWord();
        return switch (keyword) {
            case "lam" -> lamTerm();
            case "force" -> forceTerm();
            case "delay" -> delayTerm();
            case "con" -> conTerm();
            case "builtin" -> builtinTerm();
            case "error" -> errorTerm();
            case "constr" -> constrTerm();
            case "case" -> caseTerm();
            default -> throw error("Unknown term keyword: " + keyword);
        };
    }

    private Term lamTerm() {
        expectKeyword("lam");
        skipWhitespace();
        String name = parseName();
        skipWhitespace();
        scope.addLast(name);
        var body = term();
        scope.removeLast();
        skipWhitespace();
        expect(')');
        return Term.lam(name, body);
    }

    private Term forceTerm() {
        expectKeyword("force");
        skipWhitespace();
        var t = term();
        skipWhitespace();
        expect(')');
        return Term.force(t);
    }

    private Term delayTerm() {
        expectKeyword("delay");
        skipWhitespace();
        var t = term();
        skipWhitespace();
        expect(')');
        return Term.delay(t);
    }

    private Term conTerm() {
        expectKeyword("con");
        skipWhitespace();
        var constant = parseConstant();
        skipWhitespace();
        expect(')');
        return Term.const_(constant);
    }

    private Term builtinTerm() {
        expectKeyword("builtin");
        skipWhitespace();
        String name = parseName();
        skipWhitespace();
        expect(')');

        var fun = lookupBuiltin(name);
        return Term.builtin(fun);
    }

    private Term errorTerm() {
        expectKeyword("error");
        skipWhitespace();
        expect(')');
        return Term.error();
    }

    private Term constrTerm() {
        expectKeyword("constr");
        requireVersion(1, 1, "constr");
        skipWhitespace();
        long tag = parseUnsigned64();
        skipWhitespace();

        var fields = new ArrayList<Term>();
        while (pos < input.length() && input.charAt(pos) != ')') {
            fields.add(term());
            skipWhitespace();
        }
        expect(')');
        return new Term.Constr(tag, fields);
    }

    private Term caseTerm() {
        expectKeyword("case");
        requireVersion(1, 1, "case");
        skipWhitespace();
        var scrutinee = term();
        skipWhitespace();

        var branches = new ArrayList<Term>();
        while (pos < input.length() && input.charAt(pos) != ')') {
            branches.add(term());
            skipWhitespace();
        }
        expect(')');
        return new Term.Case(scrutinee, branches);
    }

    /**
     * Parse an application: [func arg1 arg2 ...]
     * Multiple arguments are folded left: [f a b] = [[f a] b]
     */
    private Term applyTerm() {
        expect('[');
        skipWhitespace();
        var func = term();
        skipWhitespace();

        // At least one argument required
        var arg = term();
        var result = Term.apply(func, arg);
        skipWhitespace();

        // Additional arguments: fold left
        while (pos < input.length() && input.charAt(pos) != ']') {
            arg = term();
            result = Term.apply(result, arg);
            skipWhitespace();
        }
        expect(']');
        return result;
    }

    private Term varTerm() {
        String name = parseName();
        // Search scope from innermost (end) to outermost (start) for the binder
        for (int i = scope.size() - 1; i >= 0; i--) {
            if (scope.get(i).equals(name)) {
                int deBruijnIndex = scope.size() - i;
                return Term.var(new NamedDeBruijn(name, deBruijnIndex));
            }
        }
        // Free variable — index 0 (unbound, will cause evaluation failure)
        return Term.var(new NamedDeBruijn(name, 0));
    }

    // ---- Constants ----

    private Constant parseConstant() {
        var type = parseType();
        skipWhitespace();
        return parseConstantValueOfType(type);
    }

    private Constant parseConstantValueOfType(DefaultUni type) {
        return switch (type) {
            case DefaultUni.Integer ignored -> {
                var value = parseBigInteger();
                yield Constant.integer(value);
            }
            case DefaultUni.ByteString ignored -> {
                var bytes = parseHashByteString();
                yield Constant.byteString(bytes);
            }
            case DefaultUni.String ignored -> {
                var value = parseQuotedString();
                yield Constant.string(value);
            }
            case DefaultUni.Unit ignored -> {
                expect('(');
                skipWhitespace();
                expect(')');
                yield Constant.unit();
            }
            case DefaultUni.Bool ignored -> {
                String word = parseWord();
                yield switch (word) {
                    case "True" -> Constant.bool(true);
                    case "False" -> Constant.bool(false);
                    default -> throw error("Expected 'True' or 'False', got: " + word);
                };
            }
            case DefaultUni.Data ignored -> {
                var data = parsePlutusData();
                yield Constant.data(data);
            }
            case DefaultUni.Apply a -> parseApplyConstant(a);
            case DefaultUni.ProtoValue ignored -> parseValueConstant();
            case DefaultUni.Bls12_381_G1_Element ignored -> {
                var bytes = parse0xByteString();
                var validator = BlsConstantValidator.getInstance();
                if (validator != null) {
                    try {
                        validator.validateG1Element(bytes);
                    } catch (Exception e) {
                        throw error("Invalid bls12_381_G1_element: " + e.getMessage());
                    }
                }
                yield new Constant.Bls12_381_G1Element(bytes);
            }
            case DefaultUni.Bls12_381_G2_Element ignored -> {
                var bytes = parse0xByteString();
                var validator = BlsConstantValidator.getInstance();
                if (validator != null) {
                    try {
                        validator.validateG2Element(bytes);
                    } catch (Exception e) {
                        throw error("Invalid bls12_381_G2_element: " + e.getMessage());
                    }
                }
                yield new Constant.Bls12_381_G2Element(bytes);
            }
            case DefaultUni.Bls12_381_MlResult ignored -> {
                var bytes = parse0xByteString();
                yield new Constant.Bls12_381_MlResult(bytes);
            }
            default -> throw error("Cannot parse constant of type: " + type);
        };
    }

    private Constant parseApplyConstant(DefaultUni.Apply apply) {
        // Determine if it's a list, array, or pair by unwinding the Apply chain
        if (apply.f() instanceof DefaultUni.ProtoArray) {
            // Array type
            var elemType = apply.arg();
            expect('[');
            skipWhitespace();
            var values = new ArrayList<Constant>();
            if (pos < input.length() && input.charAt(pos) != ']') {
                values.add(parseConstantValueOfType(elemType));
                skipWhitespace();
                while (pos < input.length() && input.charAt(pos) == ',') {
                    pos++; // skip comma
                    skipWhitespace();
                    values.add(parseConstantValueOfType(elemType));
                    skipWhitespace();
                }
            }
            expect(']');
            return new Constant.ArrayConst(elemType, values);
        } else if (apply.f() instanceof DefaultUni.ProtoList) {
            // List type
            var elemType = apply.arg();
            expect('[');
            skipWhitespace();
            var values = new ArrayList<Constant>();
            if (pos < input.length() && input.charAt(pos) != ']') {
                values.add(parseConstantValueOfType(elemType));
                skipWhitespace();
                while (pos < input.length() && input.charAt(pos) == ',') {
                    pos++; // skip comma
                    skipWhitespace();
                    values.add(parseConstantValueOfType(elemType));
                    skipWhitespace();
                }
            }
            expect(']');
            return new Constant.ListConst(elemType, values);
        } else if (apply.f() instanceof DefaultUni.Apply inner
                   && inner.f() instanceof DefaultUni.ProtoPair) {
            // Pair type: Apply(Apply(ProtoPair, typeA), typeB)
            var typeA = inner.arg();
            var typeB = apply.arg();
            expect('(');
            skipWhitespace();
            var first = parseConstantValueOfType(typeA);
            skipWhitespace();
            expect(',');
            skipWhitespace();
            var second = parseConstantValueOfType(typeB);
            skipWhitespace();
            expect(')');
            return new Constant.PairConst(first, second);
        }
        throw error("Unsupported compound type: " + apply);
    }

    // ---- Types ----

    private DefaultUni parseType() {
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == '(') {
            // Compound type: (list elemType), (pair typeA typeB)
            pos++; // skip '('
            skipWhitespace();
            String typeName = parseWord();
            skipWhitespace();
            return switch (typeName) {
                case "list" -> {
                    var elemType = parseType();
                    skipWhitespace();
                    expect(')');
                    yield DefaultUni.listOf(elemType);
                }
                case "pair" -> {
                    var typeA = parseType();
                    skipWhitespace();
                    var typeB = parseType();
                    skipWhitespace();
                    expect(')');
                    yield DefaultUni.pairOf(typeA, typeB);
                }
                case "array" -> {
                    var elemType = parseType();
                    skipWhitespace();
                    expect(')');
                    yield DefaultUni.arrayOf(elemType);
                }
                default -> throw error("Unknown compound type: " + typeName);
            };
        }
        // Simple type
        String typeName = parseWord();
        return switch (typeName) {
            case "integer" -> DefaultUni.INTEGER;
            case "bytestring" -> DefaultUni.BYTESTRING;
            case "string" -> DefaultUni.STRING;
            case "unit" -> DefaultUni.UNIT;
            case "bool" -> DefaultUni.BOOL;
            case "data" -> DefaultUni.DATA;
            case "bls12_381_G1_element" -> DefaultUni.BLS12_381_G1;
            case "bls12_381_G2_element" -> DefaultUni.BLS12_381_G2;
            case "bls12_381_mlresult" -> DefaultUni.BLS12_381_ML;
            case "value" -> new DefaultUni.ProtoValue();
            default -> throw error("Unknown type: " + typeName);
        };
    }

    // ---- Value Constants ----

    /** Maximum key length in Value: 32 bytes (CIP-153). */
    private static final int MAX_POLICY_ID_LENGTH = 32;
    /** Maximum tokenName length: 32 bytes. */
    private static final int MAX_TOKEN_NAME_LENGTH = 32;
    /** Maximum token quantity: 2^127 - 1 (Int128). */
    private static final BigInteger MAX_QUANTITY = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    /** Minimum token quantity: -2^127 (Int128). */
    private static final BigInteger MIN_QUANTITY = BigInteger.ONE.shiftLeft(127).negate();

    /**
     * Parse a Value constant: [(#policyId, [(#tokenName, quantity), ...]), ...]
     * <p>
     * Normalizes: sorts by policyId then tokenName, merges duplicate tokens,
     * removes zero quantities, removes empty policies.
     * Validates: key <= 32 bytes (CIP-153), tokenName <= 32 bytes, quantity in Int128 range.
     */
    private Constant parseValueConstant() {
        expect('[');
        skipWhitespace();
        var rawEntries = new ArrayList<Constant.ValueConst.ValueEntry>();
        if (pos < input.length() && input.charAt(pos) != ']') {
            rawEntries.add(parseValueEntry());
            skipWhitespace();
            while (pos < input.length() && input.charAt(pos) == ',') {
                pos++; // skip comma
                skipWhitespace();
                rawEntries.add(parseValueEntry());
                skipWhitespace();
            }
        }
        expect(']');
        return Constant.ValueConst.normalize(rawEntries);
    }

    private Constant.ValueConst.ValueEntry parseValueEntry() {
        expect('(');
        skipWhitespace();
        byte[] policyId = parseHashByteString();
        if (policyId.length > MAX_POLICY_ID_LENGTH) {
            throw error("Value policyId too long: " + policyId.length +
                    " bytes (max " + MAX_POLICY_ID_LENGTH + ")");
        }
        skipWhitespace();
        expect(',');
        skipWhitespace();
        // Parse token list
        expect('[');
        skipWhitespace();
        var tokens = new ArrayList<Constant.ValueConst.TokenEntry>();
        if (pos < input.length() && input.charAt(pos) != ']') {
            tokens.add(parseTokenEntry());
            skipWhitespace();
            while (pos < input.length() && input.charAt(pos) == ',') {
                pos++; // skip comma
                skipWhitespace();
                tokens.add(parseTokenEntry());
                skipWhitespace();
            }
        }
        expect(']');
        skipWhitespace();
        expect(')');
        return new Constant.ValueConst.ValueEntry(policyId, tokens);
    }

    private Constant.ValueConst.TokenEntry parseTokenEntry() {
        expect('(');
        skipWhitespace();
        byte[] tokenName = parseHashByteString();
        if (tokenName.length > MAX_TOKEN_NAME_LENGTH) {
            throw error("Value tokenName too long: " + tokenName.length +
                    " bytes (max " + MAX_TOKEN_NAME_LENGTH + ")");
        }
        skipWhitespace();
        expect(',');
        skipWhitespace();
        BigInteger quantity = parseBigInteger();
        if (quantity.compareTo(MAX_QUANTITY) > 0) {
            throw error("Value quantity overflow: " + quantity + " exceeds Int128 max");
        }
        if (quantity.compareTo(MIN_QUANTITY) < 0) {
            throw error("Value quantity underflow: " + quantity + " below Int128 min");
        }
        skipWhitespace();
        expect(')');
        return new Constant.ValueConst.TokenEntry(tokenName, quantity);
    }

    // ---- PlutusData ----

    private PlutusData parsePlutusData() {
        depth++;
        if (depth > MAX_DEPTH) {
            throw error("Maximum nesting depth exceeded (" + MAX_DEPTH + ")");
        }
        try {
            return parsePlutusDataInner();
        } finally {
            depth--;
        }
    }

    private PlutusData parsePlutusDataInner() {
        skipWhitespace();
        // Data can optionally be parenthesized
        boolean paren = false;
        if (pos < input.length() && input.charAt(pos) == '(') {
            // Peek ahead: could be a paren-wrapped data value or something else
            // Check if next token after '(' is a data keyword
            int saved = pos;
            pos++;
            skipWhitespace();
            String word = peekWord();
            if (word.equals("I") || word.equals("B") || word.equals("Constr")
                    || word.equals("List") || word.equals("Map")) {
                paren = true;
                // pos is already past '('
            } else {
                pos = saved; // restore
            }
        }

        String keyword = parseWord();
        skipWhitespace();
        PlutusData result = switch (keyword) {
            case "I" -> {
                var value = parseBigInteger();
                yield PlutusData.integer(value);
            }
            case "B" -> {
                var bytes = parseHashByteString();
                yield PlutusData.bytes(bytes);
            }
            case "Constr" -> {
                int tag = parseNonNegativeInt();
                skipWhitespace();
                expect('[');
                skipWhitespace();
                var fields = new ArrayList<PlutusData>();
                if (pos < input.length() && input.charAt(pos) != ']') {
                    fields.add(parsePlutusData());
                    skipWhitespace();
                    while (pos < input.length() && input.charAt(pos) == ',') {
                        pos++;
                        skipWhitespace();
                        fields.add(parsePlutusData());
                        skipWhitespace();
                    }
                }
                expect(']');
                yield PlutusData.constr(tag, fields.toArray(PlutusData[]::new));
            }
            case "List" -> {
                expect('[');
                skipWhitespace();
                var items = new ArrayList<PlutusData>();
                if (pos < input.length() && input.charAt(pos) != ']') {
                    items.add(parsePlutusData());
                    skipWhitespace();
                    while (pos < input.length() && input.charAt(pos) == ',') {
                        pos++;
                        skipWhitespace();
                        items.add(parsePlutusData());
                        skipWhitespace();
                    }
                }
                expect(']');
                yield PlutusData.list(items.toArray(PlutusData[]::new));
            }
            case "Map" -> {
                expect('[');
                skipWhitespace();
                var entries = new ArrayList<PlutusData.Pair>();
                if (pos < input.length() && input.charAt(pos) != ']') {
                    entries.add(parseDataPair());
                    skipWhitespace();
                    while (pos < input.length() && input.charAt(pos) == ',') {
                        pos++;
                        skipWhitespace();
                        entries.add(parseDataPair());
                        skipWhitespace();
                    }
                }
                expect(']');
                yield PlutusData.map(entries.toArray(PlutusData.Pair[]::new));
            }
            default -> throw error("Unknown PlutusData tag: " + keyword);
        };

        if (paren) {
            skipWhitespace();
            expect(')');
        }
        return result;
    }

    private PlutusData.Pair parseDataPair() {
        expect('(');
        skipWhitespace();
        var key = parsePlutusData();
        skipWhitespace();
        expect(',');
        skipWhitespace();
        var value = parsePlutusData();
        skipWhitespace();
        expect(')');
        return new PlutusData.Pair(key, value);
    }

    // ---- Primitives ----

    private BigInteger parseBigInteger() {
        skipWhitespace();
        int start = pos;
        if (pos < input.length() && (input.charAt(pos) == '-' || input.charAt(pos) == '+')) {
            pos++;
        }
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw error("Expected integer");
        }
        int digitCount = pos - start;
        if (digitCount > MAX_INTEGER_DIGITS) {
            throw error("Integer literal too large (" + digitCount + " digits, max " + MAX_INTEGER_DIGITS + ")");
        }
        String numStr = input.substring(start, pos);
        return new BigInteger(numStr);
    }

    private int parseNonNegativeInt() {
        var value = parseBigInteger();
        if (value.signum() < 0) {
            throw error("Expected non-negative integer, got: " + value);
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw error("Integer too large for int: " + value);
        }
    }

    private long parseNonNegativeLong() {
        var value = parseBigInteger();
        if (value.signum() < 0) {
            throw error("Expected non-negative integer, got: " + value);
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw error("Integer too large for long: " + value);
        }
    }

    /**
     * Parse a non-negative integer that fits in an unsigned 64-bit value (0 to 2^64-1).
     * Values above Long.MAX_VALUE are stored as their signed (two's complement) representation.
     */
    private long parseUnsigned64() {
        var value = parseBigInteger();
        if (value.signum() < 0) {
            throw error("Expected non-negative integer, got: " + value);
        }
        if (value.bitLength() > 64) {
            throw error("Value exceeds unsigned 64-bit range: " + value);
        }
        return value.longValue(); // correctly handles values > Long.MAX_VALUE via two's complement
    }

    /**
     * Parse a # prefixed hex byte string: #aabbcc
     */
    private byte[] parseHashByteString() {
        expect('#');
        return parseHexDigits();
    }

    /**
     * Parse a 0x prefixed hex byte string: 0xaabbcc
     */
    private byte[] parse0xByteString() {
        expect('0');
        expect('x');
        return parseHexDigits();
    }

    private byte[] parseHexDigits() {
        int start = pos;
        while (pos < input.length() && isHexDigit(input.charAt(pos))) {
            pos++;
        }
        String hex = input.substring(start, pos);
        if (hex.isEmpty()) {
            return new byte[0];
        }
        if (hex.length() % 2 != 0) {
            throw error("Hex string must have even length: " + hex);
        }
        return HEX.parseHex(hex);
    }

    private String parseQuotedString() {
        expect('"');
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (ch == '"') {
                pos++;
                return sb.toString();
            }
            if (ch == '\\') {
                pos++;
                int cp = parseEscapeCodePoint();
                if (cp >= 0) {
                    sb.appendCodePoint(cp);
                }
                // cp < 0 means null-width escape (\&), append nothing
            } else {
                sb.append(ch);
                pos++;
            }
        }
        throw error("Unterminated string literal");
    }

    /**
     * Parse an escape sequence and return the Unicode code point.
     * Returns -1 for the null-width escape (\&amp;).
     */
    private int parseEscapeCodePoint() {
        if (pos >= input.length()) throw error("Unexpected end of escape sequence");
        char ch = input.charAt(pos);
        pos++;
        return switch (ch) {
            case '\\' -> (int) '\\';
            case '"'  -> (int) '"';
            case '\'' -> (int) '\'';
            case 'n'  -> (int) '\n';
            case 'r'  -> (int) '\r';
            case 't'  -> (int) '\t';
            case 'b'  -> (int) '\b';
            case 'f'  -> (int) '\f';
            case 'a'  -> 7;  // bell
            case 'v'  -> 11; // vertical tab
            case '&'  -> -1; // null-width escape (separator, produces no character)
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                pos--;
                yield parseDecimalEscape();
            }
            case 'x' -> parseHexEscape();
            case 'o' -> parseOctalEscape();
            case 'u' -> {
                if (pos < input.length() && input.charAt(pos) == '{') {
                    pos++;
                    yield parseUnicodeBraceEscape();
                } else {
                    yield parseUnicode4Escape();
                }
            }
            case '^' -> {
                if (pos >= input.length()) throw error("Expected control character");
                char ctrl = input.charAt(pos);
                pos++;
                if (ctrl < '@' || ctrl > '_') {
                    throw error("Invalid control character: \\^" + ctrl
                            + " (must be in range @-_)");
                }
                yield ctrl - '@';
            }
            default -> {
                // Try named ASCII character escapes (Haskell-style)
                pos--; // back up to re-read the character
                int namedChar = tryParseNamedCharEscape();
                if (namedChar >= 0) {
                    yield (char) namedChar;
                }
                throw error("Unknown escape sequence: \\" + ch);
            }
        };
    }

    private int parseDecimalEscape() {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        if (pos == start) throw error("Expected digit in decimal escape");
        int value = java.lang.Integer.parseInt(input.substring(start, pos));
        if (value > 0x10FFFF) {
            throw error("Decimal escape value out of range: " + value + " (max 1114111)");
        }
        return value;
    }

    private int parseHexEscape() {
        int start = pos;
        while (pos < input.length() && isHexDigit(input.charAt(pos))) {
            pos++;
        }
        if (pos == start) throw error("Expected hex digit in escape");
        int value = java.lang.Integer.parseInt(input.substring(start, pos), 16);
        if (value > 0x10FFFF) {
            throw error("Hex escape value out of range: 0x"
                    + java.lang.Integer.toHexString(value) + " (max 0x10FFFF)");
        }
        return value;
    }

    private int parseOctalEscape() {
        int start = pos;
        while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '7') {
            pos++;
        }
        if (pos == start) throw error("Expected octal digit in escape");
        int value = java.lang.Integer.parseInt(input.substring(start, pos), 8);
        if (value > 0x10FFFF) {
            throw error("Octal escape value out of range: " + value + " (max 1114111)");
        }
        return value;
    }

    private int parseUnicodeBraceEscape() {
        // \\u{hhhhhh} - 1-6 hex digits
        int start = pos;
        while (pos < input.length() && isHexDigit(input.charAt(pos))
                && (pos - start) < 6) {
            pos++;
        }
        if (pos == start) throw error("Expected hex digit in unicode escape");
        int end = pos;
        expect('}');
        int value = java.lang.Integer.parseInt(input.substring(start, end), 16);
        if (value > 0x10FFFF) {
            throw error("Unicode escape value out of range: 0x"
                    + java.lang.Integer.toHexString(value) + " (max 0x10FFFF)");
        }
        return value;
    }

    private int parseUnicode4Escape() {
        // \\uhhhh - exactly 4 hex digits
        if (pos + 4 > input.length()) throw error("Expected 4 hex digits for \\u escape");
        String hex = input.substring(pos, pos + 4);
        pos += 4;
        for (char c : hex.toCharArray()) {
            if (!isHexDigit(c)) throw error("Expected hex digit in \\u escape, got: " + c);
        }
        return java.lang.Integer.parseInt(hex, 16);
    }

    /**
     * Parse a word (sequence of non-whitespace, non-delimiter characters).
     */
    private String parseWord() {
        int start = pos;
        while (pos < input.length() && isWordChar(input.charAt(pos))) {
            pos++;
        }
        if (pos == start) throw error("Expected a word");
        return input.substring(start, pos);
    }

    /**
     * Parse a valid UPLC name/identifier.
     * Valid: letters, digits, underscores, apostrophes, hyphens.
     */
    private String parseName() {
        int start = pos;
        while (pos < input.length() && isNameChar(input.charAt(pos))) {
            pos++;
        }
        if (pos == start) throw error("Expected a name");
        return input.substring(start, pos);
    }

    /**
     * Peek at the next word without consuming it.
     */
    private String peekWord() {
        int saved = pos;
        String word = parseWord();
        pos = saved;
        return word;
    }

    // ---- Utilities ----

    private void skipWhitespace() {
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (ch == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                // Line comment: skip to end of line
                pos += 2;
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    pos++;
                }
                if (pos < input.length()) pos++; // skip newline
            } else if (Character.isWhitespace(ch)) {
                pos++;
            } else {
                break;
            }
        }
    }

    private void expect(char expected) {
        if (pos >= input.length()) {
            throw error("Expected '" + expected + "' but reached end of input");
        }
        if (input.charAt(pos) != expected) {
            throw error("Expected '" + expected + "' but got '" + input.charAt(pos) + "'");
        }
        pos++;
    }

    private void expectKeyword(String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            if (pos >= input.length() || input.charAt(pos) != keyword.charAt(i)) {
                throw error("Expected keyword '" + keyword + "'");
            }
            pos++;
        }
        // After a keyword, next char must be whitespace or delimiter
        if (pos < input.length() && isNameChar(input.charAt(pos))) {
            throw error("Expected keyword '" + keyword + "' but got longer word");
        }
    }

    private static boolean isHexDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    private static boolean isWordChar(char ch) {
        return isNameChar(ch);
    }

    private static boolean isNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '\'' || ch == '-';
    }

    /** Named ASCII character escapes (Haskell-style). */
    private static final String[][] NAMED_CHARS = {
            {"NUL", "0"}, {"SOH", "1"}, {"STX", "2"}, {"ETX", "3"},
            {"EOT", "4"}, {"ENQ", "5"}, {"ACK", "6"}, {"BEL", "7"},
            {"BS", "8"}, {"HT", "9"}, {"LF", "10"}, {"VT", "11"},
            {"FF", "12"}, {"CR", "13"}, {"SO", "14"}, {"SI", "15"},
            {"DLE", "16"}, {"DC1", "17"}, {"DC2", "18"}, {"DC3", "19"},
            {"DC4", "20"}, {"NAK", "21"}, {"SYN", "22"}, {"ETB", "23"},
            {"CAN", "24"}, {"EM", "25"}, {"SUB", "26"}, {"ESC", "27"},
            {"FS", "28"}, {"GS", "29"}, {"RS", "30"}, {"US", "31"},
            {"SP", "32"}, {"DEL", "127"},
    };

    /**
     * Try to parse a named ASCII character escape at the current position.
     * Returns the character value, or -1 if no match.
     */
    private int tryParseNamedCharEscape() {
        for (var entry : NAMED_CHARS) {
            String name = entry[0];
            if (input.startsWith(name, pos)) {
                // Ensure the name is not a prefix of a longer identifier
                int end = pos + name.length();
                if (end >= input.length() || !Character.isUpperCase(input.charAt(end))) {
                    pos += name.length();
                    return java.lang.Integer.parseInt(entry[1]);
                }
            }
        }
        return -1;
    }

    /**
     * Require minimum program version for a feature.
     * Throws parse error if the current program version is too low.
     */
    private void requireVersion(int minMajor, int minMinor, String feature) {
        if (versionMajor < minMajor || (versionMajor == minMajor && versionMinor < minMinor)) {
            throw error("'" + feature + "' requires program version >= "
                    + minMajor + "." + minMinor + ".0, but got "
                    + versionMajor + "." + versionMinor + ".x");
        }
    }

    private UplcParseException error(String message) {
        return new UplcParseException(message, pos);
    }

    /**
     * Look up a DefaultFun by its UPLC text name (camelCase, first char lowercase).
     */
    private DefaultFun lookupBuiltin(String name) {
        if (name.isEmpty()) throw error("Empty builtin name");
        // Convert from text format (addInteger) to enum name (AddInteger)
        String enumName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            return DefaultFun.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            // Try exact name as fallback (some names like Bls12_381_... may not follow pattern)
            try {
                return DefaultFun.valueOf(name);
            } catch (IllegalArgumentException e2) {
                throw error("Unknown builtin function: " + name);
            }
        }
    }
}
