package org.julclang.wasm;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.julclang.core.BlsConstantValidator;

/**
 * Web Image substitution: the UPLC text parser validates BLS12-381 constants through a ServiceLoader-discovered
 * implementation backed by the native blst library, which cannot run in WebAssembly. Without a validator the parser
 * accepts BLS constants unchecked, as it does when no implementation is on the classpath.
 */
@TargetClass(BlsConstantValidator.class)
final class Target_org_julclang_core_BlsConstantValidator {

    @Substitute
    static BlsConstantValidator getInstance() {
        return null;
    }
}
