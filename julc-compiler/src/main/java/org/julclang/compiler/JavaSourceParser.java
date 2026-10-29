package org.julclang.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How JuLC reads Java source, everywhere it reads it (ADR-062 T24): Java 21, with Unicode escapes
 * translated before anything else, as javac does (JLS 21 §3.3).
 * <p>
 * javac compiles the same source for the JVM, so a reading that differs from javac's makes the
 * script differ from the code its tests run. JavaParser's default configuration leaves Unicode
 * escapes untranslated: an escape is then not a line terminator inside a comment, not the end of a
 * comment, and not the same identifier as the character it denotes.
 * <p>
 * {@link UnicodeEscapes} translates the source. It rejects the escapes javac rejects, and the shapes
 * javac reads differently from the JLS. JavaParser then parses the translated text, so it attributes
 * comments to nodes on one consistent set of positions. Afterwards every position in the result refers
 * to the source as written: node and comment ranges, token ranges (which {@link Problem} locations
 * use), and the line and column in a lexical error's message. A range that ends on an escape ends on
 * the escape's last character.
 * <p>
 * After translation, a backslash outside a literal or comment is an illegal character for javac.
 * JavaParser's lexer accepts one in an identifier, so it is rejected here.
 */
public final class JavaSourceParser {

    private static final Pattern LEXICAL_POSITION = Pattern.compile("line (\\d+), column (\\d+)");

    private JavaSourceParser() {
    }

    /** Parses a compilation unit with a new parser (a {@link JavaParser} is not thread-safe). */
    public static ParseResult<CompilationUnit> parse(String source) {
        UnicodeEscapes escapes;
        try {
            escapes = UnicodeEscapes.translate(source);
        } catch (UnicodeEscapes.IllegalSourceException e) {
            var at = e.position();
            var token = new JavaToken(new Range(at, at), JavaToken.Kind.EOF.getKind(), "", null, null);
            var problems = new ArrayList<Problem>();
            problems.add(new Problem(e.getMessage(), new TokenRange(token, token), null));
            return new ParseResult<>(null, problems, null);
        }
        var configuration = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var result = new JavaParser(configuration).parse(escapes.text());
        if (!escapes.isIdentity()) remap(result, escapes);
        rejectBackslashesOutsideLiteralsAndComments(result);
        return result;
    }

    /** As {@link #parse}, throwing a {@link ParseProblemException} on failure. */
    public static CompilationUnit parseCompilationUnit(String source) {
        var result = parse(source);
        if (!result.isSuccessful()) throw new ParseProblemException(result.getProblems());
        return result.getResult().orElseThrow();
    }

    /** Moves every position in the result from the translated text to the source as written. */
    private static void remap(ParseResult<CompilationUnit> result, UnicodeEscapes escapes) {
        Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<JavaToken> tokens = Collections.newSetFromMap(new IdentityHashMap<>());
        result.getResult().ifPresent(unit -> {
            unit.walk(nodes::add);
            nodes.addAll(unit.getAllComments());
            unit.getTokenRange().ifPresent(range -> addTokenList(range.getBegin(), tokens));
        });
        result.getCommentsCollection().ifPresent(comments -> nodes.addAll(comments.getComments()));
        for (var problem : result.getProblems()) {
            problem.getLocation().ifPresent(range -> {
                addTokenList(range.getBegin(), tokens);
                addTokenList(range.getEnd(), tokens);
            });
        }
        for (var node : nodes) node.getRange().ifPresent(range -> node.setRange(escapes.range(range)));
        for (var token : tokens) token.getRange().ifPresent(range -> token.setRange(escapes.range(range)));
        result.getProblems().replaceAll(problem -> {
            Matcher m = LEXICAL_POSITION.matcher(problem.getMessage());
            if (!m.find()) return problem;
            var at = escapes.begin(new Position(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            String message = problem.getMessage().substring(0, m.start())
                    + "line " + at.line + ", column " + at.column + problem.getMessage().substring(m.end());
            return new Problem(message, problem.getLocation().orElse(null), problem.getCause().orElse(null));
        });
    }

    /** Adds every token of the list {@code token} belongs to, each once. */
    private static void addTokenList(JavaToken token, Set<JavaToken> tokens) {
        var first = token;
        while (first.getPreviousToken().isPresent()) first = first.getPreviousToken().get();
        var t = first;
        while (t != null && tokens.add(t)) t = t.getNextToken().orElse(null);
    }

    private static void rejectBackslashesOutsideLiteralsAndComments(ParseResult<CompilationUnit> result) {
        var range = result.getResult().flatMap(Node::getTokenRange);
        if (range.isEmpty()) return;
        var found = new ArrayList<Problem>();
        var first = range.get().getBegin();
        while (first.getPreviousToken().isPresent()) first = first.getPreviousToken().get();
        for (var t = first; t != null; t = t.getNextToken().orElse(null)) {
            var category = t.getCategory();
            if (t.getText().indexOf('\\') >= 0 && !category.isComment() && !category.isLiteral()) {
                found.add(new Problem("Illegal character '\\' in " + t.getText() + ": outside a literal or"
                        + " comment, a backslash is not Java. The backslash a Unicode escape produces starts no"
                        + " further escape (JLS 21 §3.3)", new TokenRange(t, t), null));
            }
        }
        result.getProblems().addAll(found);
    }
}
