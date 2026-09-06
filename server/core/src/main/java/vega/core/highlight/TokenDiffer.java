package vega.core.highlight;

import java.util.Arrays;
import java.util.List;

/**
 * Produces semantic-token deltas by trimming the unchanged head and tail of two token arrays.
 *
 * <p>A common-prefix/common-suffix diff rather than a general edit-script one. Typing changes tokens
 * near the caret and leaves everything before and after identical, so this finds the minimal
 * contiguous change for the case that actually happens, in one linear pass. A general diff would
 * spend more time finding a marginally smaller edit than the client saves applying it.
 *
 * <p>Everything is quantised to whole tokens. The array is packed five integers per token, so an
 * edit that begins or ends mid-token re-interprets every following integer as a different field.
 */
public final class TokenDiffer {

    private static final int FIELDS_PER_TOKEN = 5;

    private TokenDiffer() {}

    public static TokenDelta diff(int[] previous, int[] next) {
        return diff(previous, next, null);
    }

    public static TokenDelta diff(int[] previous, int[] next, String resultId) {
        int maxTokens = Math.min(previous.length, next.length) / FIELDS_PER_TOKEN;

        int prefixTokens = 0;
        while (prefixTokens < maxTokens && tokensEqual(previous, next, prefixTokens, prefixTokens)) {
            prefixTokens++;
        }

        int previousTokens = previous.length / FIELDS_PER_TOKEN;
        int nextTokens = next.length / FIELDS_PER_TOKEN;
        int suffixTokens = 0;
        while (suffixTokens < maxTokens - prefixTokens
                && tokensEqual(
                        previous, next, previousTokens - suffixTokens - 1, nextTokens - suffixTokens - 1)) {
            suffixTokens++;
        }

        int start = prefixTokens * FIELDS_PER_TOKEN;
        int deleteCount = previous.length - start - suffixTokens * FIELDS_PER_TOKEN;
        int insertEnd = next.length - suffixTokens * FIELDS_PER_TOKEN;

        if (deleteCount == 0 && insertEnd == start) {
            return new TokenDelta(resultId, List.of());
        }

        int[] data = Arrays.copyOfRange(next, start, insertEnd);
        return new TokenDelta(resultId, List.of(new TokenDelta.Edit(start, deleteCount, data)));
    }

    private static boolean tokensEqual(int[] a, int[] b, int aToken, int bToken) {
        int aBase = aToken * FIELDS_PER_TOKEN;
        int bBase = bToken * FIELDS_PER_TOKEN;
        for (int field = 0; field < FIELDS_PER_TOKEN; field++) {
            if (a[aBase + field] != b[bBase + field]) {
                return false;
            }
        }
        return true;
    }
}
