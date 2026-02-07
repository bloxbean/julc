package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.codegen.ValidatorWrapper;
import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.plutus.compiler.pir.PirGenerator;
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
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Main compiler facade. Orchestrates the pipeline:
 * parse -> validate -> resolve -> PIR generate -> UPLC generate -> Program
 */
public class PlutusCompiler {

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

        // 4. Register record types and sealed interfaces
        var typeResolver = new TypeResolver();
        for (var type : cu.findAll(RecordDeclaration.class)) {
            typeResolver.registerRecord(type);
        }
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() && !type.getPermittedTypes().isEmpty()) {
                typeResolver.registerSealedInterface(type);
            }
        }

        // 5. Find @Entrypoint method
        var entrypointMethod = findEntrypoint(validatorClass);

        // 6. Set up symbol table with method parameters
        var symbolTable = new SymbolTable();

        // Register all methods (helpers + entrypoint) in the symbol table with their types
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic()) {
                var mType = computeMethodType(method, typeResolver);
                symbolTable.define(method.getNameAsString(), mType);
            }
        }

        // 7. Generate PIR for helper methods (non-entrypoint static methods)
        var pirGenerator = new PirGenerator(typeResolver, symbolTable, stdlibLookup);
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic() && method.getAnnotationByName("Entrypoint").isEmpty()) {
                var helperPir = pirGenerator.generateMethod(method);
                var mType = computeMethodType(method, typeResolver);
                symbolTable.defineMethod(method.getNameAsString(), mType, helperPir);
            }
        }

        // 8. Generate PIR for the entrypoint method
        var validateFn = pirGenerator.generateMethod(entrypointMethod);

        // 9. Wrap helper methods as Let bindings around the entrypoint
        // Methods must be wrapped inner-first so earlier methods are in outer scope
        PirTerm body = validateFn;
        var methods = new ArrayList<>(symbolTable.allMethods());
        for (int i = methods.size() - 1; i >= 0; i--) {
            var mi = methods.get(i);
            body = new PirTerm.Let(mi.name(), mi.body(), body);
        }

        // 10. Wrap entrypoint for on-chain
        var wrapper = new ValidatorWrapper();
        int paramCount = entrypointMethod.getParameters().size();
        PirTerm wrappedTerm;
        if (isMinting) {
            wrappedTerm = wrapper.wrapMintingPolicy(body);
        } else {
            wrappedTerm = wrapper.wrapSpendingValidator(body, paramCount);
        }

        // 11. Lower to UPLC
        var uplcGenerator = new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(wrappedTerm);

        // 12. Optimize UPLC
        var optimizer = new UplcOptimizer();
        uplcTerm = optimizer.optimize(uplcTerm);

        // 13. Create Program (version 1.1.0 for V3)
        var program = Program.plutusV3(uplcTerm);

        return new CompileResult(program, diagnostics);
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
