// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class Json5Test {
    @Test
    void rejectsTrailingContent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> Json5.parseObject("{ a: 1 } garbage"));
        assertTrue(error.getMessage().contains("trailing"), error.getMessage());
    }

    @Test
    void acceptsWhitespaceAndCommentsAroundRoot() {
        Map<String, Object> value = Json5.parseObject("/* head */ { a: 1, } // tail\n");
        assertEquals(1L, value.get("a"));
    }
}
