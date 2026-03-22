package com.bloxbean.cardano.julc.jrl.parser;

import com.bloxbean.cardano.julc.jrl.ast.*;
import com.bloxbean.cardano.julc.jrl.grammar.JRLBaseVisitor;
import com.bloxbean.cardano.julc.jrl.grammar.JRLParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.List;

/**
 * Converts an ANTLR JRL parse tree into a clean, immutable AST.
 * <p>
 * Each {@code visit*} method maps one grammar rule to the corresponding AST node.
 * Source locations are extracted from ANTLR tokens for error reporting.
 */
public class JrlAstBuilder extends JRLBaseVisitor<Object> {

    private final String fileName;

    public JrlAstBuilder(String fileName) {
        this.fileName = fileName;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private SourceRange rangeOf(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        return new SourceRange(fileName,
                start.getLine(), start.getCharPositionInLine() + 1,
                stop.getLine(), stop.getCharPositionInLine() + stop.getText().length());
    }

    private String unquote(String s) {
        // Remove surrounding double quotes from STRING tokens
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private byte[] parseHex(String hex) {
        // Strip 0x/0X prefix
        String digits = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        // Pad odd-length hex strings
        if (digits.length() % 2 != 0) {
            digits = "0" + digits;
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ── Top-level ───────────────────────────────────────────────

    @Override
    public ContractNode visitJrlFile(JRLParser.JrlFileContext ctx) {
        // Header
        var header = ctx.header();
        String name = unquote(header.STRING(0).getText());
        String version = unquote(header.STRING(1).getText());
        PurposeType purpose = header.purposeType() != null
                ? visitPurposeType(header.purposeType()) : null;

        // Params
        List<ParamNode> params = ctx.paramBlock() != null
                ? ctx.paramBlock().paramDecl().stream().map(this::visitParamDecl).toList()
                : List.of();

        // Datum
        DatumDeclNode datum = ctx.datumDecl() != null
                ? visitDatumDecl(ctx.datumDecl()) : null;

        // Records
        List<RecordDeclNode> records = ctx.recordDecl().stream()
                .map(this::visitRecordDecl).toList();

        // Redeemer (top-level, single-purpose)
        RedeemerDeclNode redeemer = ctx.redeemerDecl() != null
                ? visitRedeemerDecl(ctx.redeemerDecl()) : null;

        // Rules (top-level, single-purpose)
        List<RuleNode> rules = ctx.ruleDecl().stream()
                .map(this::visitRuleDecl).toList();

        // Default (top-level, single-purpose)
        DefaultAction defaultAction = ctx.defaultDecl() != null
                ? visitDefaultDecl(ctx.defaultDecl()) : null;

        // Purpose sections (multi-validator)
        List<PurposeSectionNode> purposeSections = ctx.purposeSection().stream()
                .map(this::visitPurposeSection).toList();

        return new ContractNode(name, version, purpose, params, datum, records,
                redeemer, rules, defaultAction, purposeSections, rangeOf(ctx));
    }

    // ── Declarations ────────────────────────────────────────────

    @Override
    public PurposeType visitPurposeType(JRLParser.PurposeTypeContext ctx) {
        String text = ctx.getText();
        return switch (text) {
            case "spending"   -> PurposeType.SPENDING;
            case "minting"    -> PurposeType.MINTING;
            case "withdraw"   -> PurposeType.WITHDRAW;
            case "certifying" -> PurposeType.CERTIFYING;
            case "voting"     -> PurposeType.VOTING;
            case "proposing"  -> PurposeType.PROPOSING;
            default -> throw new IllegalStateException("Unknown purpose: " + text);
        };
    }

    @Override
    public ParamNode visitParamDecl(JRLParser.ParamDeclContext ctx) {
        return new ParamNode(
                ctx.IDENT().getText(),
                visitTypeRef(ctx.typeRef()),
                rangeOf(ctx));
    }

    public TypeRef visitTypeRef(JRLParser.TypeRefContext ctx) {
        if (ctx instanceof JRLParser.ListTypeContext lt) {
            return new TypeRef.ListType(visitTypeRef(lt.typeRef()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.OptionalTypeContext ot) {
            return new TypeRef.OptionalType(visitTypeRef(ot.typeRef()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.SimpleTypeContext st) {
            return new TypeRef.SimpleType(st.IDENT().getText(), rangeOf(ctx));
        }
        throw new IllegalStateException("Unknown typeRef: " + ctx.getText());
    }

    @Override
    public DatumDeclNode visitDatumDecl(JRLParser.DatumDeclContext ctx) {
        List<FieldNode> fields = ctx.fieldDecl().stream()
                .map(this::visitFieldDecl).toList();
        return new DatumDeclNode(ctx.IDENT().getText(), fields, rangeOf(ctx));
    }

    @Override
    public RecordDeclNode visitRecordDecl(JRLParser.RecordDeclContext ctx) {
        List<FieldNode> fields = ctx.fieldDecl().stream()
                .map(this::visitFieldDecl).toList();
        return new RecordDeclNode(ctx.IDENT().getText(), fields, rangeOf(ctx));
    }

    @Override
    public FieldNode visitFieldDecl(JRLParser.FieldDeclContext ctx) {
        return new FieldNode(
                ctx.IDENT().getText(),
                visitTypeRef(ctx.typeRef()),
                rangeOf(ctx));
    }

    @Override
    public RedeemerDeclNode visitRedeemerDecl(JRLParser.RedeemerDeclContext ctx) {
        String name = ctx.IDENT().getText();
        var body = ctx.redeemerBody();
        if (body instanceof JRLParser.VariantRedeemerContext vr) {
            List<VariantNode> variants = vr.variantDecl().stream()
                    .map(this::visitVariantDecl).toList();
            return new RedeemerDeclNode(name, variants, List.of(), rangeOf(ctx));
        } else if (body instanceof JRLParser.RecordRedeemerContext rr) {
            List<FieldNode> fields = rr.fieldDecl().stream()
                    .map(this::visitFieldDecl).toList();
            return new RedeemerDeclNode(name, List.of(), fields, rangeOf(ctx));
        }
        throw new IllegalStateException("Unknown redeemer body: " + body.getText());
    }

    @Override
    public VariantNode visitVariantDecl(JRLParser.VariantDeclContext ctx) {
        List<FieldNode> fields = ctx.fieldDecl() != null
                ? ctx.fieldDecl().stream().map(this::visitFieldDecl).toList()
                : List.of();
        return new VariantNode(ctx.IDENT().getText(), fields, rangeOf(ctx));
    }

    // ── Purpose sections ────────────────────────────────────────

    @Override
    public PurposeSectionNode visitPurposeSection(JRLParser.PurposeSectionContext ctx) {
        PurposeType purpose = visitPurposeType(ctx.purposeType());
        RedeemerDeclNode redeemer = ctx.redeemerDecl() != null
                ? visitRedeemerDecl(ctx.redeemerDecl()) : null;
        List<RuleNode> rules = ctx.ruleDecl().stream()
                .map(this::visitRuleDecl).toList();
        DefaultAction defaultAction = visitDefaultDecl(ctx.defaultDecl());
        return new PurposeSectionNode(purpose, redeemer, rules, defaultAction, rangeOf(ctx));
    }

    // ── Rules ───────────────────────────────────────────────────

    @Override
    public RuleNode visitRuleDecl(JRLParser.RuleDeclContext ctx) {
        String name = unquote(ctx.STRING(0).getText());
        List<FactPattern> patterns = ctx.factPattern().stream()
                .map(this::visitFactPatternDispatch).toList();
        DefaultAction action = ctx.action().ALLOW() != null
                ? DefaultAction.ALLOW : DefaultAction.DENY;
        // Optional trace message (STRING at index 1 if TRACE keyword present)
        String trace = null;
        if (ctx.TRACE() != null && ctx.STRING().size() > 1) {
            trace = unquote(ctx.STRING(1).getText());
        }
        return new RuleNode(name, patterns, action, trace, rangeOf(ctx));
    }

    @Override
    public DefaultAction visitDefaultDecl(JRLParser.DefaultDeclContext ctx) {
        return ctx.action().ALLOW() != null ? DefaultAction.ALLOW : DefaultAction.DENY;
    }

    // ── Fact patterns ───────────────────────────────────────────

    private FactPattern visitFactPatternDispatch(JRLParser.FactPatternContext ctx) {
        if (ctx instanceof JRLParser.RedeemerPatternContext rp) {
            return new FactPattern.RedeemerPattern(
                    visitVariantMatch(rp.variantMatch()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.TransactionPatternContext tp) {
            var field = switch (tp.txField().getText()) {
                case "signedBy"    -> FactPattern.TxField.SIGNED_BY;
                case "validAfter"  -> FactPattern.TxField.VALID_AFTER;
                case "validBefore" -> FactPattern.TxField.VALID_BEFORE;
                default -> throw new IllegalStateException("Unknown txField: " + tp.txField().getText());
            };
            return new FactPattern.TransactionPattern(
                    field, visitExprDispatch(tp.expr()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.DatumPatternContext dp) {
            return new FactPattern.DatumPattern(
                    visitVariantMatch(dp.variantMatch()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.ConditionPatternContext cp) {
            return new FactPattern.ConditionPattern(
                    visitExprDispatch(cp.expr()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.OutputPatternContext op) {
            return buildOutputPattern(op);
        }
        throw new IllegalStateException("Unknown fact pattern: " + ctx.getText());
    }

    private FactPattern.OutputPattern buildOutputPattern(JRLParser.OutputPatternContext ctx) {
        Expression to = null;
        ValueConstraint value = null;
        DatumConstraint datum = null;

        for (var field : ctx.outputField()) {
            if (field instanceof JRLParser.OutputToContext ot) {
                to = visitExprDispatch(ot.expr());
            } else if (field instanceof JRLParser.OutputValueContext ov) {
                value = visitValueExpr(ov.valueExpr());
            } else if (field instanceof JRLParser.OutputDatumContext od) {
                datum = buildDatumConstraint(od.datumExpr());
            }
        }
        return new FactPattern.OutputPattern(to, value, datum, rangeOf(ctx));
    }

    private ValueConstraint visitValueExpr(JRLParser.ValueExprContext ctx) {
        if (ctx instanceof JRLParser.MinAdaValueContext mv) {
            return new ValueConstraint.MinADA(visitExprDispatch(mv.expr()));
        } else if (ctx instanceof JRLParser.ContainsValueContext cv) {
            return new ValueConstraint.Contains(
                    visitExprDispatch(cv.expr(0)),
                    visitExprDispatch(cv.expr(1)),
                    visitExprDispatch(cv.expr(2)));
        }
        throw new IllegalStateException("Unknown valueExpr: " + ctx.getText());
    }

    private DatumConstraint buildDatumConstraint(JRLParser.DatumExprContext ctx) {
        String typeName = ctx.IDENT().getText();
        List<DatumFieldExpr> fields = ctx.datumFieldExpr().stream()
                .map(f -> new DatumFieldExpr(f.IDENT().getText(), visitExprDispatch(f.expr())))
                .toList();
        return new DatumConstraint(typeName, fields);
    }

    // ── Pattern matching ────────────────────────────────────────

    @Override
    public VariantMatch visitVariantMatch(JRLParser.VariantMatchContext ctx) {
        String typeName = ctx.IDENT().getText();
        List<FieldMatch> fields = ctx.fieldMatch() != null
                ? ctx.fieldMatch().stream().map(this::visitFieldMatch).toList()
                : List.of();
        return new VariantMatch(typeName, fields, rangeOf(ctx));
    }

    @Override
    public FieldMatch visitFieldMatch(JRLParser.FieldMatchContext ctx) {
        return new FieldMatch(
                ctx.IDENT().getText(),
                visitMatchValue(ctx.matchValue()),
                rangeOf(ctx));
    }

    private MatchValue visitMatchValue(JRLParser.MatchValueContext ctx) {
        if (ctx instanceof JRLParser.BindingValueContext bv) {
            String varName = bv.VAR_BINDING().getText().substring(1); // strip '$'
            return new MatchValue.Binding(varName, rangeOf(ctx));
        } else if (ctx instanceof JRLParser.NestedMatchValueContext nm) {
            String typeName = nm.IDENT().getText();
            List<FieldMatch> fields = nm.fieldMatch().stream()
                    .map(this::visitFieldMatch).toList();
            return new MatchValue.NestedMatch(typeName, fields, rangeOf(ctx));
        } else if (ctx instanceof JRLParser.LiteralValueContext lv) {
            return new MatchValue.Literal(visitExprDispatch(lv.expr()), rangeOf(ctx));
        }
        throw new IllegalStateException("Unknown matchValue: " + ctx.getText());
    }

    // ── Expressions ─────────────────────────────────────────────

    private Expression visitExprDispatch(JRLParser.ExprContext ctx) {
        if (ctx instanceof JRLParser.OrExprContext oe) {
            return new Expression.BinaryExpr(
                    visitExprDispatch(oe.expr(0)), Expression.BinaryOp.OR,
                    visitExprDispatch(oe.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.AndExprContext ae) {
            return new Expression.BinaryExpr(
                    visitExprDispatch(ae.expr(0)), Expression.BinaryOp.AND,
                    visitExprDispatch(ae.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.EqualityExprContext ee) {
            var op = ee.EQ() != null ? Expression.BinaryOp.EQ : Expression.BinaryOp.NEQ;
            return new Expression.BinaryExpr(
                    visitExprDispatch(ee.expr(0)), op,
                    visitExprDispatch(ee.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.ComparisonExprContext ce) {
            var op = ce.GT() != null ? Expression.BinaryOp.GT
                    : ce.GTE() != null ? Expression.BinaryOp.GTE
                    : ce.LT() != null ? Expression.BinaryOp.LT
                    : Expression.BinaryOp.LTE;
            return new Expression.BinaryExpr(
                    visitExprDispatch(ce.expr(0)), op,
                    visitExprDispatch(ce.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.AdditiveExprContext ad) {
            var op = ad.PLUS() != null ? Expression.BinaryOp.ADD : Expression.BinaryOp.SUB;
            return new Expression.BinaryExpr(
                    visitExprDispatch(ad.expr(0)), op,
                    visitExprDispatch(ad.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.MultiplicativeExprContext mc) {
            return new Expression.BinaryExpr(
                    visitExprDispatch(mc.expr(0)), Expression.BinaryOp.MUL,
                    visitExprDispatch(mc.expr(1)), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.NotExprContext ne) {
            return new Expression.UnaryExpr(
                    Expression.UnaryOp.NOT, visitExprDispatch(ne.expr()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.FieldAccessExprContext fa) {
            return new Expression.FieldAccessExpr(
                    visitExprDispatch(fa.expr()), fa.IDENT().getText(), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.FunctionCallExprContext fc) {
            List<Expression> args = fc.exprList() != null
                    ? fc.exprList().expr().stream().map(this::visitExprDispatch).toList()
                    : List.of();
            return new Expression.FunctionCallExpr(fc.IDENT().getText(), args, rangeOf(ctx));
        } else if (ctx instanceof JRLParser.ParenExprContext pe) {
            return visitExprDispatch(pe.expr());
        } else if (ctx instanceof JRLParser.VarRefExprContext vr) {
            String varName = vr.VAR_BINDING().getText().substring(1); // strip '$'
            return new Expression.VarRefExpr(varName, rangeOf(ctx));
        } else if (ctx instanceof JRLParser.OwnAddressExprContext) {
            return new Expression.OwnAddressExpr(rangeOf(ctx));
        } else if (ctx instanceof JRLParser.OwnPolicyIdExprContext) {
            return new Expression.OwnPolicyIdExpr(rangeOf(ctx));
        } else if (ctx instanceof JRLParser.IdentRefExprContext ir) {
            return new Expression.IdentRefExpr(ir.IDENT().getText(), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.IntLiteralExprContext il) {
            return new Expression.IntLiteralExpr(Long.parseLong(il.INTEGER().getText()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.StringLiteralExprContext sl) {
            return new Expression.StringLiteralExpr(unquote(sl.STRING().getText()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.HexLiteralExprContext hl) {
            return new Expression.HexLiteralExpr(parseHex(hl.HEX_LITERAL().getText()), rangeOf(ctx));
        } else if (ctx instanceof JRLParser.TrueLiteralExprContext) {
            return new Expression.BoolLiteralExpr(true, rangeOf(ctx));
        } else if (ctx instanceof JRLParser.FalseLiteralExprContext) {
            return new Expression.BoolLiteralExpr(false, rangeOf(ctx));
        }
        throw new IllegalStateException("Unknown expression: " + ctx.getClass().getSimpleName());
    }
}
