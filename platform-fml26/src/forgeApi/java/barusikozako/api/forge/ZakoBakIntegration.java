// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api.forge;

import barusikozako.api.BackupIntegration;
import barusikozako.api.BackupLifecycleHook;
import net.minecraftforge.fml.IExtensionPoint;

/** Register this extension on your own mod container. Either callback can be absent. */
public record ZakoBakIntegration(BackupIntegration service, BackupLifecycleHook lifecycle) implements IExtensionPoint<ZakoBakIntegration> {}
