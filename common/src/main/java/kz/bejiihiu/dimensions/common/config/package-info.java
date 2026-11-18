/**
 * The plugin's configuration system. One YAML schema serves both platforms
 * (Paper/Folia backend and Velocity proxy); each platform consumes the sections
 * that apply to it and ignores the rest.
 *
 * <p>The flow is always: {@link Environment} (platform passes a path) →
 * {@link ConfigurationService#load()} → immutable {@link PluginConfig} snapshot.
 * All snapshot types are records; nothing here exposes Configurate or any
 * platform API, so the module compiles against neither Bukkit nor Velocity.
 *
 * <p>Non-obvious behaviors:
 * <ul>
 *   <li>first start writes the bundled defaults to disk;</li>
 *   <li>missing keys are healed in memory from the bundled defaults (the file is
 *       <em>not</em> rewritten unless a migration runs);</li>
 *   <li>unknown keys are collected into {@link LoadReport#warnings()} as
 *       dotted paths and never fail startup;</li>
 *   <li>a broken file keeps the last-good snapshot and reports through
 *       {@link ConfigurationService#health()};</li>
 *   <li>{@code config-version} drives the class-based migration framework,
 *       applied sequentially, saved back atomically;</li>
 *   <li>{@code ${VAR}} references in the database connection strings are
 *       expanded at load time; an unset variable is a hard failure naming the
 *       variable, never its value.</li>
 * </ul>
 */
package kz.bejiihiu.dimensions.common.config;
