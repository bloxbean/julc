package com.example.lib;
import java.math.BigInteger;
@OnchainLibrary
public class Limits {
    public static boolean within(BigInteger a) { return a.compareTo(BigInteger.TEN) < 0; }
    public static boolean within(long a) { return a < 1000; }
}