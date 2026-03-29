package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.JulcVersionProvider;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.repl.ReplCompleter;
import com.bloxbean.julc.cli.repl.ReplEngine;
import com.bloxbean.julc.cli.repl.ReplResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "repl", description = "Interactive REPL for evaluating JuLC expressions")
public class ReplCommand implements Runnable {

    @Option(names = "--no-budget", description = "Hide budget (CPU/mem) display")
    private boolean noBudget;

    @Option(names = "--no-jline", description = "Disable JLine (use basic stdin)")
    private boolean noJline;

    private static final String PROMPT = AnsiColors.bold("julc> ");
    private static final String CONTINUATION = "  ... ";

    @Override
    public void run() {
        printBanner();

        var engine = new ReplEngine();
        if (noBudget) {
            engine.setShowBudget(false);
        }

        if (noJline || System.console() == null) {
            runBasicLoop(engine);
        } else {
            try {
                runJlineLoop(engine);
            } catch (Exception e) {
                // Fallback if JLine fails (e.g., native-image issues)
                System.err.println(AnsiColors.dim("JLine unavailable, falling back to basic mode."));
                runBasicLoop(engine);
            }
        }
    }

    private void runJlineLoop(ReplEngine engine) throws IOException {
        // Ensure history directory exists
        Path historyDir = Path.of(System.getProperty("user.home"), ".julc");
        Files.createDirectories(historyDir);
        Path historyFile = historyDir.resolve("repl_history");

        var terminal = org.jline.terminal.TerminalBuilder.builder()
                .system(true)
                .build();

        // Custom parser: don't treat '.' as a word break so "MapLib." stays as one token
        var parser = new org.jline.reader.impl.DefaultParser();
        parser.setEofOnUnclosedBracket(org.jline.reader.impl.DefaultParser.Bracket.CURLY,
                org.jline.reader.impl.DefaultParser.Bracket.ROUND);

        var lineReader = org.jline.reader.LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .completer(new ReplCompleter())
                .variable(org.jline.reader.LineReader.HISTORY_FILE, historyFile)
                .option(org.jline.reader.LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();

        while (true) {
            String input;
            try {
                input = lineReader.readLine(PROMPT);
            } catch (org.jline.reader.UserInterruptException e) {
                // Ctrl+C — clear line, continue
                continue;
            } catch (org.jline.reader.EndOfFileException e) {
                // Ctrl+D — quit
                System.out.println("Goodbye!");
                return;
            }

            if (input == null) {
                System.out.println("Goodbye!");
                return;
            }

            // Multi-line: collect continuation lines if brackets are unmatched
            input = collectMultiLine(input, lineReader);

            if (isQuitCommand(input)) {
                System.out.println("Goodbye!");
                return;
            }

            processInput(engine, input);
        }
    }

    private void runBasicLoop(ReplEngine engine) {
        var reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("julc> ");
            System.out.flush();
            String input;
            try {
                input = reader.readLine();
            } catch (IOException e) {
                break;
            }

            if (input == null) {
                System.out.println("Goodbye!");
                return;
            }

            // Multi-line: collect continuation lines
            input = collectMultiLineBasic(input, reader);

            if (isQuitCommand(input)) {
                System.out.println("Goodbye!");
                return;
            }

            processInput(engine, input);
        }
    }

    private void processInput(ReplEngine engine, String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return;

        ReplResult result = engine.evaluate(trimmed);
        if (result == null) return;

        switch (result) {
            case ReplResult.Success s -> {
                System.out.println(AnsiColors.green("=> ") + s.formattedValue());
                if (!s.traces().isEmpty()) {
                    for (String trace : s.traces()) {
                        System.out.println(AnsiColors.cyan("   trace: ") + trace);
                    }
                }
                if (engine.isShowBudget() && s.budget().cpuSteps() > 0) {
                    System.out.println(AnsiColors.dim("   CPU: " +
                            String.format("%,d", s.budget().cpuSteps()) + "  Mem: " +
                            String.format("%,d", s.budget().memoryUnits())));
                }
            }
            case ReplResult.Error e -> {
                System.out.println(AnsiColors.red("Error: ") + e.message());
                if (!e.traces().isEmpty()) {
                    for (String trace : e.traces()) {
                        System.out.println(AnsiColors.cyan("   trace: ") + trace);
                    }
                }
                if (engine.isShowBudget() && e.budget().cpuSteps() > 0) {
                    System.out.println(AnsiColors.dim("   CPU: " +
                            String.format("%,d", e.budget().cpuSteps()) + "  Mem: " +
                            String.format("%,d", e.budget().memoryUnits())));
                }
            }
            case ReplResult.MetaOutput m -> System.out.println(m.text());
        }
    }

    private static boolean isQuitCommand(String input) {
        String trimmed = input.trim().toLowerCase();
        return trimmed.equals(":quit") || trimmed.equals(":q")
                || trimmed.equals(":exit") || trimmed.equals("quit") || trimmed.equals("exit");
    }

    /**
     * Collect multi-line input when brackets are unmatched (JLine mode).
     */
    private String collectMultiLine(String initial, org.jline.reader.LineReader lineReader) {
        if (!hasUnmatchedBrackets(initial)) {
            return initial;
        }
        var sb = new StringBuilder(initial);
        while (hasUnmatchedBrackets(sb.toString())) {
            try {
                String continuation = lineReader.readLine(CONTINUATION);
                if (continuation == null) break;
                sb.append("\n").append(continuation);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Collect multi-line input when brackets are unmatched (basic mode).
     */
    private String collectMultiLineBasic(String initial, BufferedReader reader) {
        if (!hasUnmatchedBrackets(initial)) {
            return initial;
        }
        var sb = new StringBuilder(initial);
        while (hasUnmatchedBrackets(sb.toString())) {
            System.out.print("  ... ");
            System.out.flush();
            try {
                String continuation = reader.readLine();
                if (continuation == null) break;
                sb.append("\n").append(continuation);
            } catch (IOException e) {
                break;
            }
        }
        return sb.toString();
    }

    static boolean hasUnmatchedBrackets(String input) {
        int curly = 0, paren = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                i++; // skip escaped char
                continue;
            }
            if (c == '"' && !inChar) inString = !inString;
            else if (c == '\'' && !inString) inChar = !inChar;
            else if (!inString && !inChar) {
                if (c == '{') curly++;
                else if (c == '}') curly--;
                else if (c == '(') paren++;
                else if (c == ')') paren--;
            }
        }
        return curly > 0 || paren > 0;
    }

    private void printBanner() {
        System.out.println(AnsiColors.bold("JuLC REPL") + " " +
                AnsiColors.dim("v" + JulcVersionProvider.VERSION));
        System.out.println("All stdlib libraries are auto-imported. " +
                "Type " + AnsiColors.cyan(":help") + " for commands, " +
                AnsiColors.cyan(":quit") + " to exit.");
        System.out.println();
    }
}
