import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Run by the pinned javac 21.0.3's own {@code java} (ADR-067 Verification 3): compiles every case directory under
 * the argument, each on its own with the in-process system compiler, and prints one line per case:
 * {@code <case>\tOK} or {@code <case>\tERROR\t<first error, one line>}. Kept free of any JuLC class, because
 * JuLC's class files target Java 25 and a Java 21 compiler cannot read them.
 */
public class Javac21Driver {
    public static void main(String[] args) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        List<Path> cases;
        try (Stream<Path> dirs = Files.list(Path.of(args[0]))) {
            cases = dirs.filter(Files::isDirectory).sorted().toList();
        }
        var out = new StringBuilder();
        for (Path dir : cases) {
            List<String> arguments = new ArrayList<>(List.of("-proc:none", "-Xlint:none", "-nowarn", "-d",
                    Files.createTempDirectory("javac21-").toString()));
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(f -> arguments.add(f.toString()));
            }
            var errors = new ByteArrayOutputStream();
            int status = compiler.run(null, null, errors, arguments.toArray(String[]::new));
            String name = dir.getFileName().toString();
            if (status == 0) {
                out.append(name).append("\tOK\n");
            } else {
                String first = errors.toString(StandardCharsets.UTF_8).lines()
                        .filter(l -> l.contains("error:")).findFirst().orElse("error: (no message)");
                out.append(name).append("\tERROR\t").append(first.substring(first.indexOf("error:") + 6).trim()).append('\n');
            }
        }
        System.out.print(out);
    }
}
