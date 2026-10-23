package com.example.facetofacetranslator;

import java.util.ArrayList;
import java.util.List;

/** One prepared phrase per line; filling the input never sends a message. */
final class PreparedScripts {
    static final int MAX_CHARACTERS = 20000;
    static final int MAX_PHRASES = 50;

    static List<String> parse(String draft) {
        if (draft.length() > MAX_CHARACTERS) throw new IllegalArgumentException("话术总长度不能超过 20000 字");
        List<String> phrases = new ArrayList<>();
        for (String line : draft.split("\\r?\\n")) {
            String phrase = line.trim();
            if (phrase.isEmpty()) continue;
            if (phrase.length() > 5000) throw new IllegalArgumentException("每条话术不能超过 5000 字");
            if (phrases.size() == MAX_PHRASES) throw new IllegalArgumentException("最多保存 50 条话术");
            phrases.add(phrase);
        }
        return phrases;
    }

    private PreparedScripts() {}
}
