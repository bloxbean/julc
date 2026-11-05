package p;
import org.julclang.testkit.Test;
enum E {
    A {
        class InA { @Test public static boolean inA() { return true; } }
    };
    class InMembers { @Test public static boolean inMembers() { return true; } }
}
class Outer {
    @Test public static boolean outer() { return true; }
    interface I { @Test public static boolean inI() { return true; } }
    record R(int x) { @Test public static boolean inRecord() { return true; } }
    @org.junit.Test public static boolean qualified() { return true; }
    @Test public static Boolean boxed() { return true; }
    @Test static boolean notPublic() { return true; }
}
