package p;
import org.julclang.stdlib.annotation.OnchainLibrary;
enum E {
    A { @OnchainLibrary class InConst { } };
    @OnchainLibrary static class InEnum { }
}
