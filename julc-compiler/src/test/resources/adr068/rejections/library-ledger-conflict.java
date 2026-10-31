// entry: library
// expect-message: Library type conflicts with bundled ledger type
package org.julclang.ledger;
@OnchainLibrary public class TxOut { public static java.math.BigInteger f() { return java.math.BigInteger.ONE; } }
