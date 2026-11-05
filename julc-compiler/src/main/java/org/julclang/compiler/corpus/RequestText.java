package org.julclang.compiler.corpus;

import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A lossless text form of a generic specialization request ({@link LibraryRequest.Instantiate}: its symbol, type
 * arguments and producer types), so that ADR-068 M0's corpus can record it and the ledger can replay it (D8, I5).
 * The form is an S-expression: {@code (instantiate "sym" (args ref…) (producers (producer "name" type)…))}, a
 * reference {@code (ref "name" variable ref…)}, and a {@link PirType} by its record name, such as
 * {@code (List (Integer))} or {@code (Record "r" (field "f" (Data)))}.
 */
public final class RequestText {

    private RequestText() {
    }

    public static String encode(LibraryRequest.Instantiate request) {
        var text = new StringBuilder("(instantiate ").append(quote(request.symbol())).append(" (args");
        for (var argument : request.typeArguments()) text.append(' ').append(reference(argument));
        text.append(") (producers");
        for (var entry : request.producerTypes().entrySet()) {
            text.append(" (producer ").append(quote(entry.getKey())).append(' ').append(type(entry.getValue())).append(')');
        }
        return text.append("))").toString();
    }

    public static LibraryRequest.Instantiate decode(String text) {
        var node = new Parser(text).parse();
        var items = node.list("instantiate");
        String symbol = items.get(1).string();
        var arguments = new ArrayList<LibraryType.Reference>();
        for (var a : items.get(2).list("args").subList(1, items.get(2).list("args").size())) arguments.add(reference(a));
        var producers = new LinkedHashMap<String, PirType>();
        var producerItems = items.get(3).list("producers");
        for (var p : producerItems.subList(1, producerItems.size())) {
            var entry = p.list("producer");
            producers.put(entry.get(1).string(), type(entry.get(2)));
        }
        return new LibraryRequest.Instantiate(symbol, arguments, producers);
    }

    static String reference(LibraryType.Reference reference) {
        var text = new StringBuilder("(ref ").append(quote(reference.name())).append(' ').append(reference.variable());
        for (var argument : reference.arguments()) text.append(' ').append(reference(argument));
        return text.append(')').toString();
    }

    static String type(PirType type) {
        return switch (type) {
            case PirType.IntegerType t -> "(Integer)";
            case PirType.ByteStringType t -> "(ByteString)";
            case PirType.StringType t -> "(String)";
            case PirType.BoolType t -> "(Bool)";
            case PirType.UnitType t -> "(Unit)";
            case PirType.DataType t -> "(Data)";
            case PirType.NativeValueType t -> "(NativeValue)";
            case PirType.NativeG1Type t -> "(NativeG1)";
            case PirType.NativeG2Type t -> "(NativeG2)";
            case PirType.NativeMlResultType t -> "(NativeMlResult)";
            case PirType.NativeListType t -> "(NativeList " + type(t.elemType()) + ")";
            case PirType.ListType t -> "(List " + type(t.elemType()) + ")";
            case PirType.PairType t -> "(Pair " + type(t.first()) + " " + type(t.second()) + ")";
            case PirType.MapType t -> "(Map " + type(t.keyType()) + " " + type(t.valueType()) + ")";
            case PirType.OptionalType t -> "(Optional " + type(t.elemType()) + ")";
            case PirType.ArrayType t -> "(Array " + type(t.elemType()) + ")";
            case PirType.FunType t -> "(Fun " + type(t.paramType()) + " " + type(t.returnType()) + ")";
            case PirType.RecordType t -> "(Record " + quote(t.name()) + fields(t.fields()) + ")";
            case PirType.SumType t -> {
                var text = new StringBuilder("(Sum ").append(quote(t.name()));
                for (var c : t.constructors()) {
                    text.append(" (ctor ").append(quote(c.name())).append(' ').append(c.tag()).append(fields(c.fields())).append(')');
                }
                yield text.append(')').toString();
            }
            case PirType.NamedTypeRef t -> "(Ref " + quote(t.stableId()) + " " + quote(t.name()) + " " + t.kind().name() + ")";
        };
    }

    private static String fields(List<PirType.Field> fields) {
        var text = new StringBuilder();
        for (var f : fields) text.append(" (field ").append(quote(f.name())).append(' ').append(type(f.type())).append(')');
        return text.toString();
    }

    private static LibraryType.Reference reference(Node node) {
        var items = node.list("ref");
        var arguments = new ArrayList<LibraryType.Reference>();
        for (var a : items.subList(3, items.size())) arguments.add(reference(a));
        return new LibraryType.Reference(items.get(1).string(), arguments, Boolean.parseBoolean(items.get(2).atom()));
    }

    private static PirType type(Node node) {
        var items = node.items();
        String head = items.getFirst().atom();
        return switch (head) {
            case "Integer" -> new PirType.IntegerType();
            case "ByteString" -> new PirType.ByteStringType();
            case "String" -> new PirType.StringType();
            case "Bool" -> new PirType.BoolType();
            case "Unit" -> new PirType.UnitType();
            case "Data" -> new PirType.DataType();
            case "NativeValue" -> new PirType.NativeValueType();
            case "NativeG1" -> new PirType.NativeG1Type();
            case "NativeG2" -> new PirType.NativeG2Type();
            case "NativeMlResult" -> new PirType.NativeMlResultType();
            case "NativeList" -> new PirType.NativeListType(type(items.get(1)));
            case "List" -> new PirType.ListType(type(items.get(1)));
            case "Pair" -> new PirType.PairType(type(items.get(1)), type(items.get(2)));
            case "Map" -> new PirType.MapType(type(items.get(1)), type(items.get(2)));
            case "Optional" -> new PirType.OptionalType(type(items.get(1)));
            case "Array" -> new PirType.ArrayType(type(items.get(1)));
            case "Fun" -> new PirType.FunType(type(items.get(1)), type(items.get(2)));
            case "Record" -> new PirType.RecordType(items.get(1).string(), parseFields(items.subList(2, items.size())));
            case "Sum" -> {
                var constructors = new ArrayList<PirType.Constructor>();
                for (var c : items.subList(2, items.size())) {
                    var ci = c.list("ctor");
                    constructors.add(new PirType.Constructor(ci.get(1).string(), Integer.parseInt(ci.get(2).atom()),
                            parseFields(ci.subList(3, ci.size()))));
                }
                yield new PirType.SumType(items.get(1).string(), constructors);
            }
            case "Ref" -> new PirType.NamedTypeRef(items.get(1).string(), items.get(2).string(),
                    PirType.NamedKind.valueOf(items.get(3).atom()));
            default -> throw new IllegalArgumentException("unknown PIR type " + head);
        };
    }

    private static List<PirType.Field> parseFields(List<Node> nodes) {
        var fields = new ArrayList<PirType.Field>();
        for (var f : nodes) {
            var fi = f.list("field");
            fields.add(new PirType.Field(fi.get(1).string(), type(fi.get(2))));
        }
        return fields;
    }

    static String quote(String s) {
        var text = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') text.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) text.append(String.format("\\u%04x", (int) c));
            else text.append(c);
        }
        return text.append('"').toString();
    }

    /** A parsed S-expression: an atom, a string, or a list. */
    private record Node(String atom, String string, List<Node> items) {
        @Override
        public String atom() {
            if (atom == null) throw new IllegalArgumentException("expected an atom");
            return atom;
        }

        @Override
        public String string() {
            if (string == null) throw new IllegalArgumentException("expected a string");
            return string;
        }

        @Override
        public List<Node> items() {
            if (items == null || items.isEmpty()) throw new IllegalArgumentException("expected a list");
            return items;
        }

        List<Node> list(String head) {
            var list = items();
            if (!head.equals(list.getFirst().atom)) throw new IllegalArgumentException("expected (" + head + " …)");
            return list;
        }
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            var node = node();
            skip();
            if (at != text.length()) throw new IllegalArgumentException("trailing text at " + at);
            return node;
        }

        private Node node() {
            skip();
            char c = text.charAt(at);
            if (c == '(') {
                at++;
                var items = new ArrayList<Node>();
                while (true) {
                    skip();
                    if (text.charAt(at) == ')') {
                        at++;
                        return new Node(null, null, items);
                    }
                    items.add(node());
                }
            }
            if (c == '"') {
                at++;
                var s = new StringBuilder();
                while (text.charAt(at) != '"') {
                    char d = text.charAt(at++);
                    if (d == '\\') {
                        char e = text.charAt(at++);
                        if (e == 'u') {
                            s.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                            at += 4;
                        } else {
                            s.append(e);
                        }
                    } else {
                        s.append(d);
                    }
                }
                at++;
                return new Node(null, s.toString(), null);
            }
            int start = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at)) && text.charAt(at) != '(' && text.charAt(at) != ')') at++;
            return new Node(text.substring(start, at), null, null);
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }
    }
}
