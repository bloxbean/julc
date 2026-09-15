package org.julclang.playground.wasm;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.julclang.vm.java.CekValue;
import org.julclang.vm.java.builtins.BuiltinException;

/**
 * Web Image substitution: BLS12-381 builtins are backed by the native blst library (JNI and FFM), which cannot run
 * in WebAssembly. Every BLS builtin goes through {@code Bls12381Builtins.bls(name, call)}; replacing it makes the
 * native code unreachable and turns each call into a regular builtin evaluation failure with a clear message.
 */
@TargetClass(className = "org.julclang.vm.java.builtins.Bls12381Builtins")
final class Target_org_julclang_vm_java_builtins_Bls12381Builtins {

    @Substitute
    private static CekValue bls(String name, Target_org_julclang_vm_java_builtins_Bls12381Builtins_BlsCall call) {
        throw new BuiltinException(name + ": BLS12-381 builtins are not supported in the WebAssembly playground");
    }
}

@TargetClass(className = "org.julclang.vm.java.builtins.Bls12381Builtins", innerClass = "BlsCall")
final class Target_org_julclang_vm_java_builtins_Bls12381Builtins_BlsCall {
}
