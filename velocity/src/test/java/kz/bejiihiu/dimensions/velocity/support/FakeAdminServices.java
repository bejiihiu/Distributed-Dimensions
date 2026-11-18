package kz.bejiihiu.dimensions.velocity.support;

import kz.bejiihiu.dimensions.velocity.command.DdProxyCommand;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Programmable {@link DdProxyCommand.AdminServices} for command tests. */
public final class FakeAdminServices implements DdProxyCommand.AdminServices {

    public boolean channelRegistered = true;
    public boolean configHealthy = true;
    public String configHealthSummary = "loaded from config.yml, config-version 1";
    public List<String> dimensionLines = List.of(
            "overworld -> server 'overworld' (minecraft:overworld)",
            "nether -> server 'nether' (minecraft:the_nether)",
            "end -> server 'end' (minecraft:the_end)");
    public int inFlightOperations;
    public int pendingCapacity = 512;
    public int completedCacheSize;
    public long completedCacheTtlMillis = 600_000L;
    public Map<String, Long> counters = Map.of("requests-received", 7L);
    public List<TransferOperations.RecentOperation> recent = new ArrayList<>();
    public DdProxyCommand.ReloadReport reloadReport = new DdProxyCommand.ReloadReport(true, List.of("source: config.yml, config-version 1"));

    @Override
    public boolean channelRegistered() {
        return channelRegistered;
    }

    @Override
    public boolean configHealthy() {
        return configHealthy;
    }

    @Override
    public String configHealthSummary() {
        return configHealthSummary;
    }

    @Override
    public List<String> dimensionLines() {
        return dimensionLines;
    }

    @Override
    public int inFlightOperations() {
        return inFlightOperations;
    }

    @Override
    public int pendingCapacity() {
        return pendingCapacity;
    }

    @Override
    public int completedCacheSize() {
        return completedCacheSize;
    }

    @Override
    public long completedCacheTtlMillis() {
        return completedCacheTtlMillis;
    }

    @Override
    public Map<String, Long> counters() {
        return new LinkedHashMap<>(counters);
    }

    @Override
    public List<TransferOperations.RecentOperation> recentTransfers(int n) {
        return recent.stream().limit(n).toList();
    }

    @Override
    public DdProxyCommand.ReloadReport reload() {
        return reloadReport;
    }

    /** Builds a sample ring entry for transfers-output tests. */
    public static TransferOperations.RecentOperation sampleOperation(String target, String kind) {
        TransferOperations.OutcomeKind outcome = TransferOperations.OutcomeKind.valueOf(kind);
        kz.bejiihiu.dimensions.common.model.TransferError error = switch (outcome) {
            case SUCCESS -> null;
            case FAILED -> kz.bejiihiu.dimensions.common.model.TransferError.BACKEND_DOWN;
            case ABANDONED -> kz.bejiihiu.dimensions.common.model.TransferError.DISCONNECTED;
        };
        return new TransferOperations.RecentOperation(Instant.parse("2026-11-18T21:43:01Z"),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "overworld", target, outcome, error,
                "transfer outcome (fake)", 812L);
    }
}
