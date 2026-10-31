package com.example.lib;
import java.math.BigInteger;
@OnchainLibrary
public class Shapes {
    public sealed interface Shape permits Box, Dot {}
    public record Box(BigInteger side) implements Shape {}
    public record Dot() implements Shape {}
    static final Box unit = new Box(BigInteger.ONE);
    public static BigInteger side(Shape shape) {
        if (shape instanceof Box unit && unit.side().signum() > 0) { return unit.side(); }
        return BigInteger.ZERO;
    }
}