package org.julclang.compiler.frontend.javac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-068 F847: the model's discipline, as {@link JavacProgram} states it. */
class JavacProgramLifetimeTest {

    private static final String SOURCE = """
            import org.julclang.ledger.TxInfo;
            import org.julclang.core.PlutusData;
            class V {
                static boolean ok(TxInfo info, PlutusData data) { return true; }
            }
            """;

    private static JavacProgram program() {
        return JavacProgram.analyze(List.of(JavacFrontend.Unit.of(SOURCE)), List.of());
    }

    /** Each query walks classes javac has not completed yet, which is what must not interleave. */
    private static List<String> members(JavacProgram program) {
        return program.read(facts -> {
            var names = new ArrayList<String>();
            TypeElement info = facts.elements().getTypeElement("org.julclang.ledger.TxInfo");
            for (var component : facts.components(info)) {
                names.add(component.getSimpleName() + ":" + component.asType());
                if (facts.types().asElement(component.asType()) instanceof TypeElement type) {
                    ElementFilter.methodsIn(type.getEnclosedElements()).forEach(m -> names.add(m.toString()));
                }
            }
            return names;
        });
    }

    @Test
    @Timeout(30)
    void aProgramIsQueriedAfterTheThreadThatReadItHasEnded() throws Exception {
        var program = new AtomicReference<JavacProgram>();
        var reader = new Thread(() -> program.set(program()));
        reader.start();
        reader.join();
        var seen = new AtomicReference<List<String>>();
        var later = new Thread(() -> seen.set(members(program.get())));
        later.start();
        later.join();
        assertEquals(members(program()), seen.get());
        assertFalse(seen.get().isEmpty());
    }

    @Test
    @Timeout(60)
    void concurrentQueriesSeeWhatOneThreadSees() throws Exception {
        var expected = members(program());
        var program = program();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<List<String>>>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return members(program);
            }));
        }
        start.countDown();
        for (var result : results) assertEquals(expected, result.get());
        pool.shutdown();
    }

    @Test
    @Timeout(30)
    void aQueryHoldsTheProgramUntilItEnds() throws Exception {
        var program = program();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new Thread(() -> program.read(facts -> {
            entered.countDown();
            await(release);
            return null;
        }));
        first.start();
        entered.await();
        var overlapped = new AtomicBoolean(true);
        var second = new Thread(() -> program.read(facts -> {
            overlapped.set(release.getCount() != 0);
            return null;
        }));
        second.start();
        // The second query is parked on the lock, not merely late.
        for (int i = 0; i < 200 && second.getState() != Thread.State.BLOCKED; i++) Thread.sleep(5);
        assertEquals(Thread.State.BLOCKED, second.getState());
        release.countDown();
        first.join();
        second.join();
        assertFalse(overlapped.get(), "a second query ran inside the first");
    }

    @Test
    void aQueryCanNest() {
        var program = program();
        boolean same = program.read(outer -> program.read(inner -> inner == outer));
        assertTrue(same);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Outside this package the facts come only through {@code read}. The rest of the discipline is a convention: a query
     * could still return an element, and nothing here stops it.
     */
    @Test
    void theFactsAccessorIsNotPublic() throws Exception {
        assertFalse(Modifier.isPublic(JavacProgram.class.getDeclaredMethod("facts").getModifiers()));
        assertTrue(Modifier.isPublic(JavacProgram.class.getMethod("read", java.util.function.Function.class).getModifiers()));
    }
}
