// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;

/** 使用玩家的语言和英语后备解决命令消息 */
final class Translations {
    private static final Map<String, Map<String, String>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
            return size() > 64;
        }
    };

    private Translations() {}

    static String text(CommandSourceStack source, String key) {
        var player = source.getPlayer();
        String language = player == null ? "en_us" : PlayerLanguage.get(player).toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z0-9_]{1,32}")) language = "en_us";
        String value = language(language).get(key);
        if (value == null) value = language("en_us").get(key);
        return value == null ? key : value;
    }

    private static synchronized Map<String, String> language(String name) {
        return CACHE.computeIfAbsent(name, Translations::load);
    }

    private static Map<String, String> load(String name) {
        var path = "/assets/barusikozako/lang/" + name + ".json";
        try (var stream = Translations.class.getResourceAsStream(path)) {
            if (stream == null) return Map.of();
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var messages = new java.util.HashMap<String, String>();
            for (var key : json.keySet()) messages.put(key, json.get(key).getAsString());
            return Map.copyOf(messages);
        } catch (Exception failure) {
            System.getLogger(Translations.class.getName()).log(System.Logger.Level.WARNING,
                "Could not load language file " + path, failure);
            return Map.of();
        }
    }
}
