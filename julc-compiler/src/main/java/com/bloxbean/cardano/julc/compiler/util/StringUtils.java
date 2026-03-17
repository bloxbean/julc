package com.bloxbean.cardano.julc.compiler.util;

/**
 * Shared string utilities for the compiler.
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * Compute the Levenshtein (edit) distance between two strings.
     * Uses a space-optimized two-row DP approach.
     */
    public static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            var tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }
}
