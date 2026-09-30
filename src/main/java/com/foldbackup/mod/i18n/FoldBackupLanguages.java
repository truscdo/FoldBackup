package com.foldbackup.mod.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Supported languages and client-locale normalization for server-side messages. */
public final class FoldBackupLanguages {
    private static final Map<String, String> LANGUAGES = createLanguages();

    public static final Set<String> SUPPORTED_LANGUAGES = LANGUAGES.keySet();

    private FoldBackupLanguages() {
    }

    private static Map<String, String> createLanguages() {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("zh_cn", "zh");
        languages.put("en_us", "en");
        languages.put("es_es", "es");
        languages.put("pt_br", "pt");
        languages.put("ru_ru", "ru");
        return Collections.unmodifiableMap(languages);
    }

    public static boolean isSupportedLanguage(Object value) {
        return value instanceof String language && normalizeSupportedLanguage(language) != null;
    }

    public static String normalizeSupportedLanguage(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : null;
    }

    /** Maps unsupported regional variants to a translation sharing their language prefix. */
    public static String resolveReportedLanguage(String reportedLanguage, String fallback) {
        String normalized = normalizeSupportedLanguage(reportedLanguage);
        if (normalized != null) {
            return normalized;
        }
        if (reportedLanguage != null) {
            String prefix = reportedLanguage.strip().toLowerCase(Locale.ROOT).replace('-', '_');
            for (var entry : LANGUAGES.entrySet()) {
                if (prefix.startsWith(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        return fallback;
    }
}
