package kz.bejiihiu.dimensions.velocity.command;

import com.velocitypowered.api.permission.Tristate;
import kz.bejiihiu.dimensions.velocity.support.Components;
import kz.bejiihiu.dimensions.velocity.support.FakeAdminServices;
import kz.bejiihiu.dimensions.velocity.support.FakeCommandSource;
import kz.bejiihiu.dimensions.velocity.support.FakeConsoleCommandSource;
import kz.bejiihiu.dimensions.velocity.support.FakeInvocation;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admin surface: permission gating (console always allowed, node-gated otherwise),
 * subcommand dispatch, clamping, and the fail-closed status text.
 */
class DdProxyCommandTest {

    private final FakeAdminServices services = new FakeAdminServices();
    private final DdProxyCommand command = new DdProxyCommand(services);

    private String run(FakeCommandSource source, String... arguments) {
        command.execute(new FakeInvocation(source, arguments));
        return Components.plain(source.messages);
    }

    private String run(FakeConsoleCommandSource source, String... arguments) {
        command.execute(new FakeInvocation(source, arguments));
        return Components.plain(source.messages);
    }

    // --------------------------------------------------------------- permissions

    @Test
    void playerWithoutPermissionIsRefused() {
        FakeCommandSource source = new FakeCommandSource(Tristate.UNDEFINED);
        String out = run(source, "status");
        assertTrue(out.contains("don't have permission"));
        assertFalse(out.contains("proxy status"));
    }

    @Test
    void playerWithPermissionIsAllowed() {
        FakeCommandSource source = new FakeCommandSource(Tristate.TRUE);
        String out = run(source, "status");
        assertTrue(out.contains("proxy status"));
    }

    @Test
    void consoleIsAlwaysAllowedEvenWithFalsePermission() {
        FakeConsoleCommandSource console = new FakeConsoleCommandSource(); // reports FALSE permissions
        String out = run(console, "status");
        assertTrue(out.contains("proxy status"));
    }

    // --------------------------------------------------------------- status

    @Test
    void statusReportsHealthyConfiguration() {
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "status");
        assertTrue(out.contains("config: healthy"));
        assertTrue(out.contains("distributeddimensions:transfer (registered)"));
        assertTrue(out.contains("overworld -> server 'overworld'"));
        assertTrue(out.contains("requests-received = 7"));
    }

    @Test
    void statusReportsBrokenConfigurationAndDisablesRouting() {
        services.configHealthy = false;
        services.configHealthSummary = "config.yml is broken: garbage at line 12 (kept last-good configuration)";
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "status");
        assertTrue(out.contains("BROKEN"));
        assertTrue(out.contains("transfers are DISABLED"));
        assertTrue(out.contains("dimensions: unavailable"));
    }

    @Test
    void statusReportsUnregisteredChannel() {
        services.channelRegistered = false;
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        assertTrue(run(console, "status").contains("NOT REGISTERED"));
    }

    @Test
    void bareInvocationDefaultsToStatus() {
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        assertTrue(run(console).contains("proxy status"));
    }

    // --------------------------------------------------------------- reload

    @Test
    void reloadSuccessReportsHealthy() {
        services.reloadReport = new DdProxyCommand.ReloadReport(true, List.of("source: config.yml, config-version 1"));
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "reload");
        assertTrue(out.contains("Configuration reloaded"));
        assertTrue(out.contains("config-version 1"));
    }

    @Test
    void reloadFailureKeepsLastGoodAndSaysSo() {
        services.reloadReport = new DdProxyCommand.ReloadReport(false,
                List.of("source: last-good snapshot, config-version 1", "error: garbage at line 12"));
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "reload");
        assertTrue(out.contains("keeping the last-good configuration"));
        assertTrue(out.contains("transfers remain DISABLED"));
    }

    // --------------------------------------------------------------- transfers

    @Test
    void transfersShowsRecentOperations() {
        services.recent = List.of(
                FakeAdminServices.sampleOperation("nether", "SUCCESS"),
                FakeAdminServices.sampleOperation("end", "FAILED"),
                FakeAdminServices.sampleOperation("nether", "ABANDONED"));
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "transfers");
        assertTrue(out.contains("Recent transfers (3"));
        assertTrue(out.contains("overworld -> nether"));
        assertTrue(out.contains("SUCCESS"));
        assertTrue(out.contains("FAILED backend_down"));
        assertTrue(out.contains("ABANDONED disconnected"));
    }

    @Test
    void transfersClampsToFiftyAndReportsIt() {
        for (int i = 0; i < 60; i++) {
            services.recent.add(FakeAdminServices.sampleOperation("nether", "SUCCESS"));
        }
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "transfers", "999");
        assertTrue(out.contains("capped at 50"));
    }

    @Test
    void transfersRejectsInvalidNumber() {
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        assertTrue(run(console, "transfers", "banana").contains("expects a number"));
        assertTrue(run(console, "transfers", "-3").contains("must be positive"));
    }

    @Test
    void transfersDefaultsToTenRows() {
        for (int i = 0; i < 20; i++) {
            services.recent.add(FakeAdminServices.sampleOperation("nether", "SUCCESS"));
        }
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        String out = run(console, "transfers");
        assertTrue(out.contains("Recent transfers (10"));
    }

    // --------------------------------------------------------------- suggestions

    @Test
    void suggestsSubcommandsWithPrefix() {
        List<String> all = command.suggest(new FakeInvocation(new FakeCommandSource(Tristate.TRUE)));
        assertEquals(List.of("status", "reload", "transfers"), all);
        List<String> filtered = command.suggest(new FakeInvocation(new FakeCommandSource(Tristate.TRUE), "t"));
        assertEquals(List.of("transfers"), filtered);
    }

    @Test
    void suggestsTransferCounts() {
        List<String> counts = command.suggest(new FakeInvocation(new FakeCommandSource(Tristate.TRUE), "transfers", ""));
        assertEquals(List.of("10", "25", "50"), counts);
    }

    @Test
    void unknownSubcommandPrintsUsage() {
        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        assertTrue(run(console, "bogus").contains("Usage:"));
    }
}
