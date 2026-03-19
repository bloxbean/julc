package com.bloxbean.cardano.julc.core.flat;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder;

import java.math.BigInteger;

/**
 * Encodes UPLC {@link Program}s and {@link Term}s to FLAT binary format.
 * <p>
 * Encoding follows the Plutus specification:
 * <ul>
 *   <li>Program: 3 version naturals + Term + filler</li>
 *   <li>Term: 4-bit tag + variant-specific payload</li>
 *   <li>Constant: type tag list + value</li>
 *   <li>DefaultFun: 7-bit code</li>
 * </ul>
 */
public final class UplcFlatEncoder {

    private final FlatWriter writer;

    public UplcFlatEncoder() {
        this.writer = new FlatWriter();
    }

    public UplcFlatEncoder(FlatWriter writer) {
        this.writer = writer;
    }

    /**
     * Encode a complete program and return the FLAT bytes.
     */
    public static byte[] encodeProgram(Program program) {
        var encoder = new UplcFlatEncoder();
        encoder.writeProgram(program);
        return encoder.writer.toByteArray();
    }

    /**
     * Encode a program: version triple + term + filler.
     */
    public void writeProgram(Program program) {
        writer.natural(BigInteger.valueOf(program.major()));
        writer.natural(BigInteger.valueOf(program.minor()));
        writer.natural(BigInteger.valueOf(program.patch()));
        writeTerm(program.term());
        writer.filler();
    }

    /**
     * Encode a UPLC term.
     */
    public void writeTerm(Term term) {
        switch (term) {
            case Term.Var v -> {
                writer.bits(4, 0); // tag 0
                writer.word64(v.name().index());
            }
            case Term.Delay d -> {
                writer.bits(4, 1); // tag 1
                writeTerm(d.term());
            }
            case Term.Lam l -> {
                writer.bits(4, 2); // tag 2
                writeTerm(l.body());
            }
            case Term.Apply a -> {
                writer.bits(4, 3); // tag 3
                writeTerm(a.function());
                writeTerm(a.argument());
            }
            case Term.Const c -> {
                writer.bits(4, 4); // tag 4
                writeConstant(c.value());
            }
            case Term.Force f -> {
                writer.bits(4, 5); // tag 5
                writeTerm(f.term());
            }
            case Term.Error _ -> {
                writer.bits(4, 6); // tag 6
            }
            case Term.Builtin b -> {
                writer.bits(4, 7); // tag 7
                writer.bits(7, b.fun().flatCode());
            }
            case Term.Constr c -> {
                writer.bits(4, 8); // tag 8
                writer.word64(c.tag());
                writeTermList(c.fields());
            }
            case Term.Case cs -> {
                writer.bits(4, 9); // tag 9
                writeTerm(cs.scrutinee());
                writeTermList(cs.branches());
            }
        }
    }

    private void writeTermList(java.util.List<Term> terms) {
        for (var t : terms) {
            writer.listCons();
            writeTerm(t);
        }
        writer.listNil();
    }

    /**
     * Encode a constant: type tag list + value.
     */
    public void writeConstant(Constant constant) {
        writeTypeTagList(constant);
        writeConstantValue(constant);
    }

    /**
     * Write the type tag list for a constant.
     * Pre-order traversal of the type tree, each tag: 1-bit + 4-bit code, terminated by 0-bit.
     */
    private void writeTypeTagList(Constant constant) {
        writeTypeTagsForUni(constant.type());
        writer.bit(false); // end of list
    }

    /**
     * Recursively write type tags for a DefaultUni type (pre-order traversal).
     */
    private void writeTypeTagsForUni(DefaultUni uni) {
        switch (uni) {
            case DefaultUni.Apply a -> {
                writer.bit(true);
                writer.bits(4, 7); // Apply
                writeTypeTagsForUni(a.f());
                writeTypeTagsForUni(a.arg());
            }
            default -> {
                writer.bit(true);
                writer.bits(4, uniToTag(uni));
            }
        }
    }

    private int uniToTag(DefaultUni uni) {
        return switch (uni) {
            case DefaultUni.Integer _ -> 0;
            case DefaultUni.ByteString _ -> 1;
            case DefaultUni.String _ -> 2;
            case DefaultUni.Unit _ -> 3;
            case DefaultUni.Bool _ -> 4;
            case DefaultUni.ProtoList _ -> 5;
            case DefaultUni.ProtoPair _ -> 6;
            case DefaultUni.Apply _ -> 7;
            case DefaultUni.Data _ -> 8;
            case DefaultUni.Bls12_381_G1_Element _ -> 9;
            case DefaultUni.Bls12_381_G2_Element _ -> 10;
            case DefaultUni.Bls12_381_MlResult _ -> 11;
            case DefaultUni.ProtoArray _ -> 12;
            case DefaultUni.ProtoValue _ -> 13;
        };
    }

    /**
     * Write the value of a constant (after the type tag list).
     */
    private void writeConstantValue(Constant constant) {
        switch (constant) {
            case Constant.IntegerConst ic -> writer.integer(ic.value());
            case Constant.ByteStringConst bs -> writer.byteString(bs.value());
            case Constant.StringConst s -> writer.utf8String(s.value());
            case Constant.UnitConst _ -> {} // no payload
            case Constant.BoolConst b -> writer.bit(b.value());
            case Constant.DataConst d -> writeData(d.value());
            case Constant.Bls12_381_G1Element g1 -> writer.byteString(g1.value());
            case Constant.Bls12_381_G2Element g2 -> writer.byteString(g2.value());
            case Constant.Bls12_381_MlResult ml -> writer.byteString(ml.value());
            case Constant.ListConst lc -> {
                for (var elem : lc.values()) {
                    writer.listCons();
                    writeConstantValue(elem);
                }
                writer.listNil();
            }
            case Constant.PairConst pc -> {
                writeConstantValue(pc.first());
                writeConstantValue(pc.second());
            }
            case Constant.ArrayConst ac -> {
                for (var elem : ac.values()) {
                    writer.listCons();
                    writeConstantValue(elem);
                }
                writer.listNil();
            }
            case Constant.ValueConst vc -> {
                for (var entry : vc.entries()) {
                    writer.listCons();
                    writer.byteString(entry.policyId());
                    for (var token : entry.tokens()) {
                        writer.listCons();
                        writer.byteString(token.tokenName());
                        writer.integer(token.quantity());
                    }
                    writer.listNil();
                }
                writer.listNil();
            }
        }
    }

    /**
     * Encode PlutusData in FLAT format.
     * <p>
     * Per the Plutus spec, Data constants are CBOR-encoded and then written
     * as a FLAT bytestring (pre-aligned, chunked).
     */
    public void writeData(PlutusData data) {
        byte[] cborBytes = PlutusDataCborEncoder.encode(data);
        writer.byteString(cborBytes);
    }
}
