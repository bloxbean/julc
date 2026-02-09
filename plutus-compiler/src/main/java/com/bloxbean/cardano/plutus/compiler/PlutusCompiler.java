package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.codegen.ValidatorWrapper;
import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.plutus.compiler.pir.PirGenerator;
import com.bloxbean.cardano.plutus.compiler.pir.PirHelpers;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcOptimizer;
import com.bloxbean.cardano.plutus.compiler.validate.SubsetValidator;
import com.bloxbean.cardano.plutus.core.Program;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main compiler facade. Orchestrates the pipeline:
 * parse -> validate -> resolve -> PIR generate -> UPLC generate -> Program
 */
public class PlutusCompiler {

    private record ParamField(String name, PirType pirType, String javaType) {}

    private final com.bloxbean.cardano.plutus.compiler.pir.StdlibLookup stdlibLookup;

    public PlutusCompiler() {
        this(null);
    }

    public PlutusCompiler(com.bloxbean.cardano.plutus.compiler.pir.StdlibLookup stdlibLookup) {
        this.stdlibLookup = stdlibLookup;
    }

    /**
     * Compile Java source code to a UPLC Program.
     */
    public CompileResult compile(String javaSource) {
        // 1. Parse with JavaParser
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaSource);
        } catch (Exception e) {
            throw new CompilerException("Failed to parse Java source: " + e.getMessage());
        }

        // 2. Validate subset
        var subsetValidator = new SubsetValidator();
        var diagnostics = new ArrayList<>(subsetValidator.validate(cu));
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 3. Find @Validator or @MintingPolicy class
        var validatorClass = findAnnotatedClass(cu);
        boolean isMinting = isAnnotatedMinting(validatorClass);

        // 4. Register ledger types, then user-defined record types and sealed interfaces
        var typeResolver = new TypeResolver();
        com.bloxbean.cardano.plutus.compiler.resolve.LedgerTypeRegistry.registerAll(typeResolver);
        for (var type : cu.findAll(RecordDeclaration.class)) {
            typeResolver.registerRecord(type);
        }
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() && !type.getPermittedTypes().isEmpty()) {
                typeResolver.registerSealedInterface(type);
            }
        }

        // 5. Detect @Param fields (in declaration order)
        var paramFields = findParamFields(validatorClass, typeResolver);

        // 6. Find @Entrypoint method
        var entrypointMethod = findEntrypoint(validatorClass);

        // 7. Set up symbol table with method parameters
        var symbolTable = new SymbolTable();

        // Register @Param fields in global scope so they're available as variables
        for (var pf : paramFields) {
            symbolTable.define(pf.name, pf.pirType);
        }

        // Register all methods (helpers + entrypoint) in the symbol table with their types
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic()) {
                var mType = computeMethodType(method, typeResolver);
                symbolTable.define(method.getNameAsString(), mType);
            }
        }

        // 8. Generate PIR for helper methods (non-entrypoint static methods)
        var pirGenerator = new PirGenerator(typeResolver, symbolTable, stdlibLookup);
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic() && method.getAnnotationByName("Entrypoint").isEmpty()) {
                var helperPir = pirGenerator.generateMethod(method);
                var mType = computeMethodType(method, typeResolver);
                symbolTable.defineMethod(method.getNameAsString(), mType, helperPir);
            }
        }

        // 9. Generate PIR for the entrypoint method
        var validateFn = pirGenerator.generateMethod(entrypointMethod);

        // 10. Wrap helper methods as Let bindings around the entrypoint
        // Methods must be wrapped inner-first so earlier methods are in outer scope
        PirTerm body = validateFn;
        var methods = new ArrayList<>(symbolTable.allMethods());
        for (int i = methods.size() - 1; i >= 0; i--) {
            var mi = methods.get(i);
            body = new PirTerm.Let(mi.name(), mi.body(), body);
        }

        // 11. Wrap entrypoint for on-chain
        var wrapper = new ValidatorWrapper();
        int paramCount = entrypointMethod.getParameters().size();
        PirTerm wrappedTerm;
        if (isMinting) {
            wrappedTerm = wrapper.wrapMintingPolicy(body);
        } else {
            wrappedTerm = wrapper.wrapSpendingValidator(body, paramCount);
        }

        // 12. Wrap with outer param lambdas (last param innermost, first param outermost)
        for (int i = paramFields.size() - 1; i >= 0; i--) {
            var pf = paramFields.get(i);
            var rawName = pf.name + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), pf.pirType);
            wrappedTerm = new PirTerm.Lam(rawName, new PirType.DataType(),
                    new PirTerm.Let(pf.name, decoded, wrappedTerm));
        }

        // 13. Lower to UPLC
        var uplcGenerator = new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(wrappedTerm);

        // 14. Optimize UPLC
        var optimizer = new UplcOptimizer();
        uplcTerm = optimizer.optimize(uplcTerm);

        // 15. Create Program (version 1.1.0 for V3)
        var program = Program.plutusV3(uplcTerm);

        // 16. Build ParamInfo list
        var paramInfos = paramFields.stream()
                .map(pf -> new CompileResult.ParamInfo(pf.name, pf.javaType))
                .toList();

        return new CompileResult(program, diagnostics, paramInfos);
    }

    /**
     * Compile a validator from a source file.
     */
    public CompileResult compile(Path sourceFile) throws IOException {
        return compile(Files.readString(sourceFile));
    }

    /**
     * Compile a validator from a source file.
     */
    public CompileResult compile(File sourceFile) throws IOException {
        return compile(sourceFile.toPath());
    }

    /**
     * Compile a PIR expression directly to a UPLC Program (for testing).
     */
    public Program compilePirToProgram(PirTerm pirTerm) {
        var uplcGenerator = new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(pirTerm);
        return Program.plutusV3(uplcTerm);
    }

    private ClassOrInterfaceDeclaration findAnnotatedClass(CompilationUnit cu) {
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getAnnotationByName("Validator").isPresent() ||
                    type.getAnnotationByName("MintingPolicy").isPresent()) {
                return type;
            }
        }
        throw new CompilerException("No @Validator or @MintingPolicy class found");
    }

    private boolean isAnnotatedMinting(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("MintingPolicy").isPresent();
    }

    private MethodDeclaration findEntrypoint(ClassOrInterfaceDeclaration cls) {
        for (var method : cls.getMethods()) {
            if (method.getAnnotationByName("Entrypoint").isPresent()) {
                return method;
            }
        }
        throw new CompilerException("No @Entrypoint method found in " + cls.getNameAsString());
    }

    private List<ParamField> findParamFields(ClassOrInterfaceDeclaration cls, TypeResolver typeResolver) {
        var result = new ArrayList<ParamField>();
        for (var field : cls.getFields()) {
            if (field.getAnnotationByName("Param").isPresent()) {
                for (var variable : field.getVariables()) {
                    var name = variable.getNameAsString();
                    var pirType = typeResolver.resolve(field.getCommonType());
                    var javaType = field.getCommonType().asString();
                    result.add(new ParamField(name, pirType, javaType));
                }
            }
        }
        return result;
    }

    private PirType computeMethodType(MethodDeclaration method, com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver typeResolver) {
        var paramTypes = new ArrayList<PirType>();
        for (var param : method.getParameters()) {
            paramTypes.add(typeResolver.resolve(param.getType()));
        }
        PirType methodType = typeResolver.resolve(method.getType());
        for (int i = paramTypes.size() - 1; i >= 0; i--) {
            methodType = new PirType.FunType(paramTypes.get(i), methodType);
        }
        return methodType;
    }

    private boolean hasErrors(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
