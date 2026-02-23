package com.bloxbean.cardano.julc.analyzer.cli;

import picocli.CommandLine;

/**
 * Provides version info from JAR manifest or fallback.
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
        var pkg = getClass().getPackage();
        String version = (pkg != null) ? pkg.getImplementationVersion() : null;
        if (version == null) {
            version = "dev";
        }
        return new String[]{"julc-analyzer " + version};
    }
}
