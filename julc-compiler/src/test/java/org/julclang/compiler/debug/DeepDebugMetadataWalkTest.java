package org.julclang.compiler.debug;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.debug.DebugMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #327: {@link DebugMetadataCollector}'s walk of the final UPLC visits a term of any depth on a small stack, in
 * pre-order, children left to right, numbering the occurrences as it goes.
 */
class DeepDebugMetadataWalkTest {

    static DebugMetadataCollector collector() {
        var range = new DebugMetadata.SourceRange("source:0", 0, 1, 1, 1, 1, 2);
        return DebugMetadataCollector.forSources(
                List.of(new DebugMetadataCollector.SourceText("source:0", "Deep.java", "class Deep {}\n")),
                declarations -> declarations.scope(new Object(), "scope:0", null, "source:0", range,
                        DebugMetadata.ScopeKind.METHOD, "method:0"));
    }

    record Walked(List<DebugMetadata.SuspensionPoint> points, List<DebugMetadata.LoweredBinding> lowered) {}

    static Walked walk(Term term) {
        var result = new AtomicReference<Walked>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                var points = new ArrayList<DebugMetadata.SuspensionPoint>();
                var lowered = new ArrayList<DebugMetadata.LoweredBinding>();
                collector().walk(term, new IdentityHashMap<>(), new IdentityHashMap<>(), lowered, points, new int[]{0});
                result.set(new Walked(points, lowered));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "depth-test", 256 * 1024);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    @Test
    void aDeepApplicationSpineIsWalkedInPreOrder() {
        int depth = 50_000;
        Term term = Term.const_(Constant.integer(0));
        for (int i = 0; i < depth; i++) {
            term = Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), Term.const_(Constant.integer(1))), term);
        }
        var walked = walk(term);
        // Each level is Apply, Apply, Builtin, Const, then the next level; the innermost Const ends it.
        assertEquals(4 * depth + 1, walked.points().size());
        for (int i = 0; i < walked.points().size(); i++) {
            var point = walked.points().get(i);
            assertEquals(i, point.occurrenceId());
            String expected = i == 4 * depth ? DebugMetadata.termKind(Term.const_(Constant.integer(0)))
                    : DebugMetadata.termKind(switch (i % 4) {
                        case 0, 1 -> Term.apply(Term.error(), Term.error());
                        case 2 -> Term.builtin(DefaultFun.AddInteger);
                        default -> Term.const_(Constant.integer(1));
                    });
            assertEquals(expected, point.termKind(), "occurrence " + i);
        }
    }

    @Test
    void lambdasExtendTheScopeOfTheirBodiesOnly() {
        // (\a -> \b -> a) (\c -> c): b's body sees b then a; the argument's lambda sees nothing of a or b.
        var term = Term.apply(Term.lam("a", Term.lam("b", Term.var(2))), Term.lam("c", Term.var(1)));
        var walked = walk(term);
        assertEquals(List.of("lowered:generated:1", "lowered:generated:2", "lowered:generated:4"),
                walked.lowered().stream().map(DebugMetadata.LoweredBinding::id).toList());
        var expected = walked.points().stream().map(DebugMetadata.SuspensionPoint::expectedBinderIds).toList();
        assertEquals(List.of(
                List.of(),
                List.of(),
                List.of("lowered:generated:1"),
                List.of("lowered:generated:2", "lowered:generated:1"),
                List.of(),
                List.of("lowered:generated:4")), expected);
        assertNull(walked.points().getFirst().range());
    }

    @Test
    void aDeepLambdaSpineCarriesItsBindersInward() {
        int depth = 2_000;
        Term term = Term.var(depth);
        for (int i = depth - 1; i >= 0; i--) term = Term.lam("x" + i, term);
        var walked = walk(term);
        assertEquals(depth, walked.lowered().size());
        var innermost = walked.points().getLast().expectedBinderIds();
        assertEquals(depth, innermost.size());
        assertEquals("lowered:generated:" + (depth - 1), innermost.getFirst());
        assertEquals("lowered:generated:0", innermost.getLast());
    }
}
