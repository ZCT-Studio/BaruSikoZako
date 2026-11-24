// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** 使用玩家的语言和英语后备解析命令消息 */
final class Translations {
    private static final Map<String, Map<String, String>> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, Map<String, String>> eldest) {
                    return size() > 64;
                }
            };

    private Translations() {}

    static String text(CommandSourceStack source, String key) {
        return playerText(source == null ? null : source.getPlayer(), key);
    }

    static String format(CommandSourceStack source, String key, Object... arguments) {
        return String.format(Locale.ROOT, text(source, key), arguments);
    }

    static String playerText(ServerPlayer player, String key) {
        return languageText(player == null ? null : PlayerLanguage.get(player), key);
    }

    static String languageText(String clientLanguage, String key) {
        String language = clientLanguage == null ? "en_us" : clientLanguage.toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z0-9_]{1,32}")) {
            language = "en_us";
        }
        String value = language(language).get(key);
        if (value == null) {
            value = language("en_us").get(key);
        }
        return value == null ? key : value;
    }

    private static synchronized Map<String, String> language(String name) {
        return CACHE.computeIfAbsent(name, Translations::load);
    }

    private static Map<String, String> load(String name) {
        String path = "/assets/barusikozako/lang/" + name + ".json";
        try (var stream = Translations.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Map.of();
            }
            var messages = new HashMap<String, String>();
            try (var reader = new JsonReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    String value = reader.nextString();
                    messages.put(key, value);
                }
                reader.endObject();
            }
            return Map.copyOf(messages);
        } catch (Exception failure) {
            System.getLogger(Translations.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Could not load language file " + path,
                    failure
            );
            return Map.of();
        }
    }
}
