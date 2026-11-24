// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 条件编译器行级指令与块注释行内指令的自动化测试。 */
final class ConditionalProcessorTest {
    private static final ConditionalProcessor.Context MODERN =
        new ConditionalProcessor.Context("26.3", "fabric", 25);
    private static final ConditionalProcessor.Context LEGACY =
        new ConditionalProcessor.Context("1.20.1", "forge", 17);

    @Test
    void inlineConditionalResolvesBothBranches() {
        String source = "int x = /*? if mc >= 26 {*/1/*?} else {*/2/*?}*/;";

        String modern = ConditionalProcessor.apply(source, MODERN);
        assertEquals("int x = /*? if mc >= 26 {*/1/*?} else {*//*?~ 2 *//*?}*/;", modern);
        assertTrue(ConditionalProcessor.isResolved(modern, MODERN));
        assertEquals(source, ConditionalProcessor.canonical(modern));

        String legacy = ConditionalProcessor.apply(source, LEGACY);
        assertEquals("int x = /*? if mc >= 26 {*//*?~ 1 *//*?} else {*/2/*?}*/;", legacy);
        assertTrue(ConditionalProcessor.isResolved(legacy, LEGACY));
        assertEquals(source, ConditionalProcessor.canonical(legacy));
    }

    @Test
    void inlineMultiLineBranchWrapsAndRestores() {
        String source = """
            void call() {
            /*? if mc >= 26 {*/
                modern();
            /*?} else {*/
                legacy();
            /*?}*/
            }
            """;

        String legacy = ConditionalProcessor.apply(source, LEGACY);
        assertTrue(legacy.contains("legacy();"));
        assertTrue(legacy.contains("/*?~ "));
        assertTrue(ConditionalProcessor.isResolved(legacy, LEGACY));
        assertEquals(source, ConditionalProcessor.canonical(legacy));

        String modern = ConditionalProcessor.apply(legacy, MODERN);
        assertTrue(modern.contains("modern();"));
        assertTrue(ConditionalProcessor.isResolved(modern, MODERN));
    }

    @Test
    void lineDirectiveBehaviorIsUnchanged() {
        String source = "//? if mc < 26\nlegacy();\n//? else\nmodern();\n//? end\n";
        String modern = ConditionalProcessor.apply(source, MODERN);
        assertEquals("//? if mc < 26\n//?~ legacy();\n//? else\nmodern();\n//? end\n", modern);
        assertEquals(source, ConditionalProcessor.canonical(modern));
    }

    @Test
    void lineAndInlineInactivePrefixesCoexist() {
        String source = "//? if loader == 'fabric'\nint x = /*? if mc >= 26 {*/1/*?} else {*/2/*?}*/;\n//? end\n";
        String applied = ConditionalProcessor.apply(source, LEGACY);
        assertTrue(applied.contains("//?~ int x = /*? if mc >= 26 {*//*?~ 1 *//*?} else {*/2/*?}*/;"));
        assertEquals(source, ConditionalProcessor.canonical(applied));
        assertTrue(ConditionalProcessor.isResolved(applied, LEGACY));
    }

    @Test
    void nestedInlineConditionalsAreRejected() {
        String source = "/*? if mc >= 26 {*//*? if java >= 21 {*/x/*?}*//*?}*/";
        List<String> errors = ConditionalProcessor.validate(source);
        assertTrue(errors.stream().anyMatch(error -> error.contains("nested inline conditional")), errors.toString());
        assertThrows(ConditionalProcessor.DirectiveException.class,
            () -> ConditionalProcessor.apply(source, MODERN));
    }

    @Test
    void inactiveBranchWithBlockCommentIsRejected() {
        String source = "int x = /*? if mc >= 26 {*/1/*?} else {*/a /* note */ b/*?}*/;";
        ConditionalProcessor.DirectiveException error = assertThrows(
            ConditionalProcessor.DirectiveException.class,
            () -> ConditionalProcessor.apply(source, MODERN));
        assertTrue(error.getMessage().contains("cannot be disabled"), error.getMessage());
    }

    @Test
    void invalidInlineExpressionIsReported() {
        String source = "/*? if mc >= {*/x/*?}*/";
        List<String> errors = ConditionalProcessor.validate(source);
        assertFalse(errors.isEmpty());
        assertThrows(ConditionalProcessor.DirectiveException.class,
            () -> ConditionalProcessor.apply(source, MODERN));
    }

    @Test
    void directiveLikeTextInsideStringsIsIgnored() {
        String source = "String value = \"/*? if mc >= 26 {*/\";";
        assertEquals(source, ConditionalProcessor.apply(source, MODERN));
        assertTrue(ConditionalProcessor.validate(source).isEmpty());
        assertTrue(ConditionalProcessor.isResolved(source, MODERN));
    }
}
