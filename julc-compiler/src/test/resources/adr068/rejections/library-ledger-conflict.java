// entry: library
// expect: JULC0081
// expect-message: declares org\.julclang\.ledger\.TxOut, which JuLC.s API owns
package org.julclang.ledger;
@OnchainLibrary public class TxOut { public static java.math.BigInteger f() { return java.math.BigInteger.ONE; } }
