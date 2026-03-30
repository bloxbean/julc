package com.bloxbean.julc.cli.repl;

import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JLine3 tab-completion for the REPL.
 * <p>
 * Completes meta-commands, stdlib class names, stdlib methods, and ledger types.
 * Methods are discovered from two sources:
 * <ul>
 *   <li>{@link StdlibRegistry} — PIR-registered builtins (Builtins, ListsLib HOFs)</li>
 *   <li>Stdlib source files on classpath — source-compiled libraries (MapLib, MathLib, etc.)</li>
 * </ul>
 */
public final class ReplCompleter implements Completer {

    private final List<String> metaCommands = List.of(
            ":help", ":h", ":quit", ":q", ":exit", ":libs", ":methods", ":doc",
            ":type", ":uplc", ":pir", ":budget", ":import", ":reset", ":clear"
    );

    private final List<String> classNames;
    private final Map<String, Set<String>> methodsByClass;
    private final List<String> ledgerTypes;

    /** Matches "public static <returnType> methodName(" in Java source. */
    private static final Pattern STATIC_METHOD_PATTERN =
            Pattern.compile("public\\s+static\\s+\\S+\\s+(\\w+)\\s*\\(");

    public ReplCompleter() {
        var registry = StdlibRegistry.defaultRegistry();

        // Collect class simple names from stdlib FQCNs
        var names = new ArrayList<String>();
        for (String fqcn : StdlibRegistry.stdlibClassFqcns()) {
            names.add(fqcn.substring(fqcn.lastIndexOf('.') + 1));
        }
        this.classNames = List.copyOf(names);

        // Collect methods per class — merge StdlibRegistry + source-scanned methods
        this.methodsByClass = new LinkedHashMap<>();

        // 1. From StdlibRegistry (Builtins, ListsLib HOFs, etc.)
        for (String name : classNames) {
            var methods = registry.methodsForClass(name);
            if (!methods.isEmpty()) {
                methodsByClass.computeIfAbsent(name, k -> new TreeSet<>()).addAll(methods);
            }
        }

        // 2. From stdlib source files on classpath (MapLib, MathLib, ByteStringLib, etc.)
        var librarySources = LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        for (var entry : librarySources.entrySet()) {
            String className = entry.getKey();
            String source = entry.getValue();
            Set<String> methods = extractPublicStaticMethods(source);
            if (!methods.isEmpty()) {
                methodsByClass.computeIfAbsent(className, k -> new TreeSet<>()).addAll(methods);
            }
        }

        // Common ledger types
        this.ledgerTypes = List.of(
                "TxInfo", "TxOut", "TxOutRef", "TxInInfo", "ScriptContext",
                "Address", "Credential", "Value", "OutputDatum",
                "Interval", "IntervalBound", "IntervalBoundType",
                "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId",
                "TokenName", "DatumHash", "TxId",
                "ScriptInfo", "ScriptPurpose", "Vote", "DRep", "Voter",
                "TxCert", "GovernanceAction", "ProposalProcedure"
        );
    }

    /**
     * Extract public static method names from a Java source string.
     */
    static Set<String> extractPublicStaticMethods(String source) {
        var methods = new TreeSet<String>();
        Matcher m = STATIC_METHOD_PATTERN.matcher(source);
        while (m.find()) {
            methods.add(m.group(1));
        }
        return methods;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        // JLine compares candidates against line.word(), so we use that for matching.
        String word = line.word();

        // Meta-command completion: word starts with ":"
        if (word.startsWith(":")) {
            for (String mc : metaCommands) {
                if (mc.startsWith(word)) {
                    candidates.add(new Candidate(mc));
                }
            }
            return;
        }

        // Check if previous word in the line is a meta-command that takes a class arg
        List<String> words = line.words();
        int wordIndex = line.wordIndex();
        if (wordIndex > 0) {
            String prevWord = words.get(wordIndex - 1);
            if (prevWord.equals(":methods") || prevWord.equals(":type")
                    || prevWord.equals(":uplc") || prevWord.equals(":pir")) {
                addClassCompletions(word, candidates);
                return;
            }
            if (prevWord.equals(":doc")) {
                addDocCompletions(word, candidates);
                return;
            }
        }

        // ClassName.method completion: word contains "."
        if (addMethodCompletions(word, candidates)) {
            return;
        }

        // General identifier completion: class names + ledger types
        if (!word.isEmpty()) {
            addClassCompletions(word, candidates);
            addLedgerTypeCompletions(word, candidates);
        }
    }

    private void addClassCompletions(String prefix, List<Candidate> candidates) {
        for (String name : classNames) {
            if (name.toLowerCase().startsWith(prefix.toLowerCase())) {
                candidates.add(new Candidate(name));
            }
        }
    }

    private void addDocCompletions(String word, List<Candidate> candidates) {
        if (!addMethodCompletions(word, candidates)) {
            addClassCompletions(word, candidates);
        }
    }

    /**
     * Complete "ClassName.methodPrefix" patterns. Returns true if a class match was found.
     */
    private boolean addMethodCompletions(String word, List<Candidate> candidates) {
        int dotPos = word.lastIndexOf('.');
        if (dotPos < 0) return false;

        String className = word.substring(0, dotPos);
        String methodPrefix = word.substring(dotPos + 1);
        var methods = methodsByClass.get(className);
        if (methods == null) return false;

        for (String method : methods) {
            if (method.toLowerCase().startsWith(methodPrefix.toLowerCase())) {
                candidates.add(new Candidate(
                        className + "." + method,
                        method, null, null, null, null, false));
            }
        }
        return true;
    }

    private void addLedgerTypeCompletions(String prefix, List<Candidate> candidates) {
        for (String type : ledgerTypes) {
            if (type.toLowerCase().startsWith(prefix.toLowerCase())) {
                candidates.add(new Candidate(type));
            }
        }
    }

    /** Exposed for testing: the class names available for completion. */
    List<String> getClassNames() {
        return classNames;
    }

    /** Exposed for testing: methods available for a given class. */
    Set<String> getMethodsForClass(String className) {
        return methodsByClass.getOrDefault(className, Set.of());
    }

    /** Exposed for testing: meta-command list. */
    List<String> getMetaCommands() {
        return metaCommands;
    }
}
