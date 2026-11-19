// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import java.lang.management.ManagementFactory;

final class NativeAccess {
    private NativeAccess() {}

    private static final boolean EXPLICIT_ALLOW = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(argument -> argument.startsWith("--illegal-native-access="))
        .reduce((first, last) -> last).filter("--illegal-native-access=allow"::equals).isPresent();

    static boolean permitted(Class<?> owner) {
        return owner.getModule().isNativeAccessEnabled() || EXPLICIT_ALLOW;
    }

}
