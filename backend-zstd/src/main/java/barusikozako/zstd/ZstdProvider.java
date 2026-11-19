// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.zstd;

import barusikozako.backend.BackendProvider;
import barusikozako.backend.ManagedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZstdProvider implements BackendProvider {
    @Override public String keyword() { return "zakobak_zstd"; }

    @Override public Map<String, String> defaults() {
        return Map.of("level", "3", "compressionThreads", "1", "writeMode", "full",
            "baseline", "", "gcIntervalSeconds", "0", "maxIncrementals", "8",
            "fullThresholdPercent", "75", "fullIntervalSeconds", "0");
    }

    @Override public List<Command> commands() {
        return List.of(new Command("full", "FULL", false), new Command("incremental", "INCREMENTAL", false),
            new Command("differential", "DIFFERENTIAL", true), new Command("fixed-chunks", "FIXED_CHUNKS", false),
            new Command("rebuild", "REBUILD", true));
    }

    @Override public Settings configure(Map<String, String> values) {
        if (!defaults().keySet().containsAll(values.keySet())) throw new IllegalArgumentException("Unknown Zstd setting");
        var compression = new ZstdRepository.Compression(Integer.parseInt(values.getOrDefault("level", "3")),
            Integer.parseInt(values.getOrDefault("compressionThreads", "1")));
        var mode = switch (values.getOrDefault("writeMode", "full")) {
            case "full" -> ZstdRepository.WriteMode.FULL;
            case "incremental" -> ZstdRepository.WriteMode.INCREMENTAL;
            case "differential" -> ZstdRepository.WriteMode.DIFFERENTIAL;
            case "fixed-chunks", "fixed-chunks-preview" -> ZstdRepository.WriteMode.EXPERIMENTAL_FIXED_CHUNKS;
            default -> throw new IllegalArgumentException("Unknown Zstd write mode");
        };
        var baselineText = values.getOrDefault("baseline", "");
        var baseline = baselineText.isEmpty() ? null : UUID.fromString(baselineText);
        if ((mode == ZstdRepository.WriteMode.DIFFERENTIAL) != (baseline != null) ||
            (baseline != null && !baseline.toString().equals(baselineText)))
            throw new IllegalArgumentException("Zstd differential mode requires a canonical baseline UUID");
        long gc = Long.parseLong(values.getOrDefault("gcIntervalSeconds", "0"));
        if (gc < 0 || (mode == ZstdRepository.WriteMode.EXPERIMENTAL_FIXED_CHUNKS && compression.threads() != 1))
            throw new IllegalArgumentException("Invalid Zstd maintenance or compression configuration");
        var rollover = new ZstdRepository.Rollover(Integer.parseInt(values.getOrDefault("maxIncrementals", "8")),
            Integer.parseInt(values.getOrDefault("fullThresholdPercent", "75")),
            Long.parseLong(values.getOrDefault("fullIntervalSeconds", "0")));
        return new Settings() {
            @Override public long maintenanceIntervalSeconds() { return gc; }

            @Override public boolean supports(String operation) {
                return switch (operation) {
                    case "CONFIGURED", "FULL", "INCREMENTAL", "DIFFERENTIAL", "REBUILD" -> true;
                    case "FIXED_CHUNKS" -> compression.threads() == 1;
                    default -> false;
                };
            }

            @Override public ManagedRepository open(java.nio.file.Path path, DirectoryPersistence persistence,
                                                     String operation, UUID requestedBaseline) throws java.io.IOException {
                var selected = switch (operation) {
                    case "CONFIGURED" -> mode;
                    case "FULL", "REBUILD" -> ZstdRepository.WriteMode.FULL;
                    case "INCREMENTAL" -> ZstdRepository.WriteMode.INCREMENTAL;
                    case "DIFFERENTIAL" -> ZstdRepository.WriteMode.DIFFERENTIAL;
                    case "FIXED_CHUNKS" -> ZstdRepository.WriteMode.EXPERIMENTAL_FIXED_CHUNKS;
                    default -> throw new IllegalArgumentException("Unsupported Zstd operation " + operation);
                };
                return ZstdRepository.open(path, persistence::force, compression, selected,
                    operation.equals("CONFIGURED") ? baseline : requestedBaseline, rollover);
            }
        };
    }
}
