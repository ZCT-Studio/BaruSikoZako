// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api.forge;

import io.github.zct_studio.barusikozako.api.BackupIntegration;
import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import net.minecraftforge.fml.IExtensionPoint;

/** 在自己的模组容器上注册此扩展，两个回调均可省略 */
public record ZakoBakIntegration(BackupIntegration service, BackupLifecycleHook lifecycle) implements IExtensionPoint<ZakoBakIntegration> {}
