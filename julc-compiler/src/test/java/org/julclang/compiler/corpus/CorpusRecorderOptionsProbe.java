package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.UplcVersion;

import java.util.List;

/**
 * F313's probe, run by {@link CorpusRecorderTest} in a child JVM that records: a provider built with PV11 options,
 * whose caller then changes those options to PV10 before asking it for a specialization.
 */
public final class CorpusRecorderOptionsProbe {

    private CorpusRecorderOptionsProbe() {
    }

    public static void main(String[] args) {
        var options = new CompilerOptions();
        var provider = new JavaLibraryProvider(List.of(CorpusRecorderProbe.LIBRARY), StdlibRegistry.defaultRegistry(), options);
        options.setTarget(new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0));
        var request = new LibraryRequest.Instantiate("sample.Twice.count", List.of(new LibraryType.Reference("Int", List.of())));
        var imports = provider.materialize(List.<LibraryRequest>of(request));
        System.out.print("materialized " + imports.binding(request).type());
    }
}
