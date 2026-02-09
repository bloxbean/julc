package com.bloxbean.cardano.plutus.compiler.codegen;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates PIR terms for encoding/decoding records to/from PlutusData.
 */
public class DataCodecGenerator {

    /**
     * Generate a PIR term that encodes a record to PlutusData.
     * Produces: \f1 \f2 ... -> ConstrData(0, MkCons(encode(f1), MkCons(encode(f2), ... MkNilData())))
     */
    public PirTerm generateToData(PirType.RecordType recordType) {
        // Build the list of encoded fields from right to left: MkCons(last, MkNilData())
        PirTerm fieldList = builtinApp1(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));

        var fields = recordType.fields();
        for (int i = fields.size() - 1; i >= 0; i--) {
            var field = fields.get(i);
            var fieldVar = new PirTerm.Var(field.name(), field.type());
            var encoded = wrapEncode(fieldVar, field.type());
            fieldList = builtinApp2(DefaultFun.MkCons, encoded, fieldList);
        }

        // ConstrData(0, fieldList)
        PirTerm body = builtinApp2(DefaultFun.ConstrData,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                fieldList);

        // Wrap in lambda for each field
        for (int i = fields.size() - 1; i >= 0; i--) {
            var field = fields.get(i);
            body = new PirTerm.Lam(field.name(), field.type(), body);
        }
        return body;
    }

    /**
     * Generate a PIR term that decodes a record from PlutusData.
     * Produces: \data -> let pair = UnConstrData(data)
     *                    let fields = SndPair(pair)
     *                    let f1 = decode(HeadList(fields))
     *                    let rest1 = TailList(fields)
     *                    let f2 = decode(HeadList(rest1))
     *                    ... in result
     */
    public PirTerm generateFromData(PirType.RecordType recordType) {
        var fields = recordType.fields();
        var dataParam = "data__";
        var pairVar = "pair__";
        var fieldsVar = "fields__";

        // Build the extraction chain from inside out
        List<String> fieldNames = new ArrayList<>();
        PirTerm body = buildFieldExtraction(fields, fieldsVar, 0, fieldNames);

        // Wrap in UnConstrData + SndPair
        body = new PirTerm.Let(fieldsVar,
                builtinApp1(DefaultFun.SndPair,
                        new PirTerm.Var(pairVar, new PirType.DataType())),
                body);
        body = new PirTerm.Let(pairVar,
                builtinApp1(DefaultFun.UnConstrData,
                        new PirTerm.Var(dataParam, new PirType.DataType())),
                body);
        body = new PirTerm.Lam(dataParam, new PirType.DataType(), body);
        return body;
    }

    private PirTerm buildFieldExtraction(List<PirType.Field> fields, String listVar,
                                          int index, List<String> fieldNames) {
        if (index >= fields.size()) {
            // All fields extracted - construct the record
            var fieldTerms = new ArrayList<PirTerm>();
            for (var name : fieldNames) {
                fieldTerms.add(new PirTerm.Var(name, new PirType.DataType()));
            }
            // For MVP, just return the last field or unit for empty records
            if (fieldTerms.isEmpty()) {
                return new PirTerm.Const(Constant.unit());
            }
            // Return a tuple/constructor of all decoded fields
            return new PirTerm.DataConstr(0,
                    new PirType.RecordType("result", List.of()),
                    fieldTerms);
        }

        var field = fields.get(index);
        var fieldName = field.name() + "__decoded";
        fieldNames.add(fieldName);

        var headExpr = builtinApp1(DefaultFun.HeadList,
                new PirTerm.Var(listVar, new PirType.DataType()));
        var decodedExpr = wrapDecode(headExpr, field.type());

        if (index + 1 < fields.size()) {
            var restVar = "rest__" + index;
            var restExpr = builtinApp1(DefaultFun.TailList,
                    new PirTerm.Var(listVar, new PirType.DataType()));
            var innerBody = buildFieldExtraction(fields, restVar, index + 1, fieldNames);
            return new PirTerm.Let(fieldName, decodedExpr,
                    new PirTerm.Let(restVar, restExpr, innerBody));
        } else {
            var innerBody = buildFieldExtraction(fields, listVar, index + 1, fieldNames);
            return new PirTerm.Let(fieldName, decodedExpr, innerBody);
        }
    }

    /**
     * Wrap a value in the appropriate encoding builtin based on type.
     */
    PirTerm wrapEncode(PirTerm value, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> builtinApp1(DefaultFun.IData, value);
            case PirType.ByteStringType _ -> builtinApp1(DefaultFun.BData, value);
            case PirType.DataType _ -> value; // Already Data
            case PirType.BoolType _ -> {
                // True -> Constr(1, []), False -> Constr(0, [])
                var nilData = builtinApp1(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
                yield new PirTerm.IfThenElse(value,
                        builtinApp2(DefaultFun.ConstrData, new PirTerm.Const(Constant.integer(BigInteger.ONE)), nilData),
                        builtinApp2(DefaultFun.ConstrData, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), nilData));
            }
            case PirType.ListType _ -> builtinApp1(DefaultFun.ListData, value);
            case PirType.MapType _ -> builtinApp1(DefaultFun.MapData, value);
            case PirType.StringType _ -> builtinApp1(DefaultFun.BData,
                    builtinApp1(DefaultFun.EncodeUtf8, value));
            default -> value; // Pass through for complex types
        };
    }

    /**
     * Wrap a Data value in the appropriate decoding builtin based on type.
     */
    PirTerm wrapDecode(PirTerm data, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> builtinApp1(DefaultFun.UnIData, data);
            case PirType.ByteStringType _ -> builtinApp1(DefaultFun.UnBData, data);
            case PirType.DataType _ -> data;
            case PirType.ListType _ -> builtinApp1(DefaultFun.UnListData, data);
            case PirType.MapType _ -> builtinApp1(DefaultFun.UnMapData, data);
            case PirType.StringType _ -> {
                var byteString = builtinApp1(DefaultFun.UnBData, data);
                yield builtinApp1(DefaultFun.DecodeUtf8, byteString);
            }
            default -> data; // Pass through
        };
    }

    // --- Helpers ---

    private static PirTerm builtinApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }
}
