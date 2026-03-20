package com.bloxbean.julc.cli.cmd.uplc;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.julc.cli.cmd.EvalCommand;
import com.bloxbean.julc.cli.output.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "inspect", description = "Inspect UPLC AST structure")
public class UplcInspectCommand implements Runnable {

    @Parameters(index = "0", description = "UPLC file (.uplc, .flat, or .plutus)")
    private Path file;

    @Override
    public void run() {
        try {
            Program program = EvalCommand.loadProgram(file);
            System.out.println("Program " + program.versionString());
            printAst(program.term(), 1);
        } catch (Exception e) {
            System.err.println(AnsiColors.red("Error: " + e.getMessage()));
            System.exit(1);
        }
    }

    private static void printAst(Term term, int depth) {
        String indent = "  ".repeat(depth);
        switch (term) {
            case Term.Lam lam -> {
                System.out.println(indent + "Lam " + lam.paramName());
                printAst(lam.body(), depth + 1);
            }
            case Term.Apply app -> {
                System.out.println(indent + "Apply");
                printAst(app.function(), depth + 1);
                printAst(app.argument(), depth + 1);
            }
            case Term.Force f -> {
                System.out.println(indent + "Force");
                printAst(f.term(), depth + 1);
            }
            case Term.Delay d -> {
                System.out.println(indent + "Delay");
                printAst(d.term(), depth + 1);
            }
            case Term.Var v -> System.out.println(indent + "Var " + v.name());
            case Term.Const c -> System.out.println(indent + "Const " + c.value());
            case Term.Builtin b -> System.out.println(indent + "Builtin " + b.fun());
            case Term.Constr constr -> {
                System.out.println(indent + "Constr " + constr.tag());
                for (var arg : constr.fields()) {
                    printAst(arg, depth + 1);
                }
            }
            case Term.Case cas -> {
                System.out.println(indent + "Case");
                printAst(cas.scrutinee(), depth + 1);
                for (int i = 0; i < cas.branches().size(); i++) {
                    System.out.println(indent + "  branch " + i + ":");
                    printAst(cas.branches().get(i), depth + 2);
                }
            }
            case Term.Error e -> System.out.println(indent + "Error");
        }
    }
}
