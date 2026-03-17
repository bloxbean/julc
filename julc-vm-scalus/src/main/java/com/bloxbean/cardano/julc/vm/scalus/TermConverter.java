package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.Constant;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts Scalus evaluation result terms back to plutus-java types.
 * <p>
 * For the Program to Scalus direction, we use FLAT serialization as the bridge,
 * which avoids all Scala/Java interop issues with collections and reserved keywords.
 * This class only handles the result conversion (Scalus to ours).
 * <p>
 * Key interop workarounds:
 * <ul>
 *   <li>Term.Const: "const" is a Java reserved keyword, so we use Product.productElement()</li>
 *   <li>Term.Error: no separate class in Scala 3 enum, accessed via static field</li>
 *   <li>Term.Constr.tag(): returns Word64, converted via toLong()</li>
 *   <li>Data.Constr/List/Map: args use Scalus PList which has incompatible class metadata,
 *       so we walk via Product interface to avoid referencing the PList type</li>
 * </ul>
 */
final class TermConverter {

    private TermConverter() {}

    // ---- Term: Scalus -> ours ----

    static Term fromScalus(scalus.uplc.Term term) {
        if (term instanceof scalus.uplc.Term.Var v) {
            return Term.var(new NamedDeBruijn(v.name().name(), v.name().index()));
        } else if (term instanceof scalus.uplc.Term.LamAbs l) {
            return Term.lam(l.name(), fromScalus(l.term()));
        } else if (term instanceof scalus.uplc.Term.Apply a) {
            return Term.apply(fromScalus(a.f()), fromScalus(a.arg()));
        } else if (term instanceof scalus.uplc.Term.Force f) {
            return Term.force(fromScalus(f.term()));
        } else if (term instanceof scalus.uplc.Term.Delay d) {
            return Term.delay(fromScalus(d.term()));
        } else if (term instanceof scalus.uplc.Term.Const c) {
            // "const" is a Java reserved keyword, so we use Product.productElement()
            var scalusConst = (scalus.uplc.Constant) ((scala.Product) c).productElement(0);
            return Term.const_(fromScalusConstant(scalusConst));
        } else if (term instanceof scalus.uplc.Term.Builtin b) {
            return Term.builtin(fromScalusDefaultFun(b.bn()));
        } else if (term instanceof scalus.uplc.Term.Error) {
            return Term.error();
        } else if (term instanceof scalus.uplc.Term.Constr c) {
            var fields = new ArrayList<Term>();
            var iter = c.args().iterator();
            while (iter.hasNext()) {
                fields.add(fromScalus(iter.next()));
            }
            // Word64.toLong() converts Scalus Word64 to Java long
            return new Term.Constr(c.tag().toLong(), fields);
        } else if (term instanceof scalus.uplc.Term.Case cs) {
            var branches = new ArrayList<Term>();
            var iter = cs.cases().iterator();
            while (iter.hasNext()) {
                branches.add(fromScalus(iter.next()));
            }
            return new Term.Case(fromScalus(cs.arg()), branches);
        }
        throw new IllegalArgumentException("Unknown Scalus term type: " + term.getClass());
    }

    // ---- Constant: Scalus -> ours ----

    static Constant fromScalusConstant(scalus.uplc.Constant constant) {
        if (constant instanceof scalus.uplc.Constant.Integer i) {
            return Constant.integer(i.value().bigInteger());
        } else if (constant instanceof scalus.uplc.Constant.ByteString bs) {
            return Constant.byteString(bs.value().bytes());
        } else if (constant instanceof scalus.uplc.Constant.String s) {
            return Constant.string(s.value());
        } else if (constant == scalus.uplc.Constant.Unit$.MODULE$) {
            return Constant.unit();
        } else if (constant instanceof scalus.uplc.Constant.Bool b) {
            return Constant.bool(b.value());
        } else if (constant instanceof scalus.uplc.Constant.Data d) {
            return Constant.data(fromScalusData(d.value()));
        } else if (constant instanceof scalus.uplc.Constant.List l) {
            var values = new ArrayList<Constant>();
            var iter = l.value().iterator();
            while (iter.hasNext()) {
                values.add(fromScalusConstant(iter.next()));
            }
            return new Constant.ListConst(
                    fromScalusDefaultUni(l.elemType()), values);
        } else if (constant instanceof scalus.uplc.Constant.Pair p) {
            return new Constant.PairConst(
                    fromScalusConstant(p.a()),
                    fromScalusConstant(p.b()));
        } else if (constant instanceof scalus.uplc.Constant.BLS12_381_G1_Element g1) {
            return new Constant.Bls12_381_G1Element(g1.value().toCompressedByteString().bytes());
        } else if (constant instanceof scalus.uplc.Constant.BLS12_381_G2_Element g2) {
            return new Constant.Bls12_381_G2Element(g2.value().toCompressedByteString().bytes());
        } else if (constant instanceof scalus.uplc.Constant.BLS12_381_MlResult) {
            // MlResult is an opaque type (blst.PT) — it cannot be serialized to bytes.
            // It should only appear as an intermediate value, never in final output.
            throw new IllegalArgumentException("BLS12-381 MlResult cannot be converted to bytes");
        }
        throw new IllegalArgumentException("Unsupported Scalus constant type: " + constant.getClass());
    }

    // ---- PlutusData: Scalus Data -> ours ----
    // Data variants use Scalus PList (scalus.cardano.onchain.plutus.prelude.List) which has
    // incompatible class metadata (undeclared type variables) that Java can't handle.
    // We use Product.productElement() to get the PList as Object, then walk it via
    // the Product interface (Cons has productArity=2: head + tail).

    static PlutusData fromScalusData(scalus.uplc.builtin.Data data) {
        if (data instanceof scalus.uplc.builtin.Data.I i) {
            return PlutusData.integer(i.value().bigInteger());
        } else if (data instanceof scalus.uplc.builtin.Data.B b) {
            return PlutusData.bytes(b.value().bytes());
        } else if (data instanceof scalus.uplc.builtin.Data.Constr) {
            scala.Product constr = (scala.Product) data;
            var constrTag = (scala.math.BigInt) constr.productElement(0);
            Object argsPList = constr.productElement(1);

            var fields = new ArrayList<PlutusData>();
            walkPList(argsPList, fields, true);
            return PlutusData.constr(
                    constrTag.bigInteger().intValueExact(),
                    fields.toArray(PlutusData[]::new));
        } else if (data instanceof scalus.uplc.builtin.Data.List) {
            scala.Product list = (scala.Product) data;
            Object valuesPList = list.productElement(0);

            var items = new ArrayList<PlutusData>();
            walkPList(valuesPList, items, true);
            return PlutusData.list(items.toArray(PlutusData[]::new));
        } else if (data instanceof scalus.uplc.builtin.Data.Map) {
            scala.Product map = (scala.Product) data;
            Object valuesPList = map.productElement(0);

            var entries = new ArrayList<PlutusData.Pair>();
            // Walk PList of Tuple2<Data, Data>
            Object current = valuesPList;
            while (current instanceof scala.Product p && p.productArity() == 2) {
                var tuple = (scala.Tuple2<?, ?>) p.productElement(0);
                entries.add(new PlutusData.Pair(
                        fromScalusData((scalus.uplc.builtin.Data) tuple._1()),
                        fromScalusData((scalus.uplc.builtin.Data) tuple._2())));
                current = p.productElement(1);
            }
            return PlutusData.map(entries.toArray(PlutusData.Pair[]::new));
        }
        throw new IllegalArgumentException("Unknown Scalus Data type: " + data.getClass());
    }

    /**
     * Walk a Scalus PList (Cons/Nil linked list) using the Product interface.
     * Cons has productArity=2 (head, tail), Nil terminates the walk.
     * Elements are converted to PlutusData if {@code asData} is true.
     */
    private static void walkPList(Object plist, ArrayList<PlutusData> result, boolean asData) {
        Object current = plist;
        while (current instanceof scala.Product p && p.productArity() == 2) {
            result.add(fromScalusData((scalus.uplc.builtin.Data) p.productElement(0)));
            current = p.productElement(1);
        }
    }

    // ---- DefaultFun: Scalus -> ours ----

    static DefaultFun fromScalusDefaultFun(scalus.uplc.DefaultFun fun) {
        return DefaultFun.valueOf(fun.toString());
    }

    // ---- DefaultUni: Scalus -> ours ----

    static com.bloxbean.cardano.julc.core.DefaultUni fromScalusDefaultUni(scalus.uplc.DefaultUni uni) {
        if (uni == scalus.uplc.DefaultUni.Integer$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.INTEGER;
        if (uni == scalus.uplc.DefaultUni.ByteString$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.BYTESTRING;
        if (uni == scalus.uplc.DefaultUni.String$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.STRING;
        if (uni == scalus.uplc.DefaultUni.Unit$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.UNIT;
        if (uni == scalus.uplc.DefaultUni.Bool$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.BOOL;
        if (uni == scalus.uplc.DefaultUni.Data$.MODULE$) return com.bloxbean.cardano.julc.core.DefaultUni.DATA;
        if (uni == scalus.uplc.DefaultUni.ProtoList$.MODULE$) return new com.bloxbean.cardano.julc.core.DefaultUni.ProtoList();
        if (uni == scalus.uplc.DefaultUni.ProtoPair$.MODULE$) return new com.bloxbean.cardano.julc.core.DefaultUni.ProtoPair();
        if (uni instanceof scalus.uplc.DefaultUni.Apply a) {
            return new com.bloxbean.cardano.julc.core.DefaultUni.Apply(
                    fromScalusDefaultUni(a.f()),
                    fromScalusDefaultUni(a.arg()));
        }
        throw new IllegalArgumentException("Unsupported Scalus DefaultUni type: " + uni.getClass());
    }
}
