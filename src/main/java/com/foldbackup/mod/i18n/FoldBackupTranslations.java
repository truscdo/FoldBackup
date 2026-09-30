package com.foldbackup.mod.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.foldbackup.mod.FoldBackupMod;
import com.foldbackup.mod.config.FoldBackupConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Map;

/** Loads bundled translations and resolves them using the server's language policy. */
public final class FoldBackupTranslations {
    private FoldBackupTranslations() {
    }

    public static String resolveLanguage(ServerPlayer player, FoldBackupConfig config) {
        if (player == null || !config.isAutoDetectPlayerLanguage()) {
            return config.getDefaultLanguage();
        }
        return FoldBackupLanguages.resolveReportedLanguage(player.getLanguage(), config.getDefaultLanguage());
    }

    public static String resolveLanguage(CommandSourceStack source, FoldBackupConfig config) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return resolveLanguage(player, config);
        }
        return config.getDefaultLanguage();
    }

    public static Component componentForPlayer(ServerPlayer player, FoldBackupConfig config,
            String key, Object... args) {
        return Component.literal(textForLanguage(resolveLanguage(player, config), key, args));
    }

    public static Component componentForSource(CommandSourceStack source, FoldBackupConfig config,
            String key, Object... args) {
        return Component.literal(textForLanguage(resolveLanguage(source, config), key, args));
    }

    public static Component componentForConfiguredLanguage(FoldBackupConfig config, String key, Object... args) {
        return Component.literal(textForLanguage(config.getDefaultLanguage(), key, args));
    }

    public static Component componentForLanguage(String language, String key, Object... args) {
        return Component.literal(textForLanguage(language, key, args));
    }

    public static String textForSource(CommandSourceStack source, FoldBackupConfig config,
            String key, Object... args) {
        return textForLanguage(resolveLanguage(source, config), key, args);
    }

    public static String textForLanguage(String language, String key, Object... args) {
        String template = resolveTemplate(language, key);
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return template.formatted(args);
        } catch (IllegalFormatException exception) {
            FoldBackupMod.LOG.error("Failed to format translation {} for language {}", key, language, exception);
            return template;
        }
    }

    private static String resolveTemplate(String language, String key) {
        String normalized = FoldBackupLanguages.normalizeSupportedLanguage(language);
        Map<String, String> languageMap = TranslationHolder.TRANSLATIONS.get(
                normalized == null ? "en_us" : normalized);
        String template = languageMap == null ? null : languageMap.get(key);
        if (template != null) {
            return template;
        }
        FoldBackupMod.LOG.warn("Missing FoldBackup translation key {} for language {}", key, language);
        return key;
    }

    private static Map<String, Map<String, String>> loadTranslations() {
        Map<String, Map<String, String>> translations = new HashMap<>();
        for (String language : FoldBackupLanguages.SUPPORTED_LANGUAGES) {
            translations.put(language, loadLanguageFile(language));
        }
        return Map.copyOf(translations);
    }

    private static Map<String, String> loadLanguageFile(String language) {
        String resourcePath = "assets/" + FoldBackupMod.MOD_ID + "/lang/" + language + ".json";
        try (InputStream input = FoldBackupTranslations.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing FoldBackup language resource: " + resourcePath);
            }
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> translations = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                }
                return Map.copyOf(translations);
            }
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalStateException("Failed to load FoldBackup language resource " + resourcePath, exception);
        }
    }

    private static final class TranslationHolder {
        private static final Map<String, Map<String, String>> TRANSLATIONS = loadTranslations();

        private TranslationHolder() {
        }
    }
}
