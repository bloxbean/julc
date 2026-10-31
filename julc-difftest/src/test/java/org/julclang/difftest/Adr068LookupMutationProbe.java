package org.julclang.difftest;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirFormatter;
import org.julclang.stdlib.StdlibRegistry;

import java.util.List;

/**
 * F320's probe, run by {@link Adr068LookupMutationTest} in a child JVM that records a corpus: a provider built with
 * the default registry, whose caller then registers {@code lessThanByteString}'s builder under
 * {@code equalsByteString} before asking it for a specialization that calls {@code Builtins.equalsByteString} on
 * {@code {1}} and {@code {2}}. It prints the specialization's PIR.
 */
public final class Adr068LookupMutationProbe {

    static final String BUILTINS = "org.julclang.stdlib.Builtins";

    static final String LIBRARY = """
            package probe;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary public class Bytes {
                public static <T> boolean same(T ignored) {
                    byte[] a = new byte[]{1};
                    byte[] b = new byte[]{2};
                    return Builtins.equalsByteString(a, b);
                }
            }
            """;

    static final LibraryRequest.Instantiate REQUEST =
            new LibraryRequest.Instantiate("probe.Bytes.same", List.of(new LibraryType.Reference("Int", List.of())));

    private Adr068LookupMutationProbe() {
    }

    public static void main(String[] args) {
        var registry = StdlibRegistry.defaultRegistry();
        var provider = new JavaLibraryProvider(List.of(LIBRARY), registry, new CompilerOptions().setLogger(m -> { }));
        registry.register(BUILTINS, "equalsByteString", registry.lookupBuilder(BUILTINS, "lessThanByteString").orElseThrow(),
                registry.requirements(BUILTINS, "equalsByteString"));
        var imports = provider.materialize(List.<LibraryRequest>of(REQUEST));
        imports.definitions().values().forEach(term -> System.out.println(PirFormatter.format(term)));
    }
}
