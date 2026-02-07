package com.bloxbean.cardano.plutus.compiler.desugar;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms pattern matching constructs (instanceof, switch) into PirTerm.DataMatch.
 */
public class PatternMatchDesugarer {

    private final TypeResolver typeResolver;

    public PatternMatchDesugarer(TypeResolver typeResolver) {
        this.typeResolver = typeResolver;
    }

    /**
     * Build a DataMatch from a list of branches matching on a sealed interface value.
     *
     * @param scrutinee the value being matched
     * @param sumType   the sealed interface type
     * @param branches  list of (variantName, bindingNames, bodyTerm) tuples
     * @return DataMatch PIR term
     */
    public PirTerm buildDataMatch(PirTerm scrutinee, PirType.SumType sumType,
                                   List<MatchEntry> branches) {
        // Reorder branches by constructor tag
        var orderedBranches = new ArrayList<PirTerm.MatchBranch>();
        for (var ctor : sumType.constructors()) {
            var matchEntry = branches.stream()
                    .filter(b -> b.variantName.equals(ctor.name()))
                    .findFirst();
            if (matchEntry.isPresent()) {
                orderedBranches.add(new PirTerm.MatchBranch(
                        ctor.name(), matchEntry.get().bindings, matchEntry.get().body));
            } else {
                // Default branch or error
                orderedBranches.add(new PirTerm.MatchBranch(
                        ctor.name(), List.of(), new PirTerm.Error(new PirType.UnitType())));
            }
        }
        return new PirTerm.DataMatch(scrutinee, orderedBranches);
    }

    /**
     * Build a DataMatch from chained if/else instanceof checks.
     */
    public PirTerm buildFromInstanceOfChain(PirTerm scrutinee, PirType.SumType sumType,
                                             List<InstanceOfBranch> branches, PirTerm defaultBranch) {
        var matchEntries = new ArrayList<MatchEntry>();
        for (var branch : branches) {
            matchEntries.add(new MatchEntry(branch.variantName, branch.bindings, branch.body));
        }

        // Fill missing variants with default branch
        for (var ctor : sumType.constructors()) {
            boolean found = matchEntries.stream().anyMatch(e -> e.variantName.equals(ctor.name()));
            if (!found) {
                matchEntries.add(new MatchEntry(ctor.name(), List.of(), defaultBranch));
            }
        }

        return buildDataMatch(scrutinee, sumType, matchEntries);
    }

    public record MatchEntry(String variantName, List<String> bindings, PirTerm body) {}
    public record InstanceOfBranch(String variantName, List<String> bindings, PirTerm body) {}
}
