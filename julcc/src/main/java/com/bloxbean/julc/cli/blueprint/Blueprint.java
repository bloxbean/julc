package com.bloxbean.julc.cli.blueprint;

import java.util.List;
import java.util.Map;

/**
 * CIP-57 blueprint model.
 */
public record Blueprint(Preamble preamble, List<ValidatorEntry> validators,
                        Map<String, SchemaGenerator.Schema> definitions) {

    public record Preamble(String title, String version, String plutusVersion, Compiler compiler) {}
    public record Compiler(String name, String version) {}

    public record ValidatorEntry(String title, String compiledCode, String hash, int sizeBytes,
                                 SchemaGenerator.Schema datum, SchemaGenerator.Schema redeemer,
                                 List<SchemaGenerator.Schema> parameters) {}

    /**
     * Serialize to JSON.
     */
    public String toJson() {
        var sb = new StringBuilder();
        sb.append("{\n");
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
                sb.append("      \"datum\": {\n");
                sb.append("        \"title\": ").append(jsonStr(v.datum().title())).append(",\n");
                sb.append("        \"schema\": {\n");
                sb.append("          \"$ref\": ").append(jsonStr(v.datum().ref())).append("\n");
                sb.append("        }\n");
                sb.append("      }");
            }

            // redeemer
            if (v.redeemer() != null) {
                sb.append(",\n");
                sb.append("      \"redeemer\": {\n");
                sb.append("        \"title\": ").append(jsonStr(v.redeemer().title())).append(",\n");
                sb.append("        \"schema\": {\n");
                sb.append("          \"$ref\": ").append(jsonStr(v.redeemer().ref())).append("\n");
                sb.append("        }\n");
                sb.append("      }");
            }

            // parameters
            if (v.parameters() != null && !v.parameters().isEmpty()) {
                sb.append(",\n");
                sb.append("      \"parameters\": [\n");
                for (int j = 0; j < v.parameters().size(); j++) {
                    var p = v.parameters().get(j);
                    sb.append("        {\n");
                    sb.append("          \"title\": ").append(jsonStr(p.title())).append(",\n");
                    sb.append("          \"schema\": {\n");
                    sb.append("            \"$ref\": ").append(jsonStr(p.ref())).append("\n");
                    sb.append("          }\n");
                    sb.append("        }");
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

        if (schema.dataType() != null && !"constructor".equals(schema.ref())) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"dataType\": ").append(jsonStr(schema.dataType()));
        }

        if ("constructor".equals(schema.ref())) {
            if (needComma) sb.append(",\n"); else needComma = true;
            sb.append(pad).append("  \"dataType\": \"constructor\"");
            sb.append(",\n");
            sb.append(pad).append("  \"index\": ").append(schema.index());
            sb.append(",\n");
            sb.append(pad).append("  \"fields\": [");
            if (schema.fields() != null && !schema.fields().isEmpty()) {
                sb.append('\n');
                for (int i = 0; i < schema.fields().size(); i++) {
                    var f = schema.fields().get(i);
                    sb.append(pad).append("    {\n");
                    sb.append(pad).append("      \"title\": ").append(jsonStr(f.title()));
                    if (f.ref() != null) {
                        sb.append(",\n");
                        sb.append(pad).append("      \"$ref\": ").append(jsonStr(f.ref()));
                    } else if (f.dataType() != null) {
                        sb.append(",\n");
                        sb.append(pad).append("      \"dataType\": ").append(jsonStr(f.dataType()));
                    }
                    sb.append('\n');
                    sb.append(pad).append("    }");
                    if (i < schema.fields().size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append(pad).append("  ]");
            } else {
                sb.append("]");
            }
            needComma = true;
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

        if (schema.ref() != null && !"constructor".equals(schema.ref())) {
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
