import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Build-time tool (runs on a JDK): writes the embedded platform blob read by spike.EmbeddedFileManager.
 *
 *   java tools/MakePlatformBlob.java OUT.bin STRIP SELECTION
 *     STRIP      none | code (drop method bodies) | api (drop bodies and private fields/methods, like ct.sym)
 *     SELECTION  all       every class file of java.base
 *                exported  module-info plus the classes of java.base's unqualified exported packages
 *                list:FILE the module-relative paths listed in FILE (e.g. java.base/java/lang/Object.class)
 *     then any number of jar:PATH  every class file of that jar, served on javac's class path ("classpath/..." entries)
 *
 * Format: gzip( int count, then per entry: UTF path, int length, bytes ), entries sorted by path.
 */
public class MakePlatformBlob {
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        String strip = args[1];
        String selection = args[2];
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path base = jrt.getPath("/modules/java.base");
        Set<String> paths = new TreeSet<>();
        if (selection.startsWith("list:")) {
            for (String line : Files.readAllLines(Path.of(selection.substring(5)))) {
                if (!line.isBlank()) paths.add(line.strip());
            }
        } else {
            ModuleDescriptor md = ModuleLayer.boot().findModule("java.base").orElseThrow().getDescriptor();
            Set<String> exported = md.exports().stream().filter(e -> !e.isQualified())
                    .map(e -> e.source().replace('.', '/')).collect(Collectors.toSet());
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String rel = base.relativize(p).toString();
                    int last = rel.lastIndexOf('/');
                    String pkg = last < 0 ? "" : rel.substring(0, last);
                    if (selection.equals("all") || rel.equals("module-info.class") || exported.contains(pkg)) {
                        paths.add("java.base/" + rel);
                    }
                });
            }
        }
        java.util.Map<String, byte[]> jarEntries = new java.util.TreeMap<>();
        for (int a = 3; a < args.length; a++) {
            if (!args[a].startsWith("jar:")) throw new IllegalArgumentException(args[a]);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(args[a].substring(4))) {
                for (var e : java.util.Collections.list(zip.entries())) {
                    String n = e.getName();
                    if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")) {
                        jarEntries.put("classpath/" + n, zip.getInputStream(e).readAllBytes());
                    }
                }
            }
        }
        paths.addAll(jarEntries.keySet());
        long rawTotal = 0;
        long strippedTotal = 0;
        List<byte[]> datas = new ArrayList<>();
        for (String p : paths) {
            byte[] bytes = jarEntries.containsKey(p) ? jarEntries.get(p) : Files.readAllBytes(jrt.getPath("/modules/" + p));
            rawTotal += bytes.length;
            if (!strip.equals("none") && !p.endsWith("module-info.class")) {
                bytes = strip(bytes, strip.equals("api"));
            }
            strippedTotal += bytes.length;
            datas.add(bytes);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(buf))) {
            o.writeInt(paths.size());
            int i = 0;
            for (String p : paths) {
                byte[] d = datas.get(i++);
                o.writeUTF(p);
                o.writeInt(d.length);
                o.write(d);
            }
        }
        Files.write(out, buf.toByteArray());
        System.out.printf("%s: %d class files; original %d bytes; after strip=%s %d bytes; blob (gzip) %d bytes%n",
                out.getFileName(), paths.size(), rawTotal, strip, strippedTotal, buf.size());
    }

    static byte[] strip(byte[] bytes, boolean dropPrivate) {
        ClassFile cf = ClassFile.of(ClassFile.ConstantPoolSharingOption.NEW_POOL);
        ClassModel cm = cf.parse(bytes);
        ClassTransform t = ClassTransform.transformingMethods(MethodTransform.dropping(e -> e instanceof CodeModel));
        if (dropPrivate) {
            t = t.andThen(ClassTransform.dropping(e ->
                    (e instanceof MethodModel m && m.flags().has(AccessFlag.PRIVATE))
                            || (e instanceof FieldModel f && f.flags().has(AccessFlag.PRIVATE))));
        }
        return cf.transformClass(cm, t);
    }
}
