package org.julclang.wasm;

import org.julclang.tools.model.MockTransaction.DataInput;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Build-time declarations and exhaustive codec-schema check over transport records. */
public final class TypeScriptDeclarations {
    private final Map<String, String> declarations = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        var generator = new TypeScriptDeclarations();
        Path models = Path.of(args[0], "julc-tools/src/main/java/org/julclang/tools/model");
        try (var files = Files.list(models)) {
            for (var file : files.sorted().toList()) {
                if (file.toString().endsWith(".java")) {
                    generator.visit(Class.forName("org.julclang.tools.model."
                            + file.getFileName().toString().replace(".java", "")));
                }
            }
        }
        generator.visit(FullApi.TransactionRequest.class);
        Path out = Path.of(args[1]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, "// Generated from Java records. Do not edit.\n"
                + "export type PlutusData = {int: bigint} | {bytes: string} | {list: PlutusData[]}"
                + " | {map: {k: PlutusData; v: PlutusData}[]} | {constructor: bigint; fields: PlutusData[]};\n"
                + String.join("\n", generator.declarations.values()));
    }

    private void visit(Class<?> type) {
        if (type.isRecord()) {
            JsMarshalling.schema(type); // Fails the normal build for any unhandled generic integer position.
            render(type);
        }
        for (var nested : type.getDeclaredClasses()) visit(nested);
    }

    private String render(Type type) {
        if (type == long.class || type == Long.class || type == BigInteger.class) return "bigint";
        if (type == int.class || type == Integer.class) return "number";
        if (type == String.class) return "string";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) return "Array<" + render(p.getActualTypeArguments()[0]) + ">";
            if (p.getRawType() == Map.class && p.getActualTypeArguments()[0] == String.class) {
                return "Record<string, " + render(p.getActualTypeArguments()[1]) + ">";
            }
        }
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) return "Array<" + render(cls.getComponentType()) + ">";
            if (cls.isRecord()) {
                String name = cls.getName().substring(cls.getPackageName().length() + 1).replace('$', '_');
                if (!declarations.containsKey(name)) {
                    declarations.put(name, "");
                    var text = new StringBuilder("export interface " + name + " {\n");
                    for (var field : cls.getRecordComponents()) {
                        text.append("  ").append(field.getName()).append(": ").append(render(field.getGenericType()));
                        if (!field.getType().isPrimitive()) text.append(" | null");
                        text.append(";\n");
                    }
                    declarations.put(name, text.append("}\n").toString());
                }
                return cls == DataInput.class ? "(" + name + " | PlutusData)" : name;
            }
        }
        throw new IllegalArgumentException("No TypeScript declaration for " + type);
    }
}
