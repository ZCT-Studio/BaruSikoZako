// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 玩家可见聊天消息的统一渲染入口。
 *
 * <p>所有消息共用金色前缀 {@code chat.prefix}、按语气着色的标题符号，以及键值表格排版，
 * 使状态、快照列表与任务结果看起来像同一张表，同时避免长 UUID 刷屏。
 *
 * <p>表格内容由 {@link Piece} 组成，可以携带点击事件：完整 UUID 与时间点击复制，
 * 截断 UUID 复制完整值，操作按钮点击后把命令填入聊天栏。
 */
final class Chat {
    private static final int PREFIX_COLOR = 0xFFD700;
    private static final int LABEL_COLOR = 0xAAAAAA;
    private static final int SEPARATOR_COLOR = 0x777777;
    private static final int VALUE_COLOR = 0xFFFFFF;
    private static final int COPY_COLOR = 0xFFFF55;
    private static final int ACTION_COLOR = 0x55FFFF;
    private static final String SEPARATOR = " │ ";
    private static final String INDENT = "  ";

    /** 消息语气，决定标题符号与标题颜色 */
    enum Tone {
        SUCCESS("✔", 0x55FF55),
        FAILURE("✖", 0xFF5555),
        WARNING("!", 0xFFFF55),
        INFO("ℹ", 0x55FFFF);

        private final String symbol;
        private final int color;

        Tone(String symbol, int color) {
            this.symbol = symbol;
            this.color = color;
        }
    }

    /** 点击行为：复制文本，或把命令填入聊天栏 */
    record Click(Action action, String value) {
        enum Action { COPY, SUGGEST }

        Click {
            if (value == null) throw new IllegalArgumentException("missing click value");
        }
    }

    /** 表格中的一个文本片段；颜色为空时使用默认值列颜色 */
    record Piece(String text, Integer color, Click click) {
        Piece {
            text = text == null ? "" : text;
        }
    }

    /** 表格中的一行；标签与值都可以包含可点击片段 */
    record Row(List<Piece> label, List<Piece> value) {
        Row {
            label = label == null ? List.of() : List.copyOf(label);
            value = value == null ? List.of() : List.copyOf(value);
        }

        String labelText() {
            var text = new StringBuilder();
            for (var piece : label) text.append(piece.text());
            return text.toString();
        }
    }

    private Chat() {}

    /** 普通键值行 */
    static Row row(String label, String value) {
        return new Row(List.of(text(label)), List.of(text(value)));
    }

    /** 值为富文本的键值行 */
    static Row row(String label, Piece... value) {
        return new Row(List.of(text(label)), List.of(value));
    }

    /** 标签也是富文本的键值行（例如可复制的时间） */
    static Row row(Piece label, Piece... value) {
        return new Row(List.of(label), List.of(value));
    }

    /** 默认颜色的文本片段 */
    static Piece text(String value) {
        return new Piece(value, null, null);
    }

    /** 指定颜色的文本片段 */
    static Piece colored(String value, int rgb) {
        return new Piece(value, rgb, null);
    }

    /** 显示截断值，点击复制完整值；黄色表示可复制 */
    static Piece copy(String shown, String copied) {
        return new Piece(shown, COPY_COLOR, new Click(Click.Action.COPY, copied));
    }

    /** 可交互按钮：青色并自动加上方括号，点击后把命令填入聊天栏 */
    static Piece suggest(String shown, String command) {
        var text = shown.startsWith("[") && shown.endsWith("]") ? shown : "[" + shown + "]";
        return new Piece(text, ACTION_COLOR, new Click(Click.Action.SUGGEST, command));
    }

    /** 发送带表格行的消息 */
    static void send(CommandSourceStack source, Tone tone, String title, Row... rows) {
        send(source, tone, title, List.of(rows));
    }

    /** 发送带表格行的消息；标签按显示宽度对齐 */
    static void send(CommandSourceStack source, Tone tone, String title, List<Row> rows) {
        var content = List.copyOf(rows);
        deliver(source, tone, () -> table(source, tone, title, content));
    }

    /** 发送非键值列表（如帮助文本）：标题之后的每条内容单独一行 */
    static void lines(CommandSourceStack source, Tone tone, String title, List<String> lines) {
        var content = List.copyOf(lines);
        deliver(source, tone, () -> list(source, tone, title, content));
    }

    /** 发送不含表格的单行提示 */
    static void line(CommandSourceStack source, Tone tone, String text) {
        deliver(source, tone, () -> header(source, tone, text));
    }

    /** 发送不含表格的富文本单行提示 */
    static void line(CommandSourceStack source, Tone tone, Piece... pieces) {
        var content = List.of(pieces);
        deliver(source, tone, () -> {
            var result = styled(Translations.text(source, "chat.prefix"), PREFIX_COLOR)
                .append(styled(tone.symbol + " ", tone.color));
            for (var piece : content) result.append(pieceComponent(source, piece, VALUE_COLOR));
            return result;
        });
    }

    /** 发送多行条目：每个条目固定若干行，条目之间以空行分隔 */
    static void blocks(CommandSourceStack source, Tone tone, String title, List<List<List<Piece>>> blocks) {
        var content = blocks.stream().map(block -> block.stream().map(List::copyOf).toList()).toList();
        deliver(source, tone, () -> blockMessage(source, tone, title, content));
    }

    /** 发送不带空行间隔的富文本多行内容 */
    static void richLines(CommandSourceStack source, Tone tone, String title, List<List<Piece>> lines) {
        var content = lines.stream().map(List::copyOf).toList();
        deliver(source, tone, () -> {
            var result = header(source, tone, title);
            for (var line : content) {
                result.append(Component.literal("\n"));
                result.append(styled(INDENT, LABEL_COLOR));
                for (var piece : line) result.append(pieceComponent(source, piece, VALUE_COLOR));
            }
            return result;
        });
    }

    /**
     * 取标识符前 8 个字符用于聊天展示。
     *
     * <p>用于 UUID 与快照 UUID；{@code UUID:序号} 会保留序号，避免同一实例的任务看起来相同。
     * null 或长度不超过 8 时原样返回。该结果仅用于显示，命令参数解析始终使用完整标识符。
     */
    static String shortId(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        if (colon > 8) return id.substring(0, 8) + id.substring(colon);
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /** 统一投递：异步线程经主线程发送，失败语气使用 sendFailure */
    private static void deliver(CommandSourceStack source, Tone tone, Supplier<Component> message) {
        if (source == null) return;
        source.getServer().executeIfPossible(() -> {
            var component = message.get();
            if (tone == Tone.FAILURE) source.sendFailure(component);
            else source.sendSuccess(() -> component, false);
        });
    }

    /** 标题行：金色前缀 + 语气符号 + 空格 + 语气色标题 */
    private static MutableComponent header(CommandSourceStack source, Tone tone, String title) {
        return styled(Translations.text(source, "chat.prefix"), PREFIX_COLOR)
            .append(styled(tone.symbol + " ", tone.color))
            .append(styled(title, tone.color));
    }

    /** 标题行加若干键值行，标签按最宽的一项补空格 */
    private static MutableComponent table(CommandSourceStack source, Tone tone, String title, List<Row> rows) {
        var result = header(source, tone, title);
        int width = 0;
        for (var row : rows) width = Math.max(width, displayWidth(row.labelText()));
        for (var row : rows) {
            result.append(Component.literal("\n"));
            result.append(styled(INDENT, LABEL_COLOR));
            for (var piece : row.label()) result.append(pieceComponent(source, piece, LABEL_COLOR));
            result.append(styled(padding(row.labelText(), width), LABEL_COLOR));
            result.append(styled(SEPARATOR, SEPARATOR_COLOR));
            for (var piece : row.value()) result.append(pieceComponent(source, piece, VALUE_COLOR));
        }
        return result;
    }

    /** 标题行加若干内容行 */
    private static MutableComponent list(CommandSourceStack source, Tone tone, String title, List<String> lines) {
        var result = header(source, tone, title);
        for (var line : lines) {
            result.append(Component.literal("\n"));
            result.append(styled(INDENT + line, VALUE_COLOR));
        }
        return result;
    }

    /** 标题行加多行条目，条目之间空一行 */
    private static MutableComponent blockMessage(CommandSourceStack source, Tone tone, String title,
                                                  List<List<List<Piece>>> blocks) {
        var result = header(source, tone, title);
        boolean firstBlock = true;
        for (var block : blocks) {
            result.append(Component.literal(firstBlock ? "\n" : "\n\n"));
            firstBlock = false;
            boolean firstLine = true;
            for (var line : block) {
                if (!firstLine) result.append(Component.literal("\n"));
                firstLine = false;
                result.append(styled(INDENT, LABEL_COLOR));
                for (var piece : line) result.append(pieceComponent(source, piece, VALUE_COLOR));
            }
        }
        return result;
    }

    /** 渲染片段：颜色、点击行为与复制悬停说明 */
    private static MutableComponent pieceComponent(CommandSourceStack source, Piece piece, int defaultColor) {
        var style = Style.EMPTY.withColor(TextColor.fromRgb(piece.color() == null ? defaultColor : piece.color()));
        if (piece.click() != null) {
            style = style.withClickEvent(clickEvent(piece.click()));
            if (piece.click().action() == Click.Action.COPY) style = style.withHoverEvent(
                hoverEvent(Translations.text(source, "chat.copy_hover"), piece.click().value()));
        }
        return Component.literal(piece.text()).setStyle(style);
    }

    /** 复制悬停提示：第一行说明动作，第二行展示将要复制的完整内容 */
    private static HoverEvent hoverEvent(String hint, String value) {
        var text = Component.literal(hint + "\n" + value);
//? if mc >= 1.21.5
        return new HoverEvent.ShowText(text);
//? else
//?~        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
//? end
    }

    /** 1.21.5 起 ClickEvent 改为密封接口；旧版本仍使用构造器与 Action 枚举 */
    private static ClickEvent clickEvent(Click click) {
//? if mc >= 1.21.5
        return click.action() == Click.Action.COPY
            ? new ClickEvent.CopyToClipboard(click.value())
            : new ClickEvent.SuggestCommand(click.value());
//? else
//?~        return new ClickEvent(click.action() == Click.Action.COPY
//?~            ? ClickEvent.Action.COPY_TO_CLIPBOARD : ClickEvent.Action.SUGGEST_COMMAND, click.value());
//? end
    }

    /** 按固定颜色生成文本片段 */
    private static MutableComponent styled(String text, int rgb) {
        return Component.literal(text == null ? "" : text)
            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    /** 计算显示宽度：CJK 等宽字符按 2 列计 */
    private static int displayWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += codePoint >= 0x2E80 ? 2 : 1;
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /** 在标签之后补足空格，使所有值列对齐 */
    private static String padding(String label, int width) {
        var builder = new StringBuilder();
        for (int padding = width - displayWidth(label); padding > 0; padding--) builder.append(' ');
        return builder.toString();
    }
}
