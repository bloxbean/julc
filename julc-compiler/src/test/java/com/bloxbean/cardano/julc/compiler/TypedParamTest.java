package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for typed @Param: record, sealed interface, and @NewType.
 * Verifies that the compiler infrastructure (TypeResolver, wrapDecode, lambda wrapping)
 * correctly handles non-primitive @Param types end-to-end.
 */
class TypedParamTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    static PlutusData buildTxInfo(PlutusData[] signatories) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                alwaysInterval(),                                      // 7: validRange
                PlutusData.list(signatories),                          // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
        );
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    static PlutusData simpleCtx() {
        return buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
    }

    // =========================================================================
    // Part A: @Param with record type
    // =========================================================================

    @Nested
    class RecordParam {

        @Test
        void recordParamFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class RecordParamValidator {
                        record TokenConfig(byte[] policyId, BigInteger minAmount) {}

                        @Param TokenConfig config;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            BigInteger min = config.minAmount();
                            return min > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertTrue(result.isParameterized());
            assertEquals(1, result.params().size());
            assertEquals("config", result.params().get(0).name());
            assertEquals("TokenConfig", result.params().get(0).type());

            // TokenConfig = Constr(0, [policyId, minAmount])
            var configData = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{0x01, 0x02, 0x03}),
                    PlutusData.integer(100));
            var concrete = result.program().applyParams(configData);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "Record param field access (minAmount > 0) should succeed: " + evalResult);
        }

        @Test
        void recordParamMultipleFieldAccess() {
            // Access both fields of a record param and use them in logic
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class PolicyValidator {
                        record TokenConfig(byte[] policyId, BigInteger minAmount) {}

                        @Param TokenConfig config;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            BigInteger min = config.minAmount();
                            byte[] policy = config.policyId();
                            return min > 10;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertTrue(result.isParameterized());

            // minAmount=50 > 10 → true
            var configData = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{(byte) 0xAA, (byte) 0xBB}),
                    PlutusData.integer(50));
            var concrete = result.program().applyParams(configData);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "Record param multiple field access should succeed: " + evalResult);
        }

        @Test
        void recordParamMinAmountFails() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class ThresholdValidator {
                        record TokenConfig(byte[] policyId, BigInteger minAmount) {}

                        @Param TokenConfig config;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return config.minAmount() > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);

            // minAmount = 0 → should fail (0 > 0 is false)
            var configData = PlutusData.constr(0,
                    PlutusData.bytes(new byte[28]),
                    PlutusData.integer(0));
            var concrete = result.program().applyParams(configData);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertFalse(evalResult.isSuccess(), "Record param with minAmount=0 should fail");
        }
    }

    // =========================================================================
    // Part B: @Param with sealed interface (Credential)
    // =========================================================================

    @Nested
    class SealedInterfaceParam {

        @Test
        void sealedInterfaceParamSwitchTag0() {
            // Credential is pre-registered as a ledger SumType.
            // PubKeyCredential = Constr(0, [hash]), ScriptCredential = Constr(1, [hash])
            var source = """
                    import com.bloxbean.cardano.julc.ledger.Credential;

                    @Validator
                    class CredentialParamValidator {
                        @Param Credential cred;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return switch (cred) {
                                case Credential.PubKeyCredential pk -> true;
                                case Credential.ScriptCredential sc -> false;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertTrue(result.isParameterized());
            assertEquals("Credential", result.params().get(0).type());

            // PubKeyCredential = Constr(0, [hash]) → should return true
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var concrete = result.program().applyParams(pubKeyCred);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "PubKeyCredential param should match tag 0 branch (true): " + evalResult);
        }

        @Test
        void sealedInterfaceParamSwitchTag1() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.Credential;

                    @Validator
                    class CredentialParamValidator2 {
                        @Param Credential cred;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return switch (cred) {
                                case Credential.PubKeyCredential pk -> false;
                                case Credential.ScriptCredential sc -> true;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);

            // ScriptCredential = Constr(1, [hash]) → should return true
            var scriptCred = PlutusData.constr(1, PlutusData.bytes(new byte[28]));
            var concrete = result.program().applyParams(scriptCred);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "ScriptCredential param should match tag 1 branch (true): " + evalResult);
        }

        @Test
        void sealedInterfaceParamFieldAccessInSwitch() {
            // Extract the hash from each Credential variant and use it
            // PubKeyCredential pk.hash() extracts the ByteString field
            var source = """
                    import com.bloxbean.cardano.julc.ledger.Credential;
                    import java.math.BigInteger;

                    @Validator
                    class CredFieldValidator {
                        @Param Credential cred;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return switch (cred) {
                                case Credential.PubKeyCredential pk -> true;
                                case Credential.ScriptCredential sc -> true;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertTrue(result.isParameterized());

            // ScriptCredential with 28-byte hash → both branches return true
            var cred = PlutusData.constr(1, PlutusData.bytes(new byte[28]));
            var concrete = result.program().applyParams(cred);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "Credential param ScriptCredential branch should succeed: " + evalResult);
        }

        @Test
        void inlineSealedInterfaceParam() {
            // Inline sealed interface (not pre-registered ledger type)
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class ActionParamValidator {
                        @Param Action action;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return switch (action) {
                                case Mint m -> m.amt() > 0;
                                case Burn b -> b.amt() > 0;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertTrue(result.isParameterized());
            assertEquals("Action", result.params().get(0).type());

            // Mint = Constr(0, [amt=42]) → 42 > 0 → true
            var mintAction = PlutusData.constr(0, PlutusData.integer(42));
            var concrete = result.program().applyParams(mintAction);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "Inline sealed interface Mint param should succeed: " + evalResult);

            // Burn = Constr(1, [amt=10]) → 10 > 0 → true
            var burnAction = PlutusData.constr(1, PlutusData.integer(10));
            concrete = result.program().applyParams(burnAction);
            evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "Inline sealed interface Burn param should succeed: " + evalResult);
        }

        @Test
        void inlineSealedInterfaceParamFails() {
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class ActionFailValidator {
                        @Param Action action;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return switch (action) {
                                case Mint m -> m.amt() > 0;
                                case Burn b -> b.amt() > 0;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);

            // Burn = Constr(1, [amt=0]) → 0 > 0 → false
            var burnZero = PlutusData.constr(1, PlutusData.integer(0));
            var concrete = result.program().applyParams(burnZero);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertFalse(evalResult.isSuccess(),
                    "Sealed interface param with amt=0 should fail");
        }
    }

    // =========================================================================
    // Part B2: Switch pattern variable edge cases
    // =========================================================================

    @Nested
    class SwitchPatternVarEdgeCases {

        @Test
        void patternVarAsMethodArg() {
            // Pattern var used as method argument — the original bug scenario
            // Uses inline sealed interface to avoid Credential.of() factory issues
            var source = """
                    import java.math.BigInteger;

                    sealed interface Token permits FungibleToken, NFT {}
                    record FungibleToken(long amount) implements Token {}
                    record NFT(byte[] name) implements Token {}

                    @Validator
                    class PatternVarArgValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Token token = (Token)(Object) redeemer;
                            return switch (token) {
                                case FungibleToken ft -> ft.amount() > 0;
                                case NFT nft -> nft.name().length() > 0;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // FungibleToken(42) → 42 > 0 → true
            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(42)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Pattern var as method arg should compile and succeed: " + evalResult);
        }

        @Test
        void patternVarAsReturnValue() {
            // Pattern var used in computation — cast to get tag, compare with expected
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class PatternVarReturnValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Action action = (Action)(Object) redeemer;
                            long result = switch (action) {
                                case Mint m -> m.amt() + 1;
                                case Burn b -> b.amt() - 1;
                            };
                            return result > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Mint(amt=5) → 5+1=6 > 0 → true
            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(5)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Pattern var field access in return should succeed: " + evalResult);
        }

        @Test
        void patternVarFieldAccessAndDirectRefInSameBranch() {
            // Field access on pattern var — accesses fields from different branches
            var source = """
                    import java.math.BigInteger;

                    sealed interface Coord permits Point2D, Point3D {}
                    record Point2D(long x, long y) implements Coord {}
                    record Point3D(long x, long y, long z) implements Coord {}

                    @Validator
                    class BothAccessValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Coord coord = (Coord)(Object) redeemer;
                            long sum = switch (coord) {
                                case Point2D p -> p.x() + p.y();
                                case Point3D p -> p.x() + p.y() + p.z();
                            };
                            return sum > 5;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Point2D(3, 4) → 3+4=7 > 5 → true
            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(3), PlutusData.integer(4)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Multi-field pattern var access (Point2D) should succeed: " + evalResult);

            // Point3D(1, 1, 1) → 1+1+1=3 > 5 → false
            var ctx3d = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(1, PlutusData.integer(1), PlutusData.integer(1), PlutusData.integer(1)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx3d));
            assertFalse(evalResult.isSuccess(),
                    "Multi-field pattern var access (Point3D sum=3) should fail");
        }

        @Test
        void patternVarInBothBranches() {
            // Both branches use their pattern variables in expressions
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class BothBranchesPatternVar {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Action action = (Action)(Object) redeemer;
                            return switch (action) {
                                case Mint m -> m.amt() > 0;
                                case Burn b -> b.amt() > 0;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Mint(42) → 42 > 0 → true
            var mintCtx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(42)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(mintCtx));
            assertTrue(evalResult.isSuccess(),
                    "Pattern var in both branches (Mint) should succeed: " + evalResult);

            // Burn(10) → 10 > 0 → true
            var burnCtx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(1, PlutusData.integer(10)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            evalResult = vm.evaluateWithArgs(result.program(), List.of(burnCtx));
            assertTrue(evalResult.isSuccess(),
                    "Pattern var in both branches (Burn) should succeed: " + evalResult);
        }

        @Test
        void patternVarUnusedStillCompiles() {
            // case Mint m -> true — pattern var never referenced, should still compile
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class UnusedPatternVar {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Action action = (Action)(Object) redeemer;
                            return switch (action) {
                                case Mint m -> true;
                                case Burn b -> false;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Mint redeemer embedded in ScriptContext
            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(1)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Unused pattern var (Mint → true) should succeed: " + evalResult);
        }

        @Test
        void patternVarInEquality() {
            // Equality check using field extracted from pattern var
            var source = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(long amt) implements Action {}
                    record Burn(long amt) implements Action {}

                    @Validator
                    class PatternVarEqValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Action action = (Action)(Object) redeemer;
                            return switch (action) {
                                case Mint m -> m.amt() == 42;
                                case Burn b -> b.amt() == 42;
                            };
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Mint(42) → 42 == 42 → true
            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(42)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Pattern var field in equality should succeed: " + evalResult);

            // Burn(10) → 10 == 42 → false
            var ctx2 = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(1, PlutusData.integer(10)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx2));
            assertFalse(evalResult.isSuccess(),
                    "Pattern var field in equality (10 != 42) should fail");
        }

        @Test
        void patternVarWithInlineSealedInterface() {
            // Inline sealed interface (not ledger type), pattern var as direct ref
            var source = """
                    import java.math.BigInteger;

                    sealed interface Shape permits Circle, Square {}
                    record Circle(long radius) implements Shape {}
                    record Square(long side) implements Shape {}

                    @Validator
                    class InlineSealedPatternVar {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Shape shape = (Shape)(Object) redeemer;
                            long area = switch (shape) {
                                case Circle c -> c.radius() * c.radius() * 3;
                                case Square s -> s.side() * s.side();
                            };
                            return area > 10;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());

            // Circle(radius=5) → 5*5*3 = 75 > 10 → true
            var circleCtx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(0, PlutusData.integer(5)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(circleCtx));
            assertTrue(evalResult.isSuccess(),
                    "Inline sealed with pattern var (Circle) should succeed: " + evalResult);

            // Square(side=2) → 2*2 = 4 > 10 → false
            var squareCtx = buildScriptContext(
                    buildTxInfo(new PlutusData[0]),
                    PlutusData.constr(1, PlutusData.integer(2)),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            evalResult = vm.evaluateWithArgs(result.program(), List.of(squareCtx));
            assertFalse(evalResult.isSuccess(),
                    "Inline sealed with pattern var (Square side=2) should fail");
        }
    }

    // =========================================================================
    // Part C: @Param with @NewType
    // =========================================================================

    @Nested
    class NewTypeParam {

        @Test
        void newTypeByteArrayParamLength() {
            // @NewType byte[] decodes via UnBData. asset.length() uses LengthOfByteString.
            var source = """
                    @Validator
                    class NewTypeParamValidator {
                        @NewType
                        record AssetName(byte[] name) {}

                        @Param AssetName asset;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return asset.length() > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertTrue(result.isParameterized());
            assertEquals("AssetName", result.params().get(0).type());

            // @NewType byte[] → UnBData decode, so pass BData-wrapped bytes
            var assetData = PlutusData.bytes(new byte[]{0x01, 0x02, 0x03});
            var concrete = result.program().applyParams(assetData);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "@NewType byte[] param length check should succeed: " + evalResult);
        }

        @Test
        void newTypeByteArrayParamEmpty() {
            var source = """
                    @Validator
                    class NewTypeEmptyValidator {
                        @NewType
                        record AssetName(byte[] name) {}

                        @Param AssetName asset;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return asset.length() > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);

            // Empty bytes → length 0 → 0 > 0 → false
            var emptyAsset = PlutusData.bytes(new byte[]{});
            var concrete = result.program().applyParams(emptyAsset);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertFalse(evalResult.isSuccess(),
                    "@NewType byte[] param with empty bytes should fail");
        }

        @Test
        void newTypeIntegerParam() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class NewTypeIntValidator {
                        @NewType
                        record Amount(BigInteger value) {}

                        @Param Amount amt;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return amt > 0;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertTrue(result.isParameterized());
            assertEquals("Amount", result.params().get(0).type());

            // @NewType BigInteger → UnIData decode
            var amountData = PlutusData.integer(500);
            var concrete = result.program().applyParams(amountData);
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "@NewType BigInteger param (500 > 0) should succeed: " + evalResult);
        }

        @Test
        void newTypeUsedInArithmetic() {
            // @NewType BigInteger can be used directly in arithmetic
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class NewTypeArithValidator {
                        @NewType
                        record Score(BigInteger value) {}

                        @Param Score threshold;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var doubled = threshold + threshold;
                            return doubled > 10;
                        }
                    }
                    """;
            var result = new JulcCompiler().compile(source);
            assertTrue(result.isParameterized());

            // threshold=8 → doubled=16 > 10 → true
            var concrete = result.program().applyParams(PlutusData.integer(8));
            var evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertTrue(evalResult.isSuccess(),
                    "@NewType BigInteger param arithmetic (8+8=16 > 10) should succeed: " + evalResult);

            // threshold=3 → doubled=6 > 10 → false
            concrete = result.program().applyParams(PlutusData.integer(3));
            evalResult = vm.evaluateWithArgs(concrete, List.of(simpleCtx()));
            assertFalse(evalResult.isSuccess(),
                    "@NewType BigInteger param arithmetic (3+3=6 > 10) should fail");
        }
    }
}
