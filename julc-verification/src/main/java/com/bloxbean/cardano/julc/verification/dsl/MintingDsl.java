package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.OneShotMintProperty;
import com.bloxbean.cardano.julc.verification.VerificationProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Reviewed E.4a minting expressions and authoritative promotion. */
public final class MintingDsl {
    private MintingDsl() { }

    public static DslPropertySet controlledMintPropertySet(
            String propertyId, String authorityHex, String tokenNameHex, String quantity) {
        var contract = new MintingContractModel();
        var signedQuantity = integer(quantity);
        var direction = new BigInteger(quantity).signum() > 0
                ? signedQuantity.gt(integer(0)) : signedQuantity.lt(integer(0));
        var guarantee = contract.redeemerStrictlyDecodes()
                .and(contract.context().txInfo().signatories().contains(keyHash(authorityHex)))
                .and(contract.context().txInfo().mint().exactOwnPolicyAsset(
                        contract.ownPolicy(), tokenName(tokenNameHex), signedQuantity))
                .and(direction);
        return DslPropertySet.minting(property(
                propertyId, contract.exactUplcSucceeds().implies(guarantee)));
    }

    public static DslPropertySet oneShotPropertySet(
            String propertyId,
            String authorityHex,
            String anchorTransactionIdHex,
            long anchorOutputIndex,
            String tokenNameHex) {
        var contract = new MintingContractModel();
        var guarantee = contract.redeemerStrictlyDecodes()
                .and(contract.context().txInfo().signatories().contains(keyHash(authorityHex)))
                .and(contract.context().txInfo().inputs().consumes(
                        txOutRef(anchorTransactionIdHex, anchorOutputIndex)))
                .and(contract.context().txInfo().mint().exactOwnPolicyAsset(
                        contract.ownPolicy(), tokenName(tokenNameHex), integer(1)));
        return DslPropertySet.minting(property(propertyId,
                contract.validMintingContext().and(contract.exactUplcSucceeds())
                        .implies(guarantee)));
    }

    public static VerificationProperty resolve(
            DslPropertySet candidate,
            ContractSchema schema,
            String validatorTitle,
            String sourcePath) {
        DslPropertyValidator.validate(candidate, schema, DslPropertyValidator.MAX_AST_NODES);
        DslProperty property = candidate.properties().getFirst();
        BoolBinaryNode implication = (BoolBinaryNode) property.expression();
        List<PropertyNode> guarantees = flattenAnd(implication.right());
        String redeemerType = redeemerType(schema);
        if (isRoot(implication.left(), "exactUplcSucceeds")) {
            return controlled(candidate, property.id(), validatorTitle, sourcePath,
                    redeemerType, guarantees);
        }
        return oneShot(candidate, property.id(), validatorTitle, sourcePath,
                redeemerType, guarantees);
    }

    private static ControlledMintProperty controlled(
            DslPropertySet candidate,
            String propertyId,
            String validatorTitle,
            String sourcePath,
            String redeemerType,
            List<PropertyNode> rules) {
        if (rules.size() != 4 || !isRoot(rules.get(0), "redeemerStrictlyDecodes")) {
            throw unsupported(ControlledMintProperty.TEMPLATE);
        }
        BytesLiteralNode authority = signerLiteral(rules.get(1));
        ExactOwnPolicyAssetNode asset = exactAsset(rules.get(2));
        BytesLiteralNode token = tokenLiteral(asset);
        String quantity = integerLiteral(asset.quantity());
        CompareNode direction = rules.get(3) instanceof CompareNode value ? value : null;
        if (direction == null || !direction.left().equals(asset.quantity())
                || !(direction.right() instanceof LiteralNode zero)
                || !"0".equals(zero.value())) {
            throw unsupported(ControlledMintProperty.TEMPLATE);
        }
        BigInteger signed = new BigInteger(quantity);
        String action;
        if (direction.operator() == CompareOperator.GT && signed.signum() > 0) {
            action = "MINT";
        } else if (direction.operator() == CompareOperator.LT && signed.signum() < 0) {
            action = "BURN";
        } else {
            throw unsupported(ControlledMintProperty.TEMPLATE);
        }
        String canonical = PropertyIrCodec.canonicalJson(candidate);
        return new ControlledMintProperty(
                ControlledMintProperty.SCHEMA_VERSION, ControlledMintProperty.TEMPLATE,
                propertyId, validatorTitle, "minting", sourcePath,
                authority.hex(), token.hex(), quantity, action, redeemerType, canonical,
                new ControlledMintProperty.SourceReference(sourcePath, 1, 1, "typed DSL"),
                List.of(), List.of(
                        "strict redeemer decoding",
                        "fixed authority occurs in the complete signatory list",
                        "exactly one raw entry exists for the current policy",
                        "the current policy contains exactly the configured token and quantity",
                        "mint or burn direction matches the configured action"), false);
    }

    private static OneShotMintProperty oneShot(
            DslPropertySet candidate,
            String propertyId,
            String validatorTitle,
            String sourcePath,
            String redeemerType,
            List<PropertyNode> rules) {
        if (rules.size() != 4 || !isRoot(rules.get(0), "redeemerStrictlyDecodes")) {
            throw unsupported(OneShotMintProperty.TEMPLATE);
        }
        BytesLiteralNode authority = signerLiteral(rules.get(1));
        if (!(rules.get(2) instanceof ConsumesNode consumes)
                || !isInputs(consumes.inputs())
                || !(consumes.outputReference() instanceof TxOutRefLiteralNode anchor)) {
            throw unsupported(OneShotMintProperty.TEMPLATE);
        }
        ExactOwnPolicyAssetNode asset = exactAsset(rules.get(3));
        BytesLiteralNode token = tokenLiteral(asset);
        if (!"1".equals(integerLiteral(asset.quantity()))) {
            throw unsupported(OneShotMintProperty.TEMPLATE);
        }
        String canonical = PropertyIrCodec.canonicalJson(candidate);
        return new OneShotMintProperty(
                OneShotMintProperty.SCHEMA_VERSION, OneShotMintProperty.TEMPLATE,
                propertyId, validatorTitle, "minting", sourcePath, authority.hex(),
                anchor.transactionIdHex(), anchor.outputIndex(), token.hex(), "1",
                redeemerType, canonical, List.of("validMintingContext/v3-pinned"),
                List.of("strict redeemer decoding", "complete authority membership",
                        "configured anchor input is consumed",
                        "exact current-policy singleton token quantity"), true);
    }

    private static List<PropertyNode> flattenAnd(PropertyNode expression) {
        var result = new ArrayList<PropertyNode>();
        flattenAnd(expression, result);
        return result;
    }

    private static void flattenAnd(PropertyNode expression, List<PropertyNode> result) {
        if (expression instanceof BoolBinaryNode binary
                && binary.operator() == BoolOperator.AND) {
            flattenAnd(binary.left(), result);
            flattenAnd(binary.right(), result);
        } else {
            result.add(expression);
        }
    }

    private static BytesLiteralNode signerLiteral(PropertyNode rule) {
        if (!(rule instanceof ContainsNode contains)
                || !isSignatories(contains.collection())
                || !(contains.value() instanceof BytesLiteralNode literal)
                || literal.kind() != BytesLiteralKind.KEY_HASH) {
            throw unsupported("authority membership");
        }
        return literal;
    }

    private static ExactOwnPolicyAssetNode exactAsset(PropertyNode rule) {
        if (!(rule instanceof ExactOwnPolicyAssetNode asset)
                || !isMint(asset.mint()) || !isRoot(asset.policy(), "ownPolicy")) {
            throw unsupported("exact own-policy asset");
        }
        return asset;
    }

    private static BytesLiteralNode tokenLiteral(ExactOwnPolicyAssetNode asset) {
        if (!(asset.tokenName() instanceof BytesLiteralNode token)
                || token.kind() != BytesLiteralKind.TOKEN_NAME) {
            throw unsupported("token-name literal");
        }
        return token;
    }

    private static String integerLiteral(PropertyNode value) {
        if (!(value instanceof LiteralNode literal)
                || literal.resultType() != DslType.INTEGER) {
            throw unsupported("integer literal");
        }
        return literal.value();
    }

    private static boolean isSignatories(PropertyNode node) {
        return node instanceof FieldNode field && "signatories".equals(field.name())
                && field.target() instanceof FieldNode txInfo
                && "txInfo".equals(txInfo.name())
                && isRoot(txInfo.target(), "context");
    }

    private static boolean isInputs(PropertyNode node) {
        return isTxInfoField(node, "inputs");
    }

    private static boolean isMint(PropertyNode node) {
        return isTxInfoField(node, "mint");
    }

    private static boolean isTxInfoField(PropertyNode node, String name) {
        return node instanceof FieldNode field && name.equals(field.name())
                && field.target() instanceof FieldNode txInfo
                && "txInfo".equals(txInfo.name())
                && isRoot(txInfo.target(), "context");
    }

    private static boolean isRoot(PropertyNode node, String name) {
        return node instanceof RootNode root && name.equals(root.name());
    }

    private static String redeemerType(ContractSchema schema) {
        PirType type = schema.redeemer().type();
        while (type instanceof PirType.NamedTypeRef ref) {
            type = schema.namedDefinitions().get(ref.stableId());
            if (type == null) throw new IllegalArgumentException("Unknown redeemer type");
        }
        if (!(type instanceof PirType.RecordType record)) {
            throw new IllegalArgumentException("Minting DSL redeemer must be a named record");
        }
        return record.name();
    }

    private static IllegalArgumentException unsupported(String expected) {
        return new IllegalArgumentException(
                "Minting DSL property does not match reviewed " + expected + " semantics");
    }
}
