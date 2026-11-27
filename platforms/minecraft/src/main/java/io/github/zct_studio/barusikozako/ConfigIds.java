// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** 配置文件中的标识列表解析，供服务端配置与授权配置共用。 */
final class ConfigIds {
    private ConfigIds() {}

    static Set<String> modIds(Properties settings, String field) {
        return ids(settings, field, "[a-z][a-z0-9_-]{1,63}");
    }

    static Set<String> ids(Properties settings, String field, String pattern) {
        var ids = new HashSet<String>();
        var values = settings.getProperty(field, "").strip();
        if (!values.isEmpty()) {
            for (String id : values.split(",", -1)) {
                var trimmed = id.strip();
                if (!trimmed.matches(pattern) || !ids.add(trimmed) || ids.size() > 16)
                    throw new IllegalArgumentException("invalid or repeated component ID in " + field);
            }
        }
        return Set.copyOf(ids);
    }
}
