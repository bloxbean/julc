package org.julclang.core.source;

import org.julclang.core.DeepTerms;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The source-map index walks need no Java stack per level and number nodes as before (issue #327). */
class DeepSourceMapSerializerTest {

    @Test
    void deepTermsInEveryChildPositionRoundTripOnASmallStack() {
        for (var position : DeepTerms.Position.values()) {
            var term = DeepTerms.spine(100_000, position);
            var nodes = recursiveFreeOccurrences(term);
            var positions = new IdentityHashMap<Term, SourceLocation>();
            for (int i = 0; i < nodes.size(); i += 997) {
                positions.put(nodes.get(i), new SourceLocation("Deep.java", i, 1, "n" + i));
            }
            var indexed = DeepTerms.onSmallStack(() -> SourceMapSerializer.toIndexed(positions, term));
            assertEquals(positions.size(), indexed.size(), position.toString());
            var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(Program.plutusV3(term))).term();
            var rebuilt = DeepTerms.onSmallStack(() -> SourceMapSerializer.fromIndexed(indexed, decoded));
            var decodedNodes = recursiveFreeOccurrences(decoded);
            for (int i = 0; i < nodes.size(); i += 997) {
                assertEquals(positions.get(nodes.get(i)), rebuilt.get(decodedNodes.get(i)), position + " node " + i);
            }
            assertEquals(positions.size(), rebuilt.size(), position.toString());
        }
    }

    @Test
    void numbersNodesAsTheRecursiveWalkDid() {
        var random = new Random(3274);
        for (int i = 0; i < 2_000; i++) {
            var term = DeepTerms.random(random, 1 + random.nextInt(60));
            var nodes = new ArrayList<Term>();
            recursivePreOrder(term, nodes);
            var positions = new IdentityHashMap<Term, SourceLocation>();
            var expected = new LinkedHashMap<Integer, SourceLocation>();
            for (int n = 0; n < nodes.size(); n++) {
                if (random.nextInt(3) == 0 && !positions.containsKey(nodes.get(n))) {
                    var location = new SourceLocation("R.java", n, n % 7, null);
                    positions.put(nodes.get(n), location);
                }
            }
            // The first visit of a shared node is the one recorded, as before.
            for (int n = 0; n < nodes.size(); n++) {
                var location = positions.get(nodes.get(n));
                if (location != null) expected.put(n, location);
            }
            Map<Integer, SourceLocation> indexed = SourceMapSerializer.toIndexed(positions, term);
            assertEquals(List.copyOf(expected.entrySet()), List.copyOf(indexed.entrySet()), "term " + i);
            var rebuilt = SourceMapSerializer.fromIndexed(indexed, term);
            for (var node : nodes) {
                assertSame(lastWrite(indexed, nodes, node), rebuilt.get(node), "term " + i);
            }
        }
    }

    /** The location fromIndexed leaves for {@code node}: the last index in pre-order that names it. */
    private static SourceLocation lastWrite(Map<Integer, SourceLocation> indexed, List<Term> nodes, Term node) {
        SourceLocation last = null;
        for (int n = 0; n < nodes.size(); n++) {
            if (nodes.get(n) == node && indexed.containsKey(n)) last = indexed.get(n);
        }
        return last;
    }

    /** Pre-order occurrences without recursion, so that a deep spine can be listed. */
    private static List<Term> recursiveFreeOccurrences(Term root) {
        var nodes = new ArrayList<Term>();
        var pending = new ArrayDeque<Term>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var term = pending.pop();
            nodes.add(term);
            var children = new ArrayList<Term>();
            switch (term) {
                case Term.Lam l -> children.add(l.body());
                case Term.Apply a -> {
                    children.add(a.function());
                    children.add(a.argument());
                }
                case Term.Force f -> children.add(f.term());
                case Term.Delay d -> children.add(d.term());
                case Term.Constr c -> children.addAll(c.fields());
                case Term.Case c -> {
                    children.add(c.scrutinee());
                    children.addAll(c.branches());
                }
                default -> {
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
        return nodes;
    }

    /** The recursive pre-order the walks used before, kept as the reference. */
    private static void recursivePreOrder(Term term, List<Term> out) {
        out.add(term);
        switch (term) {
            case Term.Lam l -> recursivePreOrder(l.body(), out);
            case Term.Apply a -> {
                recursivePreOrder(a.function(), out);
                recursivePreOrder(a.argument(), out);
            }
            case Term.Force f -> recursivePreOrder(f.term(), out);
            case Term.Delay d -> recursivePreOrder(d.term(), out);
            case Term.Constr c -> c.fields().forEach(field -> recursivePreOrder(field, out));
            case Term.Case c -> {
                recursivePreOrder(c.scrutinee(), out);
                c.branches().forEach(branch -> recursivePreOrder(branch, out));
            }
            default -> {
            }
        }
    }
}
