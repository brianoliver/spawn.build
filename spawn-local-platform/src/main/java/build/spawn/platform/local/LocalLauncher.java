package build.spawn.platform.local;

/*-
 * #%L
 * Spawn Local Platform
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
import build.base.option.WorkingDirectory;
import build.spawn.application.AbstractTemplatedLauncher;
import build.spawn.application.Application;
import build.spawn.application.Launcher;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Executable;
import build.spawn.application.option.Name;
import build.spawn.option.EnvironmentVariable;

import java.io.IOException;

/**
 * A generic {@link Application} {@link Launcher} for a {@link LocalMachine}.
 *
 * @author brian.oliver
 * @since Oct-2018
 */
public class LocalLauncher
    extends AbstractTemplatedLauncher<Application, LocalMachine, LocalProcess> {

    @Override
    public LocalProcess createProcess(final LocalMachine platform,
                                      final ConfigurationBuilder options) {

        // establish a ProcessBuilder to create and launch the underlying process for the application
        final Executable executable = getExecutable(options)
            .orElseThrow(() -> new IllegalArgumentException("Failed to provide or determine the Executable to launch"));

        final ProcessBuilder processBuilder = new ProcessBuilder(
            Strings.doubleQuoteIfContainsWhiteSpace(executable.get()));

        processBuilder.directory(options.get(WorkingDirectory.class).path().toFile());

        // --- establish the EnvironmentVariables ---
        options.stream(EnvironmentVariable.class)
            .forEach(variable -> variable.value()
                .ifPresentOrElse(value -> processBuilder
                        .environment()
                        .put(variable.key(), Strings.doubleQuoteIfContainsWhiteSpace(value)),
                    () -> processBuilder
                        .environment()
                        .put(variable.key(), "")));

        // include the Arguments in the command-line
        options.stream(Argument.class)
            .map(Argument::get)
            .map(argument -> executable.get().equals("java")
                ? Strings.doubleQuoteIfContainsWhiteSpace(argument)
                : argument)
            .forEach(processBuilder.command()::add);

        this.diagnostics.addRow("Application Launch Command", String.join(" ", processBuilder.command()));

        this.recorder.diagnostic("build.spawn: Launching Application...\n"
            + "--------------------------------------------------------------\n" + "%s"
            + "--------------------------------------------------------------", this.diagnostics);

        try {
            // attempt to start the application
            final Process nativeProcess = processBuilder.start();

            // establish a LocalApplicationProcess to represent the native process
            return new LocalProcess(nativeProcess, platform);
        }
        catch (final IOException e) {
            this.recorder.error(e, "Failed to launch a native process for the application %s",
                options.get(Name.class).get());

            throw new RuntimeException("Failed to launch native process for the application", e);
        }
    }
}
