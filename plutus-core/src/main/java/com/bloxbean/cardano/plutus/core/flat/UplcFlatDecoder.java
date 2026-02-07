package com.bloxbean.cardano.plutus.core.flat;

import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.core.cbor.PlutusDataCborDecoder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes UPLC {@link Program}s and {@link Term}s from FLAT binary format.
 */
public final class UplcFlatDecoder {

    private final FlatReader reader;

    public UplcFlatDecoder(FlatReader reader) {
        this.reader = reader;
    }

    /**
     * Decode a complete program from FLAT bytes.
     */
    public static Program decodeProgram(byte[] data) {
        var decoder = new UplcFlatDecoder(new FlatReader(data));
        return decoder.readProgram();
    }

    /**
     * Decode a program: version triple + term + filler.
     */
    public Program readProgram() {
        int major = reader.natural().intValueExact();
        int minor = reader.natural().intValueExact();
        int patch = reader.natural().intValueExact();
        Term term = readTerm();
        reader.filler();
        return new Program(major, minor, patch, term);
    }

    /**
     * Decode a UPLC term.
     */
    public Term readTerm() {
        int tag = reader.bits8(4);
        return switch (tag) {
            case 0 -> { // Var
                long idx = reader.word64();
                yield new Term.Var(new NamedDeBruijn("i" + idx, (int) idx));
            }
            case 1 -> { // Delay
                Term inner = readTerm();
                yield new Term.Delay(inner);
            }
            case 2 -> { // LamAbs
                Term body = readTerm();
                yield new Term.Lam("i0", body);
            }
            case 3 -> { // Apply
                Term function = readTerm();
                Term argument = readTerm();
                yield new Term.Apply(function, argument);
            }
            case 4 -> { // Const
                yield new Term.Const(readConstant());
            }
            case 5 -> { // Force
                Term inner = readTerm();
                yield new Term.Force(inner);
            }
            case 6 -> new Term.Error(); // Error
            case 7 -> { // Builtin
                int code = reader.bits8(7);
                yield new Term.Builtin(DefaultFun.fromFlatCode(code));
            }
            case 8 -> { // Constr
                long constrTag = reader.word64();
                List<Term> fields = readTermList();
                yield new Term.Constr(constrTag, fields);
            }
            case 9 -> { // Case
                Term scrutinee = readTerm();
                List<Term> branches = readTermList();
                yield new Term.Case(scrutinee, branches);
            }
            default -> throw new FlatDecodingException("Unknown Term tag: " + tag);
        };
    }

    private List<Term> readTermList() {
        var terms = new ArrayList<Term>();
        while (reader.listHasNext()) {
            terms.add(readTerm());
        }
        return terms;
    }

    /**
     * Decode a constant: type tag list + value.
     */
    public Constant readConstant() {
        var typeTags = readTypeTagList();
        int[] pos = {0};
        DefaultUni type = readUniType(typeTags, pos);
        return readConstantValueForType(type);
    }

    /**
     * Read the type tag list.
     * Format: (1-bit element-present + 4-bit type code)*, 0-bit end.
     */
    private List<Integer> readTypeTagList() {
        var tags = new ArrayList<Integer>();
        while (reader.bit()) {  // true = element present
            int tag = reader.bits8(4);
            tags.add(tag);
        }
        // false = end of list
        return tags;
    }

    /**
     * Recursively read a DefaultUni type from the tag list.
     * Handles compound types (Apply) via recursion.
     */
    private DefaultUni readUniType(List<Integer> tags, int[] pos) {
        if (pos[0] >= tags.size()) {
            throw new FlatDecodingException("Type tag list exhausted");
        }
        int tag = tags.get(pos[0]++);
        return switch (tag) {
            case 0 -> DefaultUni.INTEGER;
            case 1 -> DefaultUni.BYTESTRING;
            case 2 -> DefaultUni.STRING;
            case 3 -> DefaultUni.UNIT;
            case 4 -> DefaultUni.BOOL;
            case 5 -> new DefaultUni.ProtoList();
            case 6 -> new DefaultUni.ProtoPair();
            case 7 -> {
                // Apply: recursively read function and argument types
                DefaultUni f = readUniType(tags, pos);
                DefaultUni arg = readUniType(tags, pos);
                yield new DefaultUni.Apply(f, arg);
            }
            case 8 -> DefaultUni.DATA;
            case 9 -> DefaultUni.BLS12_381_G1;
            case 10 -> DefaultUni.BLS12_381_G2;
            case 11 -> DefaultUni.BLS12_381_ML;
            default -> throw new FlatDecodingException("Unknown type tag: " + tag);
        };
    }

    /**
     * Read a constant value given a known DefaultUni type.
     * Handles compound types (lists, pairs) via recursion.
     */
    private Constant readConstantValueForType(DefaultUni type) {
        return switch (type) {
            case DefaultUni.Integer _ -> new Constant.IntegerConst(reader.integer());
            case DefaultUni.ByteString _ -> new Constant.ByteStringConst(reader.byteString());
            case DefaultUni.String _ -> new Constant.StringConst(reader.utf8String());
            case DefaultUni.Unit _ -> new Constant.UnitConst();
            case DefaultUni.Bool _ -> new Constant.BoolConst(reader.bit());
            case DefaultUni.Data _ -> new Constant.DataConst(readData());
            case DefaultUni.Bls12_381_G1_Element _ -> new Constant.Bls12_381_G1Element(reader.byteString());
            case DefaultUni.Bls12_381_G2_Element _ -> new Constant.Bls12_381_G2Element(reader.byteString());
            case DefaultUni.Bls12_381_MlResult _ -> new Constant.Bls12_381_MlResult(reader.byteString());
            case DefaultUni.Apply a -> {
                if (a.f() instanceof DefaultUni.ProtoList) {
                    // List: read elements using elem type
                    DefaultUni elemType = a.arg();
                    var elements = new ArrayList<Constant>();
                    while (reader.listHasNext()) {
                        elements.add(readConstantValueForType(elemType));
                    }
                    yield new Constant.ListConst(elemType, elements);
                } else if (a.f() instanceof DefaultUni.Apply inner
                           && inner.f() instanceof DefaultUni.ProtoPair) {
                    // Pair: read first and second values
                    DefaultUni firstType = inner.arg();
                    DefaultUni secondType = a.arg();
                    Constant first = readConstantValueForType(firstType);
                    Constant second = readConstantValueForType(secondType);
                    yield new Constant.PairConst(first, second);
                } else {
                    throw new FlatDecodingException("Unsupported compound type: " + a);
                }
            }
            case DefaultUni.ProtoList _ -> throw new FlatDecodingException("Bare ProtoList in value position");
            case DefaultUni.ProtoPair _ -> throw new FlatDecodingException("Bare ProtoPair in value position");
        };
    }

    /**
     * Decode PlutusData from FLAT format.
     * <p>
     * Per the Plutus spec, Data constants are stored as CBOR-encoded bytestrings
     * in FLAT format.
     */
    public PlutusData readData() {
        byte[] cborBytes = reader.byteString();
        return PlutusDataCborDecoder.decode(cborBytes);
    }
}
