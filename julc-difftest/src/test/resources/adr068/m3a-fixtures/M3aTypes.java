package m3a.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.Address;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;

/** ADR-068 M3a: a name's type in PIR, read from javac: records, a sum, a ledger type, a recursive type and a raw list. */
sealed interface Shape permits Shape.Circle, Shape.Square {
    record Circle(BigInteger radius) implements Shape { }
    record Square(BigInteger side, Inner inner) implements Shape { }
}

record Inner(boolean on, byte[] id) { }

sealed interface Tree permits Tree.Leaf, Tree.Node {
    record Leaf(BigInteger value) implements Tree { }
    record Node(Tree left, Tree right) implements Tree { }
}

@MintingValidator
class M3aTypes {
    static Shape shape(Shape s) { return s; }
    static Inner inner(Inner i) { return i; }
    static Shape.Circle circle(Shape.Circle c) { return c; }
    static Tree tree(Tree t) { return t; }
    static Address address(Address a) { return a; }
    static ScriptContext context(ScriptContext c) { return c; }

    @Entrypoint
    static boolean validate(Inner redeemer, ScriptContext ctx) {
        return true;
    }
}
