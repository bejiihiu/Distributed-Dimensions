package kz.bejiihiu.dimensions.admin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code /dd} admin command of the backend plugin.
 *
 * <p><b>Why this native shape.</b> {@code paper-plugin.yml} has no {@code commands}
 * field — Paper plugins register commands from code (the legacy {@code plugin.yml}
 * descriptor is never read when {@code paper-plugin.yml} is present). The Paper
 * platform API offers {@link BasicCommand}: the Bukkit-style {@code execute} +
 * {@code suggest} + {@code canUse} shape without any external dependency and without
 * hand-rolling a Brigadier tree for three literal subcommands. Full Brigadier or a
 * A command framework would be ceremony here — and would be a bundled dependency
 * for no behavioral gain. Tab completion is the {@link #suggest} override below.
 *
 * <p><b>Permissions.</b> The single node {@value #PERMISSION} (default {@code op})
 * gates every subcommand; it is declared in {@code paper-plugin.yml} so permission
 * plugins see it as a first-class node. {@link #permission()} wires the same node
 * into the platform check; {@link #execute} re-checks defensively because console
 * and player senders both reach this code path.
 *
 * <p>Subcommands: {@code info} (backend overview), {@code reload} (configuration
 * re-check — see {@link ReloadOutcome}), {@code mirage} (mirage world registry).
 * Everything renders through {@link Messages}; the selected language is read at
 * send time, so {@code /dd reload} switching {@code general.language} takes effect
 * for the very next message.
 */
public final class DdCommand implements BasicCommand {

    /** The one admin node. Default {@code op}; declared in paper-plugin.yml. */
    public static final String PERMISSION = "distributeddimensions.admin";

    /** Subcommand literals, in suggestion order. */
    public static final List<String> SUBCOMMANDS = List.of("info", "reload", "mirage");

    private final Services services;
    private final Messages messages;

    public DdCommand(Services services, Messages messages) {
        this.services = Objects.requireNonNull(services, "services");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Everything the command needs from the live plugin, behind one seam. The
     * production implementation is assembled by {@code AdminSurface}; tests use a
     * programmable fake and exercise the pure line builders directly.
     */
    public interface Services {

        /** Snapshot for {@code /dd info}. Never throws. */
        AdminInfo info();

        /** Re-reads config.yml and reports honestly — see {@link ReloadOutcome}. Never throws. */
        ReloadOutcome reload();

        /** Snapshot for {@code /dd mirage}. Never throws. */
        MirageInfo mirages();
    }

    // ------------------------------------------------------------------ execute

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(messages.render("no.permission",
                    Placeholder.unparsed("permission", PERMISSION)));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> send(sender, InfoLines.build(services.info(), messages));
            case "reload" -> send(sender, ReloadLines.build(services.reload(), messages));
            case "mirage" -> send(sender, MirageLines.build(services.mirages(), messages));
            default -> {
                if (!sub.isEmpty()) {
                    sender.sendMessage(messages.render("unknown.subcommand",
                            Placeholder.unparsed("sub", truncate(sub))));
                }
                send(sender, helpLines());
            }
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return suggestSubcommands(args);
    }

    @Override
    public @NotNull String permission() {
        return PERMISSION;
    }

    // ------------------------------------------------------------------ helpers

    /** Pure suggestion logic, separated for tests: first-argument filtering only. */
    static List<String> suggestSubcommands(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> matches = new ArrayList<>(SUBCOMMANDS.size());
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(prefix)) {
                    matches.add(subcommand);
                }
            }
            return List.copyOf(matches);
        }
        return List.of();
    }

    private List<Component> helpLines() {
        return List.of(
                messages.render("help.usage"),
                messages.render("help.info"),
                messages.render("help.reload"),
                messages.render("help.mirage"));
    }

    private static void send(CommandSender sender, List<Component> lines) {
        for (Component line : lines) {
            sender.sendMessage(line);
        }
    }

    private static String truncate(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32);
    }
}
