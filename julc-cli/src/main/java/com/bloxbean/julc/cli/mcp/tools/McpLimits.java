package com.bloxbean.julc.cli.mcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared request-size limits for MCP tools that accept source text.
 */
final class McpLimits {

    /** Source-length cap to prevent runaway parser/compiler input. */
    static final int MAX_SOURCE_BYTES = 200_000;
    /** Per-library-source cap; each library source is parsed as Java. */
    static final int MAX_LIBRARY_SOURCE_BYTES = MAX_SOURCE_BYTES;
    /** Aggregate cap for all library sources in one request. */
    static final int MAX_TOTAL_LIBRARY_SOURCE_BYTES = 1_000_000;
    /** Keep library resolution bounded for hosted transports. */
    static final int MAX_LIBRARY_SOURCE_COUNT = 16;

    private McpLimits() {}

    static String validateSource(String argumentName, String source) {
        int bytes = utf8Bytes(source);
        if (bytes > MAX_SOURCE_BYTES) {
            return "'" + argumentName + "' too large (" + bytes + " bytes); max " +
                    MAX_SOURCE_BYTES + ".";
        }
        return null;
    }

    static String validateLibrarySources(List<String> librarySources) {
        if (librarySources.size() > MAX_LIBRARY_SOURCE_COUNT) {
            return "'librarySources' has too many entries (" + librarySources.size() +
                    "); max " + MAX_LIBRARY_SOURCE_COUNT + ".";
        }
        int totalBytes = 0;
        for (int i = 0; i < librarySources.size(); i++) {
            String source = librarySources.get(i);
            int bytes = utf8Bytes(source);
            if (bytes > MAX_LIBRARY_SOURCE_BYTES) {
                return "'librarySources[" + i + "]' too large (" + bytes + " bytes); max " +
                        MAX_LIBRARY_SOURCE_BYTES + ".";
            }
            totalBytes += bytes;
            if (totalBytes > MAX_TOTAL_LIBRARY_SOURCE_BYTES) {
                return "'librarySources' too large in total (" + totalBytes +
                        " bytes); max " + MAX_TOTAL_LIBRARY_SOURCE_BYTES + ".";
            }
        }
        return null;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
