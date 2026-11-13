// SPDX-License-Identifier: GPL-3.0-only
package dev.local.hongguotv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class PinyinTitles {
    static final int NO_MATCH = 100;
    private static final Pattern TONES = Pattern.compile("\\p{M}+");
    private static final Pattern ROMAN_QUERY = Pattern.compile("[0-9a-z]*[a-z][0-9a-z]*");

    private final Supplier<Map<Integer, String[]>> source;
    private Map<Integer, String[]> readings;

    PinyinTitles(Supplier<Map<Integer, String[]>> source) {
        this.source = source;
    }

    static Map<Integer, String[]> read(InputStream stream) throws IOException {
        Map<Integer, String[]> dictionary = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab > 0) dictionary.put(line.codePointAt(0), line.substring(tab + 1).split(","));
            }
        }
        return dictionary;
    }

    static String normalizeQuery(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replace('ü', 'v').replace('ǖ', 'v').replace('ǘ', 'v').replace('ǚ', 'v').replace('ǜ', 'v');
        String withoutTones = TONES.matcher(Normalizer.normalize(normalized, Normalizer.Form.NFD)).replaceAll("");
        StringBuilder compact = new StringBuilder();
        for (int offset = 0; offset < withoutTones.length();) {
            int code = withoutTones.codePointAt(offset);
            offset += Character.charCount(code);
            if (Character.isLetterOrDigit(code)) compact.appendCodePoint(code);
        }
        String query = compact.toString();
        return ROMAN_QUERY.matcher(query).matches() ? query : "";
    }

    private synchronized Map<Integer, String[]> dictionary() {
        if (readings == null) readings = source.get();
        return readings;
    }

    int score(String query, String title) {
        Map<Integer, String[]> dictionary = dictionary();
        List<String[]> syllables = new ArrayList<>();
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        boolean hasPinyin = false;
        for (int offset = 0; offset < normalized.length();) {
            int code = normalized.codePointAt(offset);
            offset += Character.charCount(code);
            if (!Character.isLetterOrDigit(code)) continue;
            String[] options = dictionary.get(code);
            if (options == null) options = new String[]{new String(Character.toChars(code))};
            else hasPinyin = true;
            syllables.add(options);
        }
        if (!hasPinyin) return NO_MATCH;

        return Math.min(match(query, syllables, false), match(query, syllables, true));
    }

    private static int match(String query, List<String[]> syllables, boolean initials) {
        // Track the earliest possible start at each query offset. This lets alternate readings
        // share work without generating every possible pronunciation of the title.
        int length = query.length();
        boolean[] fromBeginning = new boolean[length];
        boolean[] fromMiddle = new boolean[length];
        boolean[] nextBeginning = new boolean[length];
        boolean[] nextMiddle = new boolean[length];
        int best = NO_MATCH;
        for (int i = 0; i < syllables.size(); i++) {
            fromBeginning[0] = i == 0;
            fromMiddle[0] = i > 0;
            boolean last = i == syllables.size() - 1;
            for (int used = 0; used < length; used++) {
                if (!fromBeginning[used] && !fromMiddle[used]) continue;
                for (String option : syllables.get(i)) {
                    int size = initials ? 1 : option.length();
                    int remaining = length - used;
                    if (!query.regionMatches(used, option, 0, Math.min(size, remaining))) continue;
                    if (remaining <= size) {
                        int rank = fromBeginning[used] ? last && remaining == size ? 0 : 10 : 20;
                        best = Math.min(best, rank);
                    } else {
                        nextBeginning[used + size] |= fromBeginning[used];
                        nextMiddle[used + size] |= fromMiddle[used];
                    }
                }
            }
            if (best == 0) break;
            boolean[] swap = fromBeginning;
            fromBeginning = nextBeginning;
            nextBeginning = swap;
            swap = fromMiddle;
            fromMiddle = nextMiddle;
            nextMiddle = swap;
            java.util.Arrays.fill(nextBeginning, false);
            java.util.Arrays.fill(nextMiddle, false);
        }
        return best == NO_MATCH ? best : best + (initials ? 2 : 1);
    }
}
