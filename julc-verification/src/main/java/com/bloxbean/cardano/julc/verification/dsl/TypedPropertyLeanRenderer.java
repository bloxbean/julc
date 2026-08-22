package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ProjectedContractTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Lean renderer for parent-validated schema-4/5 structural nodes. */
public final class TypedPropertyLeanRenderer {
    private TypedPropertyLeanRenderer() { }

    public static String renderExpression(
            PropertyNode expression, ProjectedContractTypes projection) {
        var definitions = projection.definitions().stream().collect(
                Collectors.toUnmodifiableMap(
                        ProjectedContractTypes.NominalDefinition::stableId,
                        definition -> definition));
        return render(expression, definitions, Map.of());
    }

    private static String render(
            PropertyNode node,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            Map<String, String> variantFields) {
        if (node instanceof TypedRootNode root) {
            return switch (root.name()) {
                case "typedDatum" -> "typedDatum ctx";
                case "typedRedeemer" -> "typedRedeemer ctx";
                default -> throw new IllegalArgumentException(
                        "No Lean mapping for typed root " + root.name());
            };
        }
        if (node instanceof TypedVariableNode variable) {
            return variantFields.getOrDefault(variableKey(variable.variable()),
                    variable.variable());
        }
        if (node instanceof LedgerRootNode root) {
            return switch (root.name()) {
                case "ledgerContext" -> "ctx";
                case "currentCertificate" -> "certificateOf ctx";
                default -> throw new IllegalArgumentException(
                        "No Lean mapping for ledger root " + root.name());
            };
        }
        if (node instanceof TypedFieldNode field) {
            return render(field.target(), definitions, variantFields)
                    + "." + leanFieldName(field.name());
        }
        if (node instanceof VariantFieldNode field) {
            String value = variantFields.get(fieldKey(
                    field.target(), field.constructor(), field.name()));
            if (value == null) {
                throw new IllegalArgumentException(
                        "Variant field has no guarded Lean binding");
            }
            return value;
        }
        if (node instanceof OptionExistsNode exists) {
            return parenthesize("match " + parenthesize(render(
                    exists.optional(), definitions, variantFields))
                    + " with | some " + exists.variable() + " => "
                    + render(exists.predicate(), definitions, variantFields)
                    + " | none => false");
        }
        if (node instanceof VariantIsNode variant) {
            return parenthesize("match " + parenthesize(render(
                    variant.value(), definitions, variantFields))
                    + " with | ." + leanTypeName(variant.constructor())
                    + " .. => true | _ => false");
        }
        if (node instanceof VariantWhenNode variant) {
            var definition = definitions.get(variant.sumType().stableId());
            if (definition == null) {
                throw new IllegalArgumentException(
                        "Unknown projected sum " + variant.sumType().stableId());
            }
            var constructor = definition.constructors().stream()
                    .filter(candidate -> candidate.name().equals(variant.constructor()))
                    .findFirst().orElseThrow();
            var nested = new HashMap<>(variantFields);
            var pattern = new StringBuilder();
            for (int index = 0; index < constructor.fields().size(); index++) {
                var field = constructor.fields().get(index);
                String binding = variant.variable() + "_" + index;
                pattern.append(' ').append(binding);
                nested.put(fieldKey(new TypedVariableNode(
                                variant.variable(), variant.sumType()),
                        variant.constructor(), field.name()), binding);
            }
            return parenthesize("match " + parenthesize(render(
                    variant.value(), definitions, variantFields))
                    + " with | ." + leanTypeName(variant.constructor()) + pattern
                    + " => " + render(variant.predicate(), definitions, nested)
                    + " | _ => false");
        }
        if (node instanceof LedgerFieldNode field) {
            String target = parenthesize(render(
                    field.target(), definitions, variantFields));
            return switch (field.ownerType().ledgerType()) {
                case SCRIPT_CONTEXT -> target + ".scriptContextTxInfo";
                case TX_INFO -> switch (field.name()) {
                    case "inputs" -> "(⟨" + target
                            + ".txInfoInputs⟩ : JulcList TxInInfo)";
                    case "referenceInputs" -> "(⟨" + target
                            + ".txInfoReferenceInputs⟩ : JulcList TxInInfo)";
                    case "outputs" -> "(⟨" + target
                            + ".txInfoOutputs⟩ : JulcList CardanoLedgerApi.V2.TxOut)";
                    case "fee" -> target + ".txInfoFee";
                    case "mint" -> target + ".txInfoMint";
                    case "certificates" -> "(⟨" + target
                            + ".txInfoTxCerts⟩ : JulcList TxCert)";
                    case "datums" -> "(⟨" + target
                            + ".txInfoData⟩ : JulcMap CardanoLedgerApi.V2.DatumHash Data)";
                    case "redeemers" -> "(⟨" + target
                            + ".txInfoRedeemers⟩ : JulcMap ScriptPurpose Data)";
                    case "votes" -> "julcVoterMap " + target + ".txInfoVotes";
                    case "proposals" -> "(⟨" + target
                            + ".txInfoProposalProcedures⟩ : JulcList ProposalProcedure)";
                    case "id" -> target + ".txInfoId";
                    default -> unknownLedgerField(field);
                };
                case TX_IN_INFO -> switch (field.name()) {
                    case "outRef" -> target + ".txInInfoOutRef";
                    case "resolved" -> target + ".txInInfoResolved";
                    default -> unknownLedgerField(field);
                };
                case TX_OUT_REF -> switch (field.name()) {
                    case "id" -> target + ".txOutRefId";
                    case "index" -> target + ".txOutRefIdx";
                    default -> unknownLedgerField(field);
                };
                case TX_OUT -> switch (field.name()) {
                    case "address" -> target + ".txOutAddress";
                    case "value" -> target + ".txOutValue";
                    case "datum" -> target + ".txOutDatum";
                    case "referenceScript" -> target + ".txOutReferenceScript";
                    default -> unknownLedgerField(field);
                };
                case ADDRESS -> switch (field.name()) {
                    case "paymentCredential" -> target + ".addressCredential";
                    case "stakingCredential" -> target + ".addressStakingCredential";
                    default -> unknownLedgerField(field);
                };
                case GOVERNANCE_ACTION_ID -> switch (field.name()) {
                    case "txId" -> target + ".gaidTxId";
                    case "index" -> target + ".gaidGovActionIx";
                    default -> unknownLedgerField(field);
                };
                case PROTOCOL_VERSION -> switch (field.name()) {
                    case "major" -> target + ".pvMajor";
                    case "minor" -> target + ".pvMinor";
                    default -> unknownLedgerField(field);
                };
                case PROPOSAL_PROCEDURE -> switch (field.name()) {
                    case "deposit" -> target + ".ppDeposit";
                    case "returnAddress" -> target + ".ppReturnAddr";
                    default -> unknownLedgerField(field);
                };
                default -> unknownLedgerField(field);
            };
        }
        if (node instanceof LedgerVariantFieldNode field) {
            String value = variantFields.get(fieldKey(
                    field.target(), field.constructor(), field.name()));
            if (value == null) {
                throw new IllegalArgumentException(
                        "Ledger variant field has no guarded Lean binding");
            }
            return value;
        }
        if (node instanceof LedgerVariantIsNode variant) {
            return parenthesize("match " + parenthesize(render(
                    variant.value(), definitions, variantFields))
                    + " with | ." + variant.constructor()
                    + " .. => true | _ => false");
        }
        if (node instanceof LedgerVariantWhenNode variant) {
            var constructor = LedgerTypeAuthority.constructor(
                    variant.sumType(), variant.constructor());
            var nested = new HashMap<>(variantFields);
            var pattern = new StringBuilder();
            int index = 0;
            for (var field : constructor.fields().entrySet()) {
                String binding = variant.variable() + "_" + index++;
                pattern.append(' ').append(binding);
                nested.put(fieldKey(new TypedVariableNode(
                                variant.variable(), variant.sumType()),
                        variant.constructor(), field.getKey()), binding);
            }
            return parenthesize("match " + parenthesize(render(
                    variant.value(), definitions, variantFields))
                    + " with | ." + variant.constructor() + pattern
                    + " => " + render(variant.predicate(), definitions, nested)
                    + " | _ => false");
        }
        if (node instanceof ValueEntriesNode entries) {
            return "(⟨" + render(entries.value(), definitions, variantFields)
                    + "⟩ : JulcList (Data × Data))";
        }
        if (node instanceof ValueEntryWhenNode entry) {
            var nested = new HashMap<>(variantFields);
            String rawValue = entry.entryKind() == ValueEntryWhenNode.ValueEntryKind.POLICY
                    ? entry.valueVariable() + "_raw" : entry.valueVariable();
            if (entry.entryKind() == ValueEntryWhenNode.ValueEntryKind.POLICY) {
                nested.put(variableKey(entry.valueVariable()),
                        "(⟨" + rawValue + "⟩ : JulcList (Data × Data))");
            }
            String pattern = entry.entryKind() == ValueEntryWhenNode.ValueEntryKind.POLICY
                    ? "(Data.B " + entry.keyVariable() + ", Data.Map "
                            + rawValue + ")"
                    : "(Data.B " + entry.keyVariable() + ", Data.I "
                            + entry.valueVariable() + ")";
            return parenthesize("match " + parenthesize(render(
                    entry.entry(), definitions, variantFields))
                    + " with | " + pattern + " => "
                    + render(entry.predicate(), definitions, nested)
                    + " | _ => false");
        }
        if (node instanceof ValueQuantityNode quantity) {
            String value = parenthesize(render(quantity.value(), definitions, variantFields));
            String policy = parenthesize(render(quantity.policy(), definitions, variantFields));
            String token = parenthesize(render(quantity.token(), definitions, variantFields));
            return switch (quantity.quantityKind()) {
                case FIRST_MATCH -> "valueOf " + policy + " " + token + " " + value;
                case STRICT_SUMMED -> "julcValueQuantitySumStrict " + policy + " "
                        + token + " " + value;
            };
        }
        if (node instanceof ValueRelationNode relation) {
            String left = parenthesize(render(relation.left(), definitions, variantFields));
            String right = parenthesize(render(relation.right(), definitions, variantFields));
            return switch (relation.relation()) {
                case STRUCTURAL_EQ -> left + " == " + right;
                case EXTENSIONAL_EQ -> "julcValueExtensionalEq " + left + " " + right;
                case LE -> "julcValuePointwiseLe " + left + " " + right;
                case LT -> "julcValuePointwiseLt " + left + " " + right;
                case GE -> "julcValuePointwiseLe " + right + " " + left;
                case GT -> "julcValuePointwiseLt " + right + " " + left;
            };
        }
        if (node instanceof ValueArithmeticNode arithmetic) {
            var arguments = arithmetic.arguments().stream()
                    .map(argument -> parenthesize(render(
                            argument, definitions, variantFields))).toList();
            return switch (arithmetic.arithmetic()) {
                case VALIDATE -> "julcValueValidateStrict " + arguments.getFirst();
                case SINGLETON -> "julcValueSingletonStrict " + arguments.get(0) + " "
                        + arguments.get(1) + " " + arguments.get(2);
                case ADD -> "julcValueAddStrict " + arguments.get(0) + " "
                        + arguments.get(1);
                case NEGATE -> "julcValueNegateStrict " + arguments.getFirst();
                case SCALE -> "julcValueScaleStrict " + arguments.get(1) + " "
                        + arguments.get(0);
            };
        }
        if (node instanceof LedgerHelperNode helper) {
            var arguments = helper.arguments().stream()
                    .map(argument -> parenthesize(render(
                            argument, definitions, variantFields)))
                    .toList();
            return switch (helper.helper()) {
                case CURRENT_OUTPUT_REF -> parenthesize("match " + arguments.getFirst()
                        + ".scriptContextScriptInfo with | .SpendingScript ref _ => ref"
                        // Promotion admits this helper only for spending, and every
                        // generated obligation carries selectedPurpose as a premise.
                        // This branch only makes the Lean expression total.
                        + " | _ => ⟨\"\", 0⟩");
                case CURRENT_SCRIPT_PURPOSE -> arguments.getFirst()
                        + ".scriptContextScriptInfo.toScriptPurpose";
                case FIND_OWN_INPUT -> "findOwnInput " + arguments.getFirst();
                case RESOLVE_INPUT -> "resolveInput " + arguments.get(1) + " "
                        + arguments.getFirst() + ".items";
                case FILTER_PAYMENT_KEY_INPUTS -> "⟨findPubKeyInputs "
                        + arguments.get(1) + " " + arguments.getFirst() + ".items⟩";
                case FILTER_SCRIPT_INPUTS -> "⟨findScriptInputs "
                        + arguments.get(1) + " " + arguments.getFirst() + ".items⟩";
                case CONTINUING_OUTPUTS -> "julcContinuingOutputs "
                        + arguments.getFirst();
                case LOVELACE_OF -> "lovelaceOf " + arguments.getFirst();
                case VALUE_SPENT -> "valueSpent " + arguments.getFirst();
                case VALUE_PRODUCED -> "valueProduced " + arguments.getFirst();
                case AGGREGATE_INPUT_VALUES -> "julcAggregateInputValues "
                        + arguments.getFirst() + ".items";
                case AGGREGATE_OUTPUT_VALUES -> "julcAggregateOutputValues "
                        + arguments.getFirst() + ".items";
                case FILTER_ADDRESS_OUTPUTS -> "⟨List.filter (fun output => "
                        + "output.txOutAddress == " + arguments.get(1) + ") "
                        + arguments.getFirst() + ".items⟩";
                case FILTER_PAYMENT_CREDENTIAL_OUTPUTS ->
                        "⟨List.filter (fun output => output.txOutAddress.addressCredential == "
                                + arguments.get(1) + ") "
                                + arguments.getFirst() + ".items⟩";
                case IS_BALANCED -> "isBalanced " + arguments.getFirst();
                case DECODE_GOVERNANCE_ACTION ->
                        "(IsData.fromData " + arguments.getFirst()
                                + ".ppGovernanceAction : Option GovernanceAction)";
                case IS_KNOWN_VOTER -> "julcIsKnownVoter " + arguments.get(0)
                        + " " + arguments.get(1);
                case IS_KNOWN_PROPOSAL -> "isKnownProposal " + arguments.get(0)
                        + " " + arguments.get(1) + " " + arguments.get(2) + ".items";
            };
        }
        if (node instanceof LedgerByteAliasNode alias) {
            return render(alias.bytes(), definitions, variantFields);
        }
        if (node instanceof AuthorityKeyHashNode authority) {
            return render(authority.bytes(), definitions, variantFields);
        }
        if (node instanceof AuthorityListNode authorities) {
            return authorities.authorities().stream()
                    .map(authority -> render(authority, definitions, variantFields))
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        if (node instanceof AuthorityListFromBytesNode authorities) {
            return listItems(authorities.bytesList(), definitions, variantFields);
        }
        if (node instanceof AuthorizationNode authorization) {
            String authorities = parenthesize(render(
                    authorization.authorities(), definitions, variantFields));
            String signers = "ctx.scriptContextTxInfo.txInfoSignatories";
            return switch (authorization.relation()) {
                case ANY_SIGNED -> "julcAnySigned " + authorities + " " + signers;
                case ALL_SIGNED -> "julcAllSigned " + authorities + " " + signers;
                case NONE_SIGNED -> "julcNoneSigned " + authorities + " " + signers;
                case AT_LEAST_SIGNED -> "julcAtLeastSigned "
                        + authorization.threshold() + " " + authorities + " " + signers;
                case EXACTLY_SIGNED -> "julcExactlySigned "
                        + authorization.threshold() + " " + authorities + " " + signers;
                case NO_UNEXPECTED_SIGNERS -> "julcNoUnexpectedSigners "
                        + authorities + " " + signers;
                case EXACT_SIGNER_SET -> "julcExactSignerSet "
                        + authorities + " " + signers;
            };
        }
        if (node instanceof NoSignersNode) {
            return "List.isEmpty ctx.scriptContextTxInfo.txInfoSignatories";
        }
        if (node instanceof BoolLiteralNode literal) {
            return Boolean.toString(literal.value());
        }
        if (node instanceof BoolNotNode not) {
            return "!" + parenthesize(render(not.value(), definitions, variantFields));
        }
        if (node instanceof IntegerArithmeticNode arithmetic) {
            String left = parenthesize(render(
                    arithmetic.left(), definitions, variantFields));
            return switch (arithmetic.operator()) {
                case NEGATE -> "-" + left;
                case ADD -> parenthesize(left + " + " + parenthesize(render(
                        arithmetic.right(), definitions, variantFields)));
                case SUBTRACT -> parenthesize(left + " - " + parenthesize(render(
                        arithmetic.right(), definitions, variantFields)));
                case SCALE -> parenthesize(arithmetic.constant() + " * " + left);
            };
        }
        if (node instanceof TypedEqualityNode equality) {
            return equality(equality.left(), equality.right(), equality.negated(),
                    definitions, variantFields);
        }
        if (node instanceof OptionStateNode option) {
            String present = option.state() == OptionState.PRESENT ? "true" : "false";
            String empty = option.state() == OptionState.EMPTY ? "true" : "false";
            return parenthesize("match " + parenthesize(render(
                    option.optional(), definitions, variantFields))
                    + " with | some _ => " + present + " | none => " + empty);
        }
        if (node instanceof ListStateNode list) {
            String items = listItems(list.list(), definitions, variantFields);
            return list.state() == ListState.EMPTY
                    ? "List.isEmpty " + parenthesize(items)
                    : "!" + parenthesize("List.isEmpty " + parenthesize(items));
        }
        if (node instanceof ListContainsNode list) {
            String items = listItems(list.list(), definitions, variantFields);
            String value = render(list.value(), definitions, variantFields);
            return "julcListContains " + parenthesize(items) + " " + parenthesize(value);
        }
        if (node instanceof ListQuantifierNode list) {
            String items = listItems(list.list(), definitions, variantFields);
            String predicate = "(fun " + list.variable() + " => "
                    + render(list.predicate(), definitions, variantFields) + ")";
            return switch (list.quantifier()) {
                case EXISTS -> "List.any " + parenthesize(items) + " " + predicate;
                case ALL -> "List.all " + parenthesize(items) + " " + predicate;
                case NONE -> "!" + parenthesize(
                        "List.any " + parenthesize(items) + " " + predicate);
            };
        }
        if (node instanceof ListCountNode list) {
            String items = listItems(list.list(), definitions, variantFields);
            return "julcListCount (fun " + list.variable() + " => "
                    + render(list.predicate(), definitions, variantFields) + ") "
                    + parenthesize(items);
        }
        if (node instanceof ListAtNode list) {
            return "julcListAt " + parenthesize(listItems(
                    list.list(), definitions, variantFields)) + " "
                    + parenthesize(render(list.index(), definitions, variantFields));
        }
        if (node instanceof StructuralEqualsNode equality) {
            return equality(equality.left(), equality.right(), equality.negated(),
                    definitions, variantFields);
        }
        if (node instanceof MapQuantifierNode map) {
            String entries = mapEntries(map.map(), definitions, variantFields);
            String predicate = mapPredicate(map.keyVariable(), map.valueVariable(),
                    map.predicate(), definitions, variantFields);
            return switch (map.quantifier()) {
                case EXISTS -> "List.any " + parenthesize(entries) + " " + predicate;
                case ALL -> "List.all " + parenthesize(entries) + " " + predicate;
                case NONE -> "!" + parenthesize(
                        "List.any " + parenthesize(entries) + " " + predicate);
            };
        }
        if (node instanceof MapCountEntryNode map) {
            return "julcListCount " + mapPredicate(map.keyVariable(), map.valueVariable(),
                    map.predicate(), definitions, variantFields) + " "
                    + parenthesize(mapEntries(map.map(), definitions, variantFields));
        }
        if (node instanceof MapContainsKeyNode map) {
            return "julcMapContainsKey " + parenthesize(mapEntries(
                    map.map(), definitions, variantFields)) + " "
                    + parenthesize(render(map.key(), definitions, variantFields));
        }
        if (node instanceof MapCountKeyNode map) {
            return "julcMapCountKey " + parenthesize(mapEntries(
                    map.map(), definitions, variantFields)) + " "
                    + parenthesize(render(map.key(), definitions, variantFields));
        }
        if (node instanceof MapLookupFirstNode map) {
            return "julcMapLookupFirst " + parenthesize(mapEntries(
                    map.map(), definitions, variantFields)) + " "
                    + parenthesize(render(map.key(), definitions, variantFields));
        }
        if (node instanceof MapLookupAllNode map) {
            return "⟨julcMapLookupAll " + parenthesize(mapEntries(
                    map.map(), definitions, variantFields)) + " "
                    + parenthesize(render(map.key(), definitions, variantFields)) + "⟩";
        }
        if (node instanceof BoolBinaryNode binary) {
            String left = render(binary.left(), definitions, variantFields);
            String right = render(binary.right(), definitions, variantFields);
            return switch (binary.operator()) {
                case AND -> parenthesize(left + " && " + right);
                case OR -> parenthesize(left + " || " + right);
                case IMPLIES -> parenthesize("(!" + parenthesize(left) + ") || "
                        + parenthesize(right));
            };
        }
        if (node instanceof CompareNode comparison) {
            String operator = switch (comparison.operator()) {
                case EQ -> "==";
                case NE -> "!=";
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
            };
            return parenthesize(render(comparison.left(), definitions, variantFields)
                    + " " + operator + " "
                    + render(comparison.right(), definitions, variantFields));
        }
        if (node instanceof ContainsNode contains) {
            return "List.elem " + parenthesize(render(
                    contains.value(), definitions, variantFields)) + " "
                    + parenthesize(render(
                    contains.collection(), definitions, variantFields));
        }
        return PropertyLeanRenderer.renderExpression(node);
    }

    private static String equality(
            PropertyNode left,
            PropertyNode right,
            boolean negated,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            Map<String, String> variantFields) {
        String comparison = "julcStructuralEq "
                + parenthesize(render(left, definitions, variantFields)) + " "
                + parenthesize(render(right, definitions, variantFields));
        return negated ? "!" + parenthesize(comparison) : comparison;
    }

    private static String listItems(
            PropertyNode list,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            Map<String, String> variantFields) {
        return parenthesize(render(list, definitions, variantFields)) + ".items";
    }

    private static String mapEntries(
            PropertyNode map,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            Map<String, String> variantFields) {
        return parenthesize(render(map, definitions, variantFields)) + ".entries";
    }

    private static String mapPredicate(
            String key,
            String value,
            PropertyNode predicate,
            Map<String, ProjectedContractTypes.NominalDefinition> definitions,
            Map<String, String> variantFields) {
        return "(fun entry => let " + key + " := entry.1; let " + value
                + " := entry.2; " + render(predicate, definitions, variantFields) + ")";
    }

    private static String fieldKey(
            PropertyNode target, String constructor, String field) {
        if (!(target instanceof TypedVariableNode variable)) {
            throw new IllegalArgumentException(
                    "Variant field target is not its constructor binder");
        }
        return variable.variable() + "#" + constructor + "#" + field;
    }

    private static String variableKey(String variable) {
        return "$variable:" + variable;
    }

    private static String parenthesize(String value) {
        return "(" + value + ")";
    }

    private static String unknownLedgerField(LedgerFieldNode field) {
        throw new IllegalArgumentException("No Lean mapping for ledger field "
                + field.ownerType().ledgerType() + "." + field.name());
    }

    private static String leanFieldName(String raw) {
        String typeName = leanTypeName(raw);
        String result = Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
        if (LEAN_RESERVED.contains(result.toLowerCase(java.util.Locale.ROOT))) {
            result += "Field";
        }
        return result;
    }

    private static String leanTypeName(String raw) {
        var result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (!Character.isLetterOrDigit(ch)) {
                capitalize = true;
                continue;
            }
            result.append(capitalize ? Character.toUpperCase(ch) : ch);
            capitalize = false;
        }
        if (result.isEmpty()) result.append("Generated");
        if (Character.isDigit(result.charAt(0))) result.insert(0, 'T');
        return result.toString();
    }

    private static final java.util.Set<String> LEAN_RESERVED = java.util.Set.of(
            "abbrev", "axiom", "class", "def", "deriving", "else", "end",
            "example", "export", "if", "import", "in", "inductive", "instance",
            "let", "match", "namespace", "open", "opaque", "partial", "private",
            "protected", "structure", "theorem", "where", "with");
}
