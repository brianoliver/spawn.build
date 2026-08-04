package build.spawn.platform.local.jdk;

/*-
 * #%L
 * Spawn Local JDK
 * %%
 * Copyright (C) 2026 Workday, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import build.base.configuration.ConfigurationBuilder;
import build.base.foundation.Strings;
import build.base.network.Network;
import build.base.option.JDKVersion;
import build.base.option.WorkingDirectory;
import build.spawn.application.Launcher;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Executable;
import build.spawn.application.option.LaunchIdentity;
import build.spawn.application.option.Name;
import build.spawn.application.option.Orphanable;
import build.spawn.jdk.AbstractTemplatedJDKLauncher;
import build.spawn.jdk.JDKApplication;
import build.spawn.jdk.OperatingSystem;
import build.spawn.jdk.agent.SpawnAgent;
import build.spawn.jdk.agent.SpawnAgentArchiveBuilder;
import build.spawn.jdk.option.JDKAgent;
import build.spawn.jdk.option.JDKHome;
import build.spawn.jdk.option.JDKOption;
import build.spawn.jdk.option.Jar;
import build.spawn.jdk.option.MainClass;
import build.spawn.option.EnvironmentVariable;
import build.spawn.platform.local.LocalMachine;
import build.spawn.platform.local.LocalProcess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Launcher} for {@link JDKApplication}s on a {@link LocalMachine}.
 *
 * @author brian.oliver
 * @since Oct-2018
 */
public class LocalJDKLauncher
    extends AbstractTemplatedJDKLauncher<JDKApplication, LocalMachine, LocalProcess> {

    /**
     * The cached {@link Path} to the {@link SpawnAgent} archive, created at most once per JVM.
     */
    private static final AtomicReference<Path> SPAWN_AGENT_ARCHIVE = new AtomicReference<>();

    @Override
    public Optional<Executable> getExecutable(final ConfigurationBuilder options) {
        // the JDK is launched on this LocalMachine, so the host's own OperatingSystem determines
        // whether the java executable carries a .exe suffix
        final var javaExecutable = OperatingSystem.current() == OperatingSystem.WINDOWS ? "bin/java.exe" : "bin/java";

        return Optional.of(Executable.of(options.get(JDKHome.class).get() + "/" + javaExecutable));
    }

    @Override
    public LocalProcess createProcess(final LocalMachine machine,
                                      final ConfigurationBuilder options) {

        // obtain the Path to the SpawnAgent archive, creating it at most once per JVM
        if (SPAWN_AGENT_ARCHIVE.get() == null) {
            final Path created = SpawnAgentArchiveBuilder.getArchive()
                .orElseGet(SpawnAgentArchiveBuilder::createArchive);
            SPAWN_AGENT_ARCHIVE.compareAndSet(null, created);
        }
        final var spawnAgentPath = SPAWN_AGENT_ARCHIVE.get();

        // establish a ProcessBuilder to create and launch the underlying process for the application
        final ProcessBuilder processBuilder = getExecutable(options)
            .map(Executable::get)
            .map(Strings::doubleQuoteIfContainsWhiteSpace)
            .map(ProcessBuilder::new)
            .orElseThrow(
                () -> new IllegalArgumentException("Failed to provide or determine the Java Executable to launch"));

        processBuilder.directory(options.get(WorkingDirectory.class).path().toFile());

        // --- detect the JDKVersion ---
        final var javaVersion = options.getOrDefault(JDKVersion.class, JDKVersion::current);

        // --- establish the EnvironmentVariables ---
        options.stream(EnvironmentVariable.class)
            .forEach(environmentVariable -> {
                environmentVariable.value()
                    .ifPresentOrElse(value -> processBuilder.environment().put(
                            environmentVariable.key(),
                            Strings.doubleQuoteIfContainsWhiteSpace(value)),
                        () -> processBuilder.environment().put(environmentVariable.key(), ""));
            });

        // --- include the SpawnAgent (if not already defined) ---
        if (options.stream(JDKAgent.class)
            .noneMatch(agent -> agent.path().toString().contains(SpawnAgent.ARCHIVE_NAME))) {

            // determine if the application is orphanable
            final Orphanable orphanable = options.get(Orphanable.class);

            // find the first address of the machine that's of the same class as the Server
            // (ensures that if the Server is using IPv4, the address returned is IPv4)
            final Optional<InetAddress> inetAddress = machine.addresses()
                .filter(Network.isOfClass(this.server.getLocalAddress()))
                .findFirst();

            if (inetAddress.isPresent()) {
                try {
                    // create a URI for the SpawnAgent
                    final URI uri = new URI("spawn", null, inetAddress.get().getHostAddress(),
                        this.server.getLocalPort(), null, null, null);

                    // introduce the SpawnAgent as a JavaAgent
                    options.add(JDKAgent.of(
                        spawnAgentPath,
                        "machine=" + uri
                            + ",orphanable" + "=" + orphanable
                            + ",launchId=" + options.get(LaunchIdentity.class).get()));
                }
                catch (final URISyntaxException e) {
                    throw new RuntimeException("Failed to create URI for the SpawnAgent", e);
                }
            }
            else {
                throw new RuntimeException(
                    "Failed to determine a local machine address of the same class as the machine server");
            }
        }

        // --- include the Java Agents (they must appear first when starting a Java Virtual Machine) ---
        options.stream(JDKAgent.class)
            .filter(agent -> agent.isSupported(javaVersion, options))
            .flatMap(agent -> agent.resolve(machine, options))
            .forEach(processBuilder.command()::add);

        // --- include the Java Options (excluding the JavaAgents as we've just included them) ---
        options.stream(JDKOption.class)
            .filter(option -> !(option instanceof JDKAgent))
            .filter(option -> option.isSupported(javaVersion, options))
            .flatMap(option -> option.resolve(machine, options))
            .forEach(processBuilder.command()::add);

        // --- include the MainClass / Module / Jar ---
        final MainClass mainClass = options.get(MainClass.class);
        if (mainClass == null) {
            final Jar jarPath = options.get(Jar.class);

            if (jarPath == null) {
                throw new IllegalArgumentException("Failed to define a MainClass for the JDKApplication");
            }

            processBuilder.command().add("-jar");
            processBuilder.command().add(Strings.doubleQuoteIfContainsWhiteSpace(jarPath.get().toString()));
        }
        else {
            processBuilder.command().add(mainClass.className());
        }

        // --- include the Arguments ---
        options.stream(Argument.class)
            .map(Argument::get)
            .map(Strings::doubleQuoteIfContainsWhiteSpace)
            .forEach(processBuilder.command()::add);

        // --- launch the Java Application ---
        this.diagnostics.addRow("Application Launch Command", String.join(" ", processBuilder.command()));

        this.recorder.diagnostic("\n" + "build.spawn: Launching JDK-based Application...\n"
            + "--------------------------------------------------------------\n" + "%s"
            + "--------------------------------------------------------------", this.diagnostics);

        try {
            // attempt to start the application
            final Process nativeProcess = processBuilder.start();

            return new LocalProcess(nativeProcess, machine);
        }
        catch (final IOException e) {
            this.recorder.error(e, "Failed to launch a native process for the application %s",
                options.get(Name.class).get());

            throw new RuntimeException("Failed to launch native process for the application", e);
        }
    }
}
