package p;
import org.julclang.stdlib.annotation.*;
class Outer {
    void m() {
        @OnchainLibrary class LocalLib { }
        Runnable r = new Runnable() { @OnchainLibrary class InAnon { } public void run() {} };
    }
    @OnchainLibrary @SpendingValidator static class Conflict { }
    enum En { A { @OnchainLibrary class InConst { } }; @OnchainLibrary class InEnum { } }
    static { @OnchainLibrary class InInit { } }
    Object o = switch (1) { case 1 -> { @OnchainLibrary class InCase { } yield null; } default -> null; };
}
@OnchainLibrary
@MintingValidator
record TopRec(int a) { }
