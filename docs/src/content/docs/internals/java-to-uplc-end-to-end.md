---
title: "Java to UPLC End to End"
description: "A beginner-friendly deep dive into how JuLC compiles Java to PIR, UPLC, FLAT bytes, and Cardano script CBOR."
---

This guide is for JuLC developers who want to work on the core language, PIR generation, UPLC lowering, encoding, or compiler tests.

It starts from the basic concepts and then follows the real JuLC pipeline:

```text
Java source
  -> JavaParser AST
  -> validation and type resolution
  -> PIR
  -> validator wrapper
  -> UPLC
  -> optimization
  -> Program
  -> FLAT bytes
  -> double-CBOR script hex
```

The main implementation files are:

| Area | File |
| ---- | ---- |
| Pipeline orchestration | `julc-compiler/.../compiler/JulcCompiler.java` |
| Java subset validation | `julc-compiler/.../compiler/validate/SubsetValidator.java` |
| Type registration and resolution | `julc-compiler/.../compiler/resolve/TypeRegistrar.java`, `TypeResolver.java` |
| Java AST to PIR | `julc-compiler/.../compiler/pir/PirGenerator.java` |
| PIR AST | `julc-compiler/.../compiler/pir/PirTerm.java`, `PirType.java` |
| Validator wrapper | `julc-compiler/.../compiler/codegen/ValidatorWrapper.java` |
| PIR to UPLC | `julc-compiler/.../compiler/uplc/UplcGenerator.java` |
| UPLC optimizer | `julc-compiler/.../compiler/uplc/UplcOptimizer.java` |
| UPLC AST | `julc-core/.../core/Term.java`, `Program.java` |
| Text UPLC | `julc-core/.../core/text/UplcPrinter.java`, `UplcParser.java` |
| FLAT encoding | `julc-core/.../core/flat/UplcFlatEncoder.java`, `FlatWriter.java` |
| Data CBOR | `julc-core/.../core/cbor/PlutusDataCborEncoder.java` |
| Ledger script adapter | `julc-cardano-client-lib/.../clientlib/JulcScriptAdapter.java` |

## 1. Background Concepts

### Java Source Is the User Language

JuLC does not compile JVM bytecode. It parses `.java` source with JavaParser. That means compiler behavior is driven by Java syntax trees, annotations, method declarations, records, sealed interfaces, expressions, and statements.

A validator class is discovered by annotation:

```java
@SpendingValidator
class AlwaysSucceeds {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
```

The compiler accepts a deterministic on-chain subset of Java. The subset keeps features that can be translated into pure UPLC and rejects features that imply JVM runtime behavior, mutation-heavy object state, I/O, exceptions, floating point, threads, or hidden side effects.

### Lambda Calculus in Five Minutes

UPLC is based on lambda calculus. You do not need to be a programming language researcher to work on JuLC, but you do need the core ideas because most compiler transformations eventually become lambdas and applications.

The smallest useful lambda calculus has only three term forms:

```text
term =
  variable
  lambda abstraction
  function application
```

In text:

```text
x              variable
\x -> x        lambda abstraction: a function that takes x and returns x
f x            function application: call f with argument x
```

In JuLC/UPLC text, the same ideas are printed like this:

```text
x
(lam x x)
[f x]
```

The most important concepts:

| Concept | Meaning for JuLC developers |
| ------- | --------------------------- |
| Lambda | A function value. Java methods, helper functions, and wrappers become nested `Lam` terms. |
| Application | Calling a function. Java method calls and builtin calls become nested `Apply` terms. |
| Binding | A variable is in scope inside the body of the lambda or let that introduced it. |
| Free variable | A variable reference with no binding in scope. In JuLC this usually becomes an "Unbound variable" compiler bug. |
| Substitution | Replacing a bound variable with an argument. Beta reduction is the optimizer's safe version of this. |
| Currying | A multi-argument function is represented as nested one-argument functions. |
| Strictness | UPLC evaluates function arguments eagerly, so condition branches must be delayed explicitly. |

Currying is everywhere in UPLC. A Java call with two arguments:

```java
add(x, y)
```

is represented as two one-argument applications:

```text
[[add x] y]
```

A Java function with two parameters:

```java
static BigInteger add(BigInteger x, BigInteger y) {
    return x.add(y);
}
```

becomes nested lambdas:

```text
(lam x : Integer
  (lam y : Integer
    [[(builtin addInteger) x] y]))
```

This is why almost every JuLC lowering rule either creates `Lam`, creates `Apply`, or rewrites a higher-level construct into a combination of both.

### Beta Reduction and `let`

Lambda application has a core rewrite rule called beta reduction:

```text
[(lam x body) value]  ->  body with x replaced by value
```

Example:

```text
[(lam x [[(builtin addInteger) x] (con integer 1)])
  (con integer 41)]

-> [[(builtin addInteger) (con integer 41)] (con integer 1)]
```

PIR has `Let` because it is easier to generate and read:

```text
let x = 41 in x + 1
```

UPLC does not have `let`, so `UplcGenerator` lowers it to lambda application:

```text
(let x = value in body)

-> [(lam x body) value]
```

The optimizer can later perform beta reduction if doing so is safe and beneficial.

### Scope and De Bruijn Indices

PIR uses names. UPLC variables are encoded by De Bruijn index.

```text
(lam x
  (lam y
    x))
```

Inside the inner lambda:

```text
y = index 1
x = index 2
```

The printed name `x` is only for readability. The binary FLAT payload stores the index. This is why `UplcGenerator` maintains a scope stack.

Common De Bruijn mistakes:

| Symptom | Likely cause |
| ------- | ------------ |
| `Unbound variable: x` | A PIR reference was generated outside the `Let` or `Lam` that should bind it. |
| Wrong value used at runtime | Scope push/pop order is wrong, or a generated variable name accidentally shadows another binding. |
| Recursion breaks | `LetRec` body was generated without the recursive name in scope. |

### Strict Evaluation, Delay, and Force

UPLC is strict: function arguments are evaluated before the function receives them.

That matters for conditionals. This Java code must evaluate only one branch:

```java
return ok ? goodValue() : errorValue();
```

If JuLC lowered it as a normal strict function call, both branches could run. Instead, `UplcGenerator` delays both branches and forces the selected one:

```text
PIR:
(if cond thenBranch elseBranch)

UPLC shape:
(force
  [[[(force (builtin ifThenElse)) cond]
     (delay thenBranch)]
    (delay elseBranch)])
```

`Delay` creates a suspended computation. `Force` evaluates it. Polymorphic builtins also need `Force`, but for a different reason: type instantiation. In JuLC both cases use the same UPLC term constructor, so read the surrounding pattern carefully.

### Plutus Data Is the Ledger Data Format

On-chain data is represented as `Data`, also called `PlutusData` in JuLC:

```text
Data =
  Constr(tag, fields)
  Map([(Data, Data)])
  List([Data])
  I(integer)
  B(bytes)
```

Cardano passes script arguments as `Data`. Java records and sealed variants are encoded into this representation:

```java
record Point(BigInteger x, BigInteger y) {}
```

becomes a `ConstrData` value:

```text
Constr(0, [I(x), I(y)])
```

This is separate from FLAT. `Data` is the value format used by the ledger and builtins. FLAT is the binary format used to serialize a UPLC program.

### PIR Is JuLC's Typed Bridge

PIR means Plutus Intermediate Representation. In JuLC it is a typed lambda-calculus-like AST between Java and UPLC.

PIR exists because Java has names, types, local variables, helper methods, records, loops, and pattern matching. UPLC is much smaller: it has lambdas, applications, constants, builtins, delays, forces, errors, and Plutus V3 constructor/case terms.

PIR keeps enough information to make the right lowering decisions:

```java
sealed interface PirTerm {
    record Var(String name, PirType type)
    record Let(String name, PirTerm value, PirTerm body)
    record LetRec(List<Binding> bindings, PirTerm body)
    record Lam(String param, PirType paramType, PirTerm body)
    record App(PirTerm function, PirTerm argument)
    record Const(Constant value)
    record Builtin(DefaultFun fun)
    record IfThenElse(PirTerm cond, PirTerm thenBranch, PirTerm elseBranch)
    record DataConstr(int tag, PirType dataType, List<PirTerm> fields)
    record DataMatch(PirTerm scrutinee, List<MatchBranch> branches)
    record Error(PirType type)
    record Trace(PirTerm message, PirTerm body)
}
```

The important point: PIR is not the on-chain format. It is a compiler-internal form designed to be easy to generate from Java and easy to lower to UPLC.

### UPLC Is the On-Chain Program Language

UPLC means Untyped Plutus Core. JuLC represents it with `Term` in `julc-core`:

```java
sealed interface Term {
    record Var(NamedDeBruijn name)
    record Lam(String paramName, Term body)
    record Apply(Term function, Term argument)
    record Force(Term term)
    record Delay(Term term)
    record Const(Constant value)
    record Builtin(DefaultFun fun)
    record Error()
    record Constr(long tag, List<Term> fields)
    record Case(Term scrutinee, List<Term> branches)
}
```

UPLC is untyped. Names are only debug labels; variables are resolved by De Bruijn index.

```text
Index 1 = nearest enclosing lambda
Index 2 = next enclosing lambda
Index 3 = one more outer lambda
```

Example:

```text
PIR:
(lam x : Integer (lam y : Integer x))

UPLC:
(lam x (lam y x))

Actual variable identity:
x inside the inner lambda has De Bruijn index 2.
```

### UPLC Text Grammar

JuLC's `UplcPrinter` and `UplcParser` use a compact text format for tests, debugging, CLI commands, and docs. The exact parser lives in `julc-core/.../core/text/UplcParser.java`; this grammar is a practical contributor reference.

```text
program =
  "(" "program" version term ")"

version =
  natural "." natural "." natural

term =
    variable
  | "(" "lam" name term ")"
  | "[" term term "]"
  | "(" "force" term ")"
  | "(" "delay" term ")"
  | "(" "con" constant ")"
  | "(" "builtin" builtinName ")"
  | "(" "error" ")"
  | "(" "constr" natural term* ")"
  | "(" "case" term term* ")"

constant =
    "integer" integer
  | "bytestring" "#" hex
  | "string" quotedString
  | "unit" "()"
  | "bool" ("True" | "False")
  | "data" dataLiteral
  | type constantValue

dataLiteral =
    "I" integer
  | "B" "#" hex
  | "List" "[" dataLiteral* "]"
  | "Map" "[" dataPair* "]"
  | "Constr" natural "[" dataLiteral* "]"
```

Examples:

```text
(program 1.1.0 (con integer 42))

(program 1.1.0
  (lam x
    [[(builtin addInteger) x] (con integer 1)]))

(program 1.1.0
  (force
    [[[(force (builtin ifThenElse))
        (con bool True)]
       (delay (con integer 1))]
      (delay (con integer 0))]))
```

Application is left-associated by nesting:

```text
f a b c

is printed as:
[[[f a] b] c]
```

That style can look noisy at first, but it maps directly to the binary AST:

```text
Apply(Apply(Apply(f, a), b), c)
```

### UPLC Values and Evaluation Results

Not every `Term` is a final value. During CEK evaluation, terms reduce until they become a value or hit `Error`.

Useful mental model:

| Term | Runtime role |
| ---- | ------------ |
| `Lam` | Function value |
| `Delay` | Suspended computation |
| `Const` | Constant value |
| `Builtin` | Builtin function waiting for required forces and arguments |
| `Apply` | Work to do |
| `Force` | Work to do |
| `Error` | Immediate script failure |
| `Constr` / `Case` | Plutus V3 sums-of-products terms |

Validator wrappers intentionally convert Java `boolean` results into UPLC success/failure:

```text
true  -> Unit
false -> Error
```

The ledger cares whether evaluation succeeds, how much budget it used, and what final value was produced. For validators, success with `Unit` is the normal compiled shape.

### FLAT Is the Binary Encoding of UPLC

FLAT serializes the `Program` and `Term` tree into compact bits:

```text
Program = major natural, minor natural, patch natural, term, filler
Term    = 4-bit tag + payload
Builtin = 7-bit builtin code
Const   = type tag list + value
```

JuLC writes FLAT in `UplcFlatEncoder` using `FlatWriter`. The output of FLAT is the script bytes whose length matters for on-chain script size limits.

After FLAT, Cardano tooling expects script CBOR. In JuLC's cardano-client-lib adapter:

```text
Program
  -> FLAT bytes
  -> inner CBOR bytestring containing FLAT bytes
  -> outer CBOR bytestring containing inner CBOR
  -> PlutusV3Script.cborHex
```

This double-CBOR wrapping is implemented in `JulcScriptAdapter.fromProgram`.

## 2. The Real Pipeline in `JulcCompiler`

The main path is `JulcCompiler.doCompile(...)`.

### Phase 1: Parse Source

JuLC configures JavaParser for Java 21 and parses the validator plus optional library sources:

```text
String validatorSource
List<String> librarySources
  -> CompilationUnit validatorCu
  -> List<CompilationUnit> libraryCus
```

Source-file compile methods preserve file paths in the JavaParser `CompilationUnit`. That matters for diagnostics and source maps.

### Phase 2: Validate the Java Subset

`SubsetValidator` walks each `CompilationUnit` and rejects unsupported Java.

This phase catches unsupported language constructs before PIR generation. That is better than allowing an arbitrary AST node to drift into a confusing "unsupported expression" error later.

### Phase 3: Find the Validator and Purpose

The compiler finds the annotated class and determines the script purpose:

```text
@SpendingValidator -> SPENDING
@MintingValidator -> MINTING
@WithdrawValidator -> WITHDRAW
@CertifyingValidator -> CERTIFYING
@VotingValidator -> VOTING
@ProposingValidator -> PROPOSING
@MultiValidator -> MULTI
```

The purpose decides how the validator wrapper extracts arguments from `ScriptContext`.

### Phase 4: Register Types

The compiler registers:

1. Ledger API types bundled with JuLC.
2. User records and sealed interfaces from validator and library sources.
3. Newtype aliases.
4. Known fully qualified names for import resolution.

This builds the bridge from Java types to PIR types:

| Java type | PIR type |
| --------- | -------- |
| `long`, `int`, `BigInteger` | `IntegerType` |
| `byte[]` | `ByteStringType` |
| `boolean` | `BoolType` |
| `String` | `StringType` |
| `PlutusData` | `DataType` |
| `JulcList<T>` | `ListType(T)` |
| `JulcMap<K,V>` | `MapType(K,V)` |
| Java `record` | `RecordType` |
| sealed interface variant | `SumType` / `RecordType` |
| `Optional<T>` | `OptionalType(T)` |

### Phase 5: Collect Parameters, Static Fields, and Entrypoints

`@Param` fields become outer UPLC lambdas. Static fields with initializers become PIR `Let` bindings.

For a parameterized validator:

```java
@Param
static BigInteger threshold;
```

JuLC wraps the script as:

```text
lam threshold__raw : Data
  let threshold = UnIData(threshold__raw)
  in <validator>
```

The deployed script is not fully usable until the parameter is applied with `Program.applyParams(...)` or the blueprint/CLI equivalent.

### Phase 6: Compile Libraries and Helpers

JuLC compiles `@OnchainLibrary` Java source methods to PIR first. Library methods are stored in `LibraryMethodRegistry`. Standard library PIR builders are resolved through `StdlibLookup` and `StdlibRegistry`.

Helper methods inside the validator class are also compiled to PIR lambdas and stored in `SymbolTable`. Later, they are wrapped around the entrypoint as `Let` or `LetRec`.

### Phase 7: Generate PIR for the Entrypoint

`PirGenerator.generateMethod(method)` turns the JavaParser method AST into a PIR lambda:

```text
static boolean validate(PlutusData redeemer, ScriptContext ctx) { ... }

-> lam redeemer : Data
     lam ctx : ScriptContext
       <body PIR>
```

The body is generated from statements and expressions.

### Phase 8: Wrap the Validator

Validators on Cardano receive a single `ScriptContext` argument as `Data`. Java entrypoints look more ergonomic:

```java
static boolean validate(PlutusData redeemer, ScriptContext ctx)
```

`ValidatorWrapper` connects those worlds:

```text
lam scriptContextData : Data
  let ctxFields = SndPair(UnConstrData(scriptContextData))
  let redeemer = HeadList(TailList(ctxFields))
  let result = validate(redeemer, scriptContextData)
  in if result then Unit else Error
```

For a 3-argument spending validator, the wrapper also extracts datum from `ScriptInfo.SpendingScript`.

### Phase 9: Lower PIR to UPLC

`UplcGenerator.generate(pir)` erases types and lowers high-level PIR:

| PIR | UPLC lowering |
| --- | ------------- |
| `Var(name, type)` | `Var(deBruijnIndex(name))` |
| `Lam(name, type, body)` | `Lam(name, body')` |
| `App(f, x)` | `Apply(f', x')` |
| `Let(name, value, body)` | `Apply(Lam(name, body'), value')` |
| `LetRec` | strict fixed-point combinator or dependency decomposition |
| `IfThenElse(c,t,e)` | `Force(Apply(Apply(Apply(Force(Builtin IfThenElse), c), Delay(t)), Delay(e)))` |
| `Builtin(fun)` | `Builtin(fun)` plus required `Force`s for polymorphic builtins |
| `DataConstr` | `ConstrData(tag, encodedFields)` |
| `DataMatch` | `UnConstrData` plus tag dispatch |

The `Delay`/`Force` around branches is critical. UPLC is strict; without delayed branches, both branches could evaluate before the condition decides which one to use.

### Phase 10: Optimize UPLC

When source maps are disabled, `UplcOptimizer` runs after lowering. It performs small local rewrites such as force/delay cancellation, beta reduction where safe, dead let removal, and constant folding.

When source maps are enabled, optimization is skipped to preserve term identity for error location mapping.

### Phase 11: Create `Program`

JuLC currently emits Plutus V3 under its PV11 compiler
contract:

```java
var program = Program.plutusV3(uplcTerm);
```

That means the version is:

```text
1.1.0
```

The `Program` stores the UPLC version, not the ledger protocol version. JuLC's
compiler policy therefore enforces the PV11 builtin boundary while lowering;
future tag 101 (`MultiIndexArray`) is rejected before a script is produced.

### Phase 12: Encode

At the compiler API level, `CompileResult.program()` is the canonical output.

For binary size:

```java
UplcFlatEncoder.encodeProgram(program).length
```

For a Cardano script hex:

```java
JulcScriptAdapter.fromProgram(program).getCborHex()
```

## 3. Java to PIR Rules by Example

The examples below show the shape of generated code. Real compiler output includes wrapper lets, unique names, helper bindings, and optimizer effects.

### Literal Return

Java:

```java
return true;
```

PIR:

```text
(con bool True)
```

Inside an entrypoint with source-map return guards enabled, the compiler may wrap a boolean return:

```text
(if (con bool True)
    (con bool True)
    (error))
```

After validator wrapping, the boolean becomes success or failure:

```text
(if result__
    (con unit ())
    (error))
```

### Integer Arithmetic

Java:

```java
BigInteger x = BigInteger.valueOf(1);
BigInteger y = BigInteger.valueOf(2);
return x.add(y).equals(BigInteger.valueOf(3));
```

Representative PIR:

```text
(let x = (con integer 1) in
  (let y = (con integer 2) in
    [[(builtin equalsInteger)
      [[(builtin addInteger) x] y]]
      (con integer 3)]))
```

Representative UPLC:

```text
[(lam x
   [(lam y
      [[(builtin equalsInteger)
        [[(builtin addInteger) x] y]]
        (con integer 3)])
    (con integer 2)])
 (con integer 1)]
```

The `let` bindings disappeared. UPLC only has lambdas and application, so `let x = v in body` becomes `(lam x body) v`.

### If / Else

Java:

```java
if (amount.compareTo(BigInteger.ZERO) > 0) {
    return true;
} else {
    return false;
}
```

PIR:

```text
(if [[(builtin lessThanInteger) (con integer 0)] amount]
    (con bool True)
    (con bool False))
```

UPLC shape:

```text
(force
  [[[(force (builtin ifThenElse))
      [[(builtin lessThanInteger) (con integer 0)] amount]]
     (delay (con bool True))]
    (delay (con bool False))])
```

The force count comes from `DefaultFun.IfThenElse`, which is polymorphic and must be forced before application.

### Short-Circuit Boolean Operators

Java:

```java
return hasSignature && amountOk;
```

PIR:

```text
(if hasSignature
    amountOk
    (con bool False))
```

Java:

```java
return isOwner || isAdmin;
```

PIR:

```text
(if isOwner
    (con bool True)
    isAdmin)
```

This preserves short-circuit behavior.

### Helper Methods

Java:

```java
static boolean isPositive(BigInteger x) {
    return x.compareTo(BigInteger.ZERO) > 0;
}

@Entrypoint
static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    return isPositive(BigInteger.valueOf(42));
}
```

PIR before helper wrapping:

```text
isPositive =
  (lam x : Integer
    [[(builtin lessThanInteger) (con integer 0)] x])

validate =
  (lam redeemer : Data
    (lam ctx : ScriptContext
      [isPositive (con integer 42)]))
```

PIR after wrapping helper as a binding:

```text
(let isPositive =
    (lam x : Integer
      [[(builtin lessThanInteger) (con integer 0)] x])
 in
    (lam redeemer : Data
      (lam ctx : ScriptContext
        [isPositive (con integer 42)])))
```

UPLC shape:

```text
[(lam isPositive
    (lam redeemer
      (lam ctx
        [isPositive (con integer 42)])))
  (lam x
    [[(builtin lessThanInteger) (con integer 0)] x])]
```

### Record Construction

Java:

```java
record Point(BigInteger x, BigInteger y) {}

Point p = new Point(BigInteger.valueOf(10), BigInteger.valueOf(20));
```

PIR:

```text
(constr 0 (con integer 10) (con integer 20))
```

The `constr` here is PIR `DataConstr`, not UPLC `Term.Constr`. During lowering, fields are encoded into `Data`:

```text
ConstrData(
  0,
  MkCons(IData(10),
    MkCons(IData(20),
      MkNilData(()))))
```

UPLC text shape:

```text
[[(builtin constrData)
   (con integer 0)]
  [[(force (builtin mkCons))
     [(builtin iData) (con integer 10)]]
    [[(force (builtin mkCons))
       [(builtin iData) (con integer 20)]]
      [(builtin mkNilData) (con unit ())]]]]
```

### Record Field Access

Java:

```java
return p.x().equals(BigInteger.TEN);
```

PIR shape:

```text
(let __fields = [(builtin sndPair) [(builtin unConstrData) p]] in
  [[(builtin equalsInteger)
    [(builtin unIData) [(force (builtin headList)) __fields]]]
    (con integer 10)])
```

Field access goes through ledger `Data`:

```text
Data value
  -> UnConstrData(data)
  -> pair(tag, fields)
  -> SndPair(pair)
  -> HeadList/TailList to locate field
  -> UnIData/UnBData/etc. to decode the field
```

### Sealed Interface Pattern Match

Java:

```java
sealed interface Action permits Deposit, Withdraw {}
record Deposit(BigInteger amount) implements Action {}
record Withdraw(BigInteger amount) implements Action {}

return switch (action) {
    case Deposit d -> d.amount().compareTo(BigInteger.ZERO) > 0;
    case Withdraw w -> w.amount().compareTo(BigInteger.ZERO) > 0;
};
```

PIR:

```text
(match action
  (Deposit amount ->
    [[(builtin lessThanInteger) (con integer 0)] amount])
  (Withdraw amount ->
    [[(builtin lessThanInteger) (con integer 0)] amount]))
```

Lowering shape:

```text
let pair = UnConstrData(action)
let tag = FstPair(pair)
let fields = SndPair(pair)
in if tag == 0 then
     let amount = UnIData(HeadList(fields)) in ...
   else if tag == 1 then
     let amount = UnIData(HeadList(fields)) in ...
   else
     Error
```

### For-Each Loop with an Accumulator

Java:

```java
BigInteger sum = BigInteger.ZERO;
for (PlutusData item : items) {
    sum = sum.add(Builtins.unIData(item));
}
return sum.equals(BigInteger.valueOf(10));
```

PIR shape:

```text
(letrec
  ((loop__forEach__0 =
     (lam xs : List[Data]
       (lam acc : Integer
         (if [(force (builtin nullList)) xs]
             acc
             (let item = [(force (builtin headList)) xs] in
               [[loop__forEach__0 [(force (builtin tailList)) xs]]
                 [[(builtin addInteger) acc] [(builtin unIData) item]]]))))))
  in
    [[(builtin equalsInteger)
      [[loop__forEach__0 items] (con integer 0)]]
      (con integer 10)])
```

UPLC has no `letrec`, so `UplcGenerator` lowers this using a strict fixed-point combinator.

### `@Param` Script Parameter

Java:

```java
@Param
static BigInteger threshold;

@Entrypoint
static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    BigInteger amount = Builtins.unIData(redeemer);
    return amount.compareTo(threshold) >= 0;
}
```

PIR after parameter wrapping:

```text
(lam threshold__raw : Data
  (let threshold = [(builtin unIData) threshold__raw] in
    (lam scriptContextData : Data
      ...)))
```

A parameterized script is a function waiting for parameter `Data`. Applying the parameter creates a concrete program:

```java
Program concrete = result.program().applyParams(PlutusData.integer(BigInteger.valueOf(100)));
```

## 4. PIR to UPLC Lowering Details

### Named Variables Become De Bruijn Variables

PIR:

```text
(lam x : Integer
  (lam y : Integer
    [[(builtin addInteger) x] y]))
```

UPLC text:

```text
(lam x
  (lam y
    [[(builtin addInteger) x] y]))
```

Internally:

```text
y -> De Bruijn index 1
x -> De Bruijn index 2
```

The names printed by `UplcPrinter` are for readability. The encoded UPLC variable payload is the index.

### `Let` Is Syntactic Sugar

PIR:

```text
(let x = (con integer 42) in
  [[(builtin addInteger) x] (con integer 1)])
```

UPLC:

```text
[(lam x
    [[(builtin addInteger) x] (con integer 1)])
  (con integer 42)]
```

### Builtin Forces

Some Plutus builtins are polymorphic. In UPLC, polymorphic builtins must be forced before use.

| Builtin kind | Examples | Force count |
| ------------ | -------- | ----------- |
| Monomorphic | `addInteger`, `equalsInteger`, `iData`, `unIData` | 0 |
| One type parameter | `ifThenElse`, `trace`, `mkCons`, `headList`, `tailList`, `nullList` | 1 |
| Two type parameters | `fstPair`, `sndPair`, `chooseList` | 2 |

PIR:

```text
[(builtin headList) xs]
```

UPLC:

```text
[(force (builtin headList)) xs]
```

### Data Encoding and Decoding

JuLC inserts encode/decode operations at boundaries where typed Java values cross into ledger `Data`.

Decode examples:

| Target PIR type | Decode from `Data` |
| ---------------- | ------------------ |
| `IntegerType` | `UnIData(data)` |
| `ByteStringType` | `UnBData(data)` |
| `BoolType` | `FstPair(UnConstrData(data)) == 1` |
| `StringType` | `DecodeUtf8(UnBData(data))` |
| `ListType` | `UnListData(data)` |
| `MapType` | `UnMapData(data)` |
| `DataType`, record, sum | pass raw `Data` |

Encode examples:

| PIR type | Encode to `Data` |
| -------- | ---------------- |
| `IntegerType` | `IData(value)` |
| `ByteStringType` | `BData(value)` |
| `BoolType` | `ConstrData(0, [])` for false, `ConstrData(1, [])` for true |
| `StringType` | `BData(EncodeUtf8(value))` |
| `ListType` | `ListData(value)` |
| `MapType` | `MapData(value)` |
| `DataType`, record, sum | already `Data` |

The helpers live in `PirHelpers.wrapDecode` and `PirHelpers.wrapEncode`.

## 5. UPLC Text and FLAT Encoding

### UPLC Text Is for Humans

JuLC can print a `Program`:

```java
String text = UplcPrinter.print(program);
```

Example:

```text
(program 1.1.0 (con integer 42))
```

This is not the deployed binary. It is a readable text form.

### FLAT Is the Deployed Program Bytes

Encoding the example above with `UplcFlatEncoder.encodeProgram(program)` gives:

```text
010100481501
```

Breakdown at a high level:

```text
01       major version natural: 1
01       minor version natural: 1
00       patch version natural: 0
...      term tag + constant type tags + integer payload
...01    filler/alignment
```

Another example:

UPLC:

```text
(program 1.1.0 [[(builtin addInteger) (con integer 1)] (con integer 2)])
```

FLAT hex:

```text
01010033700900124009
```

These are small standalone UPLC terms, not full validators. Full validators include the ScriptContext wrapper, data decoding, and success/error conversion, so their FLAT is much larger.

### Term Tags in FLAT

`UplcFlatEncoder.writeTerm` writes a 4-bit tag before each term payload:

| Term | Tag |
| ---- | --- |
| `Var` | 0 |
| `Delay` | 1 |
| `Lam` | 2 |
| `Apply` | 3 |
| `Const` | 4 |
| `Force` | 5 |
| `Error` | 6 |
| `Builtin` | 7 |
| `Constr` | 8 |
| `Case` | 9 |

For a builtin, the payload is a 7-bit `DefaultFun.flatCode()`.

For a constant, the payload is:

```text
type tag list
constant value
```

Data constants are special:

```text
PlutusData
  -> CBOR bytes
  -> FLAT bytestring
```

That is why `UplcFlatEncoder.writeData` calls `PlutusDataCborEncoder.encode(data)` first.

### `FlatWriter` Rules

`FlatWriter` is the low-level bit writer:

| Method | Meaning |
| ------ | ------- |
| `bits(n, value)` | write `n` bits, most-significant position first in the current byte |
| `natural(value)` | write non-negative integer using vli7 |
| `integer(value)` | zigzag signed integer, then vli7 |
| `word64(value)` | unsigned 64-bit integer using vli7 |
| `byteString(bytes)` | align with filler, write 255-byte chunks, then `0x00` terminator |
| `listCons()` / `listNil()` | write 1-bit list continuation markers |
| `filler()` | align final program to byte boundary |

Integer encoding uses vli7 chunks. Signed integers use zigzag first:

```text
0  -> 0
1  -> 2
-1 -> 1
2  -> 4
-2 -> 3
```

This makes small negative and positive numbers compact.

## 6. Final Cardano Script Form

The final form used by Cardano client libraries is not just raw FLAT hex.

JuLC's adapter does:

```java
byte[] flatBytes = UplcFlatEncoder.encodeProgram(program);
byte[] innerCbor = cborWrapBytes(flatBytes);
byte[] outerCbor = cborWrapBytes(innerCbor);
PlutusV3Script script = PlutusV3Script.builder()
    .cborHex(hex(outerCbor))
    .build();
```

Conceptually:

```text
UPLC Program
  -> raw FLAT bytes
  -> CBOR bytestring: h'<flat bytes>'
  -> CBOR bytestring: h'<inner cbor bytes>'
  -> cborHex string
```

Why double-CBOR?

Cardano ledger script witnesses use `plutus_v3_script = bytes .cbor bytes`. The outer bytestring is what cardano-client-lib stores as script `cborHex`; its payload must itself be valid CBOR containing the raw FLAT bytes.

## 7. How to Inspect Each Stage

Use `compileWithDetails` while developing compiler features:

```java
var result = new JulcCompiler().compileWithDetails(source);

System.out.println(result.pirPretty());
System.out.println(result.uplcFormatted());
System.out.println(result.scriptSizeFormatted());
```

Important fields:

| Field / method | Meaning |
| -------------- | ------- |
| `result.pirTerm()` | captured PIR root |
| `result.pirFormatted()` | compact PIR text |
| `result.pirPretty()` | indented PIR text |
| `result.uplcTerm()` | captured UPLC root after lowering/optimization |
| `result.uplcFormatted()` | UPLC text for the final `Program` |
| `result.program()` | versioned UPLC program |
| `result.scriptSizeBytes()` | raw FLAT size |
| `result.sourceMap()` | source map if enabled |

The regular `compile(...)` method does not retain PIR or UPLC intermediate terms. Use `compileWithDetails(...)` for tests and debugging.

## 8. Common Compiler Development Tasks

### Add a New Java Expression

Work mostly in `PirGenerator.generateExpression`.

Checklist:

1. Decide the PIR type of the expression.
2. Generate PIR using existing helpers where possible.
3. Record source position if source maps should point at this expression.
4. Add diagnostics with a helpful suggestion for unsupported cases.
5. Add compiler tests that assert either PIR shape or VM behavior.

### Add a New Java Type

Work in:

```text
TypeRegistrar
TypeResolver
PirType
PirHelpers.wrapEncode / wrapDecode
DataCodecGenerator if it is record-like
```

Ask:

```text
Is this value represented as a primitive UPLC constant?
Is it represented as ledger Data?
Does it need encode/decode at method boundaries?
Does it appear in records, lists, maps, or Optional?
```

### Add a New Stdlib Method

There are two paths:

1. Java source library with `@OnchainLibrary` if the method can be expressed in the Java subset.
2. PIR builder in `StdlibRegistry` if it needs higher-order functions, recursion patterns, or compiler-only primitives.

For PIR builders, test the PIR directly and also test Java source that calls the stdlib method.

### Change UPLC Lowering

Work in `UplcGenerator`.

Be careful with:

```text
De Bruijn scope order
force counts for polymorphic builtins
strict evaluation and Delay/Force around branches
LetRec lowering
source-map propagation
```

Add focused tests under compiler tests and, if serialization can change, add or update golden UPLC/FLAT tests.

### Change FLAT Encoding

Work in:

```text
UplcFlatEncoder
UplcFlatDecoder
FlatWriter
FlatReader
```

Any encoder change should have a decoder round-trip test:

```text
Program -> FLAT -> Program
```

For script compatibility, also check known golden `.flat.hex` outputs.

## 9. Mental Model for Debugging

When something fails, identify the boundary where it first becomes wrong:

| Symptom | Start looking at |
| ------- | ---------------- |
| Unsupported Java construct | `SubsetValidator` |
| Wrong type selected | `TypeResolver`, `TypeInferenceHelper`, `SymbolTable` |
| Wrong builtin in PIR | `PirGenerator`, `TypeMethodRegistry`, `StdlibLookup` |
| Field access broken | `TypeRegistrar`, `DataCodecGenerator`, record field extraction in `PirGenerator` |
| Pattern match broken | `PatternMatchDesugarer`, `UplcGenerator.generateDataMatch` |
| Loop behavior wrong | `LoopDesugarer`, `AccumulatorTypeAnalyzer`, `LoopBodyGenerator` |
| Unbound variable | PIR binding shape or `UplcGenerator` scope stack |
| Both branches evaluate | missing `Delay`/`Force` in conditional lowering |
| Script too large | helper/library bindings, recursion patterns, optimizer |
| CBOR script cannot load | `JulcScriptAdapter`, `UplcFlatEncoder`, `UplcFlatDecoder` |

The shortest useful debugging loop is usually:

```java
var result = new JulcCompiler().compileWithDetails(source);
System.out.println(result.pirPretty());
System.out.println(result.uplcFormatted());
System.out.println(result.scriptSizeBytes());
```

If PIR is wrong, fix Java-to-PIR. If PIR is right but UPLC is wrong, fix lowering. If UPLC text is right but binary fails, fix FLAT/CBOR.

## 10. End-to-End Example

Java:

```java
@SpendingValidator
class ThresholdValidator {
    @Param
    static BigInteger threshold;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger amount = Builtins.unIData(redeemer);
        return amount.compareTo(threshold) >= 0;
    }
}
```

Pipeline shape:

```text
JavaParser:
  class ThresholdValidator
  field threshold annotated @Param
  method validate annotated @Entrypoint

Type resolution:
  threshold -> IntegerType
  redeemer -> DataType
  ctx -> ScriptContext record type
  amount -> IntegerType

Entrypoint PIR:
  lam redeemer : Data
    lam ctx : ScriptContext
      let amount = UnIData(redeemer)
      in LessThanEqualsInteger(threshold, amount)

Validator wrapper PIR:
  lam scriptContextData : Data
    let ctxFields = SndPair(UnConstrData(scriptContextData))
    let redeemer__ = HeadList(TailList(ctxFields))
    let result__ = validate(redeemer__, scriptContextData)
    in if result__ then Unit else Error

Parameter wrapper PIR:
  lam threshold__raw : Data
    let threshold = UnIData(threshold__raw)
    in <validator wrapper>

UPLC:
  types erased
  named variables converted to De Bruijn indices
  lets converted to lambda applications
  if converted to forced ifThenElse with delayed branches
  builtins forced where required

Program:
  version 1.1.0
  term = optimized UPLC root

Final bytes:
  UplcFlatEncoder.encodeProgram(program)
  then JulcScriptAdapter.fromProgram(program).getCborHex()
```

This is the complete mental path for most JuLC compiler work: Java shape, typed PIR shape, untyped UPLC shape, raw FLAT bytes, ledger script CBOR.
