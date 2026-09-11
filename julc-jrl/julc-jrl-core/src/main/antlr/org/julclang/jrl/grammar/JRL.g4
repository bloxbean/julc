/**
 * JRL (JuLC Rule Language) Grammar
 *
 * A Drools-inspired rule language for writing Cardano smart contract validators.
 * JRL is intentionally non-Turing-complete: no loops, no recursion, no assignment.
 *
 * Compilation: JRL source -> ANTLR4 parse -> AST -> type check -> Java transpile -> JulcCompiler -> UPLC
 */
grammar JRL;

// ============================================================
// Top-level structure
// ============================================================

jrlFile
    : header paramBlock? datumDecl? recordDecl* redeemerDecl?
      (ruleDecl | purposeSection)* defaultDecl? EOF
    ;

// ============================================================
// Header
// ============================================================

header
    : CONTRACT STRING VERSION STRING (PURPOSE purposeType)?
    ;

purposeType
    : SPENDING | MINTING | WITHDRAW | CERTIFYING | VOTING | PROPOSING
    ;

// ============================================================
// Declarations
// ============================================================

paramBlock
    : PARAMS COLON paramDecl+
    ;

paramDecl
    : ident COLON typeRef
    ;

typeRef
    : LIST typeRef      # listType
    | OPTIONAL typeRef  # optionalType
    | ident             # simpleType
    ;

fieldDecl
    : ident COLON typeRef
    ;

// Soft keywords: these are keywords in fact pattern contexts but valid identifiers elsewhere
ident
    : IDENT | FROM_KW | TOKEN_KW | POLICY_KW | AMOUNT_KW | BURNED_KW | FEE_KW
    ;

datumDecl
    : DATUM ident COLON fieldDecl+
    ;

recordDecl
    : RECORD ident COLON fieldDecl+
    ;

redeemerDecl
    : REDEEMER ident COLON redeemerBody
    ;

redeemerBody
    : variantDecl+     # variantRedeemer
    | fieldDecl+       # recordRedeemer
    ;

variantDecl
    : PIPE ident (COLON fieldDecl+)?
    ;

// ============================================================
// Purpose sections (multi-validator)
// ============================================================

purposeSection
    : PURPOSE purposeType COLON redeemerDecl? ruleDecl* defaultDecl
    ;

// ============================================================
// Rules
// ============================================================

ruleDecl
    : RULE STRING WHEN factPattern+ THEN action (TRACE STRING)?
    ;

action
    : ALLOW | DENY
    ;

defaultDecl
    : DEFAULT COLON action
    ;

// ============================================================
// Fact patterns
// ============================================================

factPattern
    : REDEEMER_KW LPAREN variantMatch RPAREN              # redeemerPattern
    | TRANSACTION LPAREN txField COLON expr RPAREN         # transactionPattern
    | DATUM_KW LPAREN variantMatch RPAREN                  # datumPattern
    | CONDITION LPAREN expr RPAREN                         # conditionPattern
    | OUTPUT LPAREN outputField (COMMA outputField)* RPAREN # outputPattern
    | INPUT LPAREN inputField (COMMA inputField)* RPAREN    # inputPattern
    | MINT LPAREN mintField (COMMA mintField)* RPAREN       # mintPattern
    | CONTINUING_OUTPUT LPAREN outputField (COMMA outputField)* RPAREN # continuingOutputPattern
    ;

txField
    : SIGNED_BY | VALID_AFTER | VALID_BEFORE | FEE_KW
    ;

inputField
    : FROM_KW COLON expr            # inputFrom
    | VALUE COLON VAR_BINDING       # inputValue
    | TOKEN_KW COLON valueExpr      # inputToken
    ;

mintField
    : POLICY_KW COLON expr          # mintPolicy
    | TOKEN_KW COLON expr           # mintToken
    | AMOUNT_KW COLON VAR_BINDING   # mintAmount
    | BURNED_KW                     # mintBurned
    ;

outputField
    : TO COLON expr              # outputTo
    | VALUE COLON valueExpr      # outputValue
    | DATUM_KW COLON datumExpr   # outputDatum
    | DATUM COLON datumExpr      # outputDatumLower
    ;

valueExpr
    : MIN_ADA LPAREN expr RPAREN                           # minAdaValue
    | CONTAINS LPAREN expr COMMA expr COMMA expr RPAREN    # containsValue
    ;

datumExpr
    : INLINE ident LPAREN datumFieldExpr (COMMA datumFieldExpr)* RPAREN
    ;

datumFieldExpr
    : ident COLON expr
    ;

// ============================================================
// Pattern matching (for Redeemer/Datum fact patterns)
// ============================================================

variantMatch
    : ident (LPAREN fieldMatch (COMMA fieldMatch)* RPAREN)?
    ;

fieldMatch
    : ident COLON matchValue
    ;

matchValue
    : VAR_BINDING                                              # bindingValue
    | ident LPAREN fieldMatch (COMMA fieldMatch)* RPAREN       # nestedMatchValue
    | expr                                                     # literalValue
    ;

// ============================================================
// Expressions
// In ANTLR4 left-recursive rules, alternatives listed first get the
// highest precedence (tightest binding). Order: tightest to loosest.
// ============================================================

expr
    : expr DOT ident                  # fieldAccessExpr
    | ident LPAREN exprList? RPAREN   # functionCallExpr
    | NOT expr                        # notExpr
    | expr STAR expr                  # multiplicativeExpr
    | expr (PLUS | MINUS) expr        # additiveExpr
    | expr (GT | GTE | LT | LTE) expr # comparisonExpr
    | expr (EQ | NEQ) expr            # equalityExpr
    | expr AND expr                   # andExpr
    | expr OR expr                    # orExpr
    | LPAREN expr RPAREN             # parenExpr
    | VAR_BINDING                     # varRefExpr
    | OWN_ADDRESS                     # ownAddressExpr
    | OWN_POLICY_ID                   # ownPolicyIdExpr
    | ident                           # identRefExpr
    | INTEGER                         # intLiteralExpr
    | STRING                          # stringLiteralExpr
    | HEX_LITERAL                     # hexLiteralExpr
    | TRUE                            # trueLiteralExpr
    | FALSE                           # falseLiteralExpr
    ;

exprList
    : expr (COMMA expr)*
    ;

// ============================================================
// Lexer rules — keywords
// ============================================================

CONTRACT    : 'contract' ;
VERSION     : 'version' ;
PURPOSE     : 'purpose' ;
PARAMS      : 'params' ;
DATUM       : 'datum' ;
REDEEMER    : 'redeemer' ;
RECORD      : 'record' ;
RULE        : 'rule' ;
WHEN        : 'when' ;
THEN        : 'then' ;
ALLOW       : 'allow' ;
DENY        : 'deny' ;
DEFAULT     : 'default' ;
TRACE       : 'trace' ;

// Fact pattern keywords
REDEEMER_KW       : 'Redeemer' ;
DATUM_KW          : 'Datum' ;
TRANSACTION       : 'Transaction' ;
CONDITION         : 'Condition' ;
OUTPUT            : 'Output' ;
INPUT             : 'Input' ;
MINT              : 'Mint' ;
CONTINUING_OUTPUT : 'ContinuingOutput' ;

// Transaction fields
SIGNED_BY   : 'signedBy' ;
VALID_AFTER : 'validAfter' ;
VALID_BEFORE: 'validBefore' ;

// Output / Input / Mint keywords
TO          : 'to' ;
FROM_KW     : 'from' ;
VALUE       : 'value' ;
MIN_ADA     : 'minADA' ;
CONTAINS    : 'contains' ;
INLINE      : 'inline' ;
TOKEN_KW    : 'token' ;
POLICY_KW   : 'policy' ;
AMOUNT_KW   : 'amount' ;
BURNED_KW   : 'burned' ;
FEE_KW      : 'fee' ;

// Purpose types
SPENDING    : 'spending' ;
MINTING     : 'minting' ;
WITHDRAW    : 'withdraw' ;
CERTIFYING  : 'certifying' ;
VOTING      : 'voting' ;
PROPOSING   : 'proposing' ;

// Built-in references
OWN_ADDRESS  : 'ownAddress' ;
OWN_POLICY_ID: 'ownPolicyId' ;

// Type keywords
LIST        : 'List' ;
OPTIONAL    : 'Optional' ;

// Boolean literals
TRUE        : 'true' ;
FALSE       : 'false' ;

// ============================================================
// Lexer rules — operators and delimiters
// ============================================================

EQ     : '==' ;
NEQ    : '!=' ;
GT     : '>' ;
GTE    : '>=' ;
LT     : '<' ;
LTE    : '<=' ;
PLUS   : '+' ;
MINUS  : '-' ;
STAR   : '*' ;
AND    : '&&' ;
OR     : '||' ;
NOT    : '!' ;

LPAREN : '(' ;
RPAREN : ')' ;
COLON  : ':' ;
COMMA  : ',' ;
PIPE   : '|' ;
DOT    : '.' ;

// ============================================================
// Lexer rules — literals and identifiers
// ============================================================

STRING      : '"' (~["\r\n])* '"' ;
INTEGER     : [0-9]+ ;
HEX_LITERAL : '0' [xX] [0-9a-fA-F]+ ;
VAR_BINDING : '$' [a-zA-Z_] [a-zA-Z0-9_]* ;
IDENT       : [a-zA-Z_] [a-zA-Z0-9_]* ;

// ============================================================
// Lexer rules — skip whitespace and comments
// ============================================================

LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;
