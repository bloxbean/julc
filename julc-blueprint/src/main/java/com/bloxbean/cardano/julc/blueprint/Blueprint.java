package com.bloxbean.cardano.julc.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CIP-57 blueprint model.
 */
public record Blueprint(Preamble preamble, List<ValidatorEntry> validators,
                        Map<String, SchemaGenerator.Schema> definitions) {

    public record Preamble(String title, String version, String plutusVersion, Compiler compiler) {}
    public record Compiler(String name, String version) {}

    /** A purpose-qualified CIP-57 datum, redeemer, or compile-time parameter. */
    public record Argument(
            String title,
            String purpose,
            SchemaGenerator.Schema schema) {
        public Argument {
            title = Objects.requireNonNull(title, "title");
            purpose = Objects.requireNonNull(purpose, "purpose");
            schema = Objects.requireNonNull(schema, "schema");
        }
    }

    public record ValidatorEntry(
            String title,
            String compiledCode,
            String hash,
            int sizeBytes,
            Argument datum,
            Argument redeemer,
            List<Argument> parameters) {
        public ValidatorEntry {
            parameters = parameters == null ? null : List.copyOf(parameters);
        }
    }

    /**
     * Serialize to JSON.
     */
    public String toJson() {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"$schema\": ")
                .append(jsonStr("https://cips.cardano.org/cips/cip57/schemas/plutus-blueprint.json"))
                .append(",\n");
        sb.append("  \"preamble\": {\n");
        sb.append("    \"title\": ").append(jsonStr(preamble.title())).append(",\n");
        sb.append("    \"version\": ").append(jsonStr(preamble.version())).append(",\n");
        sb.append("    \"plutusVersion\": ").append(jsonStr(preamble.plutusVersion())).append(",\n");
        sb.append("    \"compiler\": {\n");
        sb.append("      \"name\": ").append(jsonStr(preamble.compiler().name())).append(",\n");
        sb.append("      \"version\": ").append(jsonStr(preamble.compiler().version())).append("\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"validators\": [\n");
        for (int i = 0; i < validators.size(); i++) {
            var v = validators.get(i);
            sb.append("    {\n");
            sb.append("      \"title\": ").append(jsonStr(v.title()));

            // datum
            if (v.datum() != null) {
                sb.append(",\n");
                sb.append("      \"datum\": ");
                writeArgument(sb, v.datum(), 3);
            }

            // redeemer
            if (v.redeemer() != null) {
                sb.append(",\n");
                sb.append("      \"redeemer\": ");
                writeArgument(sb, v.redeemer(), 3);
            }

            // parameters
            if (v.parameters() != null && !v.parameters().isEmpty()) {
                sb.append(",\n");
                sb.append("      \"parameters\": [\n");
                for (int j = 0; j < v.parameters().size(); j++) {
                    var p = v.parameters().get(j);
                    sb.append("        ");
                    writeArgument(sb, p, 4);
                    if (j < v.parameters().size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append("      ]");
            }

            sb.append(",\n");
            sb.append("      \"compiledCode\": ").append(jsonStr(v.compiledCode())).append(",\n");
            sb.append("      \"hash\": ").append(jsonStr(v.hash())).append("\n");
            sb.append("    }");
            if (i < validators.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]");

        // definitions
        if (definitions != null && !definitions.isEmpty()) {
            sb.append(",\n");
            sb.append("  \"definitions\": {\n");
            var keys = new java.util.ArrayList<>(definitions.keySet());
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                var schema = definitions.get(key);
                sb.append("    ").append(jsonStr(key)).append(": ");
                writeSchema(sb, schema, 4);
                if (i < keys.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  }");
        }

        sb.append("\n}\n");
        return sb.toString();
    }

    private static void writeArgument(StringBuilder sb, Argument argument, int indent) {
        String pad = "  ".repeat(indent);
        sb.append("{\n");
        sb.append(pad).append("  \"title\": ").append(jsonStr(argument.title())).append(",\n");
        sb.append(pad).append("  \"purpose\": ").append(jsonStr(argument.purpose())).append(",\n");
        sb.append(pad).append("  \"schema\": ");
        writeSchema(sb, argument.schema(), indent + 1);
        sb.append('\n').append(pad).append("}");
    }

    private static void writeSchema(StringBuilder sb, SchemaGenerator.Schema schema, int indent) {
        String pad = "  ".repeat(indent);
        sb.append("{\n");

        boolean needComma = false;

        if (schema.title() != null) {
            sb.append(pad).append("  \"title\": ").append(jsonStr(schema.title()));
            needComma = true;
        }

        if (schema.description() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"description\": ").append(jsonStr(schema.description()));
        }

        if (schema.dataType() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"dataType\": ").append(jsonStr(schema.dataType()));
        }

        if (schema.index() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"index\": ").append(schema.index());
        }

        if (schema.fields() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"fields\": [");
            if (!schema.fields().isEmpty()) {
                sb.append('\n');
                for (int i = 0; i < schema.fields().size(); i++) {
                    var f = schema.fields().get(i);
                    sb.append(pad).append("    ");
                    writeSchema(sb, f, indent + 2);
                    if (i < schema.fields().size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append(pad).append("  ]");
            } else {
                sb.append("]");
            }
        }

        if (schema.anyOf() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"anyOf\": [\n");
            for (int i = 0; i < schema.anyOf().size(); i++) {
                sb.append(pad).append("    ");
                writeSchema(sb, schema.anyOf().get(i), indent + 2);
                if (i < schema.anyOf().size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad).append("  ]");
        }

        if (schema.items() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"items\": ");
            writeSchema(sb, schema.items(), indent + 1);
        }

        if (schema.keys() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"keys\": ");
            writeSchema(sb, schema.keys(), indent + 1);
        }

        if (schema.values() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"values\": ");
            writeSchema(sb, schema.values(), indent + 1);
        }

        if (schema.ref() != null) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"$ref\": ").append(jsonStr(schema.ref()));
        }

        sb.append('\n');
        sb.append(pad).append("}");
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
