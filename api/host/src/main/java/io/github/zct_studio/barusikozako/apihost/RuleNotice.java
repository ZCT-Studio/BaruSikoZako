// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import java.util.List;

/** 为游戏聊天限制单次通知体积，完整规则保存在服务端日志 */
public final class RuleNotice {
    private RuleNotice() {}

    public static String forPlayer(List<String> rules, String heading, String more) {
        var message = new StringBuilder(heading);
        int shown = 0;
        for (String rule : rules) {
            if (message.length() + rule.length() > 3000) break;
            message.append('\n').append(rule);
            shown++;
        }
        if (shown < rules.size()) message.append('\n').append(more);
        return message.toString();
    }
}
