package build.spawn.application;

/*-
 * #%L
 * Spawn Application
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

import build.base.configuration.Configuration;
import build.base.io.Pipe;
import build.spawn.application.console.NullConsole;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardErrorFormatter;
import build.spawn.application.option.StandardErrorSubscriber;
import build.spawn.application.option.StandardOutputFormatter;
import build.spawn.application.option.StandardOutputSubscriber;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An internal abstract {@link Application}.
 *
 * @author brian.oliver
 * @since Oct-2018
 */
public abstract class AbstractApplication
    implements Application {

    /**
     * The {@link Platform} that launched the {@link Application}.
     */
    private final Platform platform;

    /**
     * The {@link Platform} {@link Process} for representing and controlling interaction with the {@link Application}.
     */
    private final Process process;

    /**
     * The {@link Class} of {@link Application} specified when launching the {@link Application}.
     */
    private final Class<? extends Application> applicationClass;

    /**
     * The {@link Configuration} used to launch the {@link Application}.
     */
    private final Configuration configuration;

    /**
     * The name resolved for the {@link Application}.
     */
    private final String name;

    /**
     * The {@link Optional} {@link Console} for the {@link Application}.
     */
    private final Optional<Console> console;

    /**
     * Completes once the {@link Process} has exited and its stdout and stderr {@link Pipe}s have delivered all of
     * their output to the subscribers, and the {@link Customizer}s have been notified of the termination.
     */
    private final CompletableFuture<Void> terminated;

    /**
     * The {@link Lifecycle}s this {@link Application} must respect.
     */
    @Inject
    private Iterable<Lifecycle<?>> lifecycles = List.of();

    /**
     * Constructs the {@link Application}.
     *
     * @param platform         the {@link Platform}
     * @param process          the {@link Process} launched for the {@link Application}
     * @param applicationClass the {@link Class} of {@link Application}
     * @param configuration    the {@link Configuration}
     */
    @SuppressWarnings("unchecked")
    public AbstractApplication(final Platform platform,
                               final Process process,
                               final Class<? extends Application> applicationClass,
                               final Configuration configuration) {

        this.platform = Objects.requireNonNull(platform, "The Platform must not be null");
        this.process = Objects.requireNonNull(process, "The Process must not be null");
        this.applicationClass = Objects.requireNonNull(applicationClass, "The class of application must not be null");
        this.configuration = Objects.requireNonNull(configuration, "The Configuration must not be null");

        // resolve the name
        this.name = this.configuration.getOptionalValue(Name.class)
            .orElseThrow(() -> new IllegalArgumentException("The Configuration fails to define an Application Name"));

        // the futures of the output Pipes, completed once all of their output has been delivered to the subscribers
        final List<CompletableFuture<?>> pipes = new ArrayList<>();

        // establish a Console for the Application (iff there's a Terminal available)
        this.console = this.process.terminal()
            .flatMap(terminal -> {
                // determine the Console.Supplier for the process
                final Console.Supplier consoleSupplier = configuration().get(Console.Supplier.class);

                final Console console = (consoleSupplier == null ? NullConsole.supplier() : consoleSupplier)
                    .create(configuration());

                // establish the stdout Pipe for the Terminal
                final var stdoutPipe = new Pipe(terminal.getOutputReader(), console.getOutputWriter())
                    .setName("stdout");

                // include the default formatter for stdout
                stdoutPipe.setTransformer(configuration()
                    .getOrDefault(StandardOutputFormatter.class, StandardOutputFormatter::getDefault)
                    .getTransformer(configuration));

                configuration().stream(StandardOutputSubscriber.class)
                    .map(StandardOutputSubscriber::get)
                    .forEach(stdoutPipe::subscribe);

                pipes.add(stdoutPipe.open());

                // establish the stderr Pip for the Terminal
                final var stderrPipe = new Pipe(terminal.getErrorReader(), console.getErrorWriter())
                    .setName("stderr");

                configuration().ifPresent(StandardErrorFormatter.class,
                    formatter -> stderrPipe.setTransformer(formatter.getTransformer(configuration())));

                configuration().stream(StandardErrorSubscriber.class)
                    .map(StandardErrorSubscriber::get)
                    .forEach(stderrPipe::subscribe);

                pipes.add(stderrPipe.open());

                // establish the stdin Pipe for the Terminal
                final var stdinPipe = new Pipe(console.getInputReader(), terminal.getInputWriter())
                    .setName("stdin")
                    .open();

                return Optional.of(console);
            });

        // the Process exiting doesn't mean its output has been delivered, as the Pipes deliver it asynchronously
        this.terminated = this.process.onExit()
            .thenCompose(__ -> CompletableFuture.allOf(pipes.toArray(CompletableFuture[]::new)))
            // upon termination, notify the application customizers of the termination
            // (part of this future, so onExit() is guaranteed to complete after the notification)
            .thenRun(() -> this.configuration()
                .stream(Customizer.class)
                .forEach(option -> option.onTerminated(platform, applicationClass, this)));
    }

    /**
     * Obtains the underlying {@link java.lang.Process} managing the {@link Application}.
     *
     * @return the {@link java.lang.Process}
     */
    protected Process process() {
        return this.process;
    }

    @Override
    public Class<? extends Application> getInterfaceClass() {
        return this.applicationClass;
    }

    @Override
    public Configuration configuration() {
        return this.configuration;
    }

    @Override
    public Platform platform() {
        return this.platform;
    }

    @Override
    public Optional<Console> console() {
        return this.console;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public boolean isAlive() {
        return this.process.isAlive();
    }

    @Override
    public OptionalInt exitValue() {
        return this.process.exitValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<? extends Application> onStart() {
        return CompletableFuture.allOf(Stream
                .concat(
                    this.configuration.stream(Customizer.class)
                        .map(customizer -> customizer.onStart(this.platform, this.applicationClass, this)),
                    StreamSupport.stream(this.lifecycles.spliterator(), false)
                        .map(Lifecycle::onStart))
                .filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new))
            .thenApply(__ -> this);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The returned {@link CompletableFuture} completes once the {@link Process} has exited and its stdout and stderr
     * have been delivered to the subscribers, and the {@link Customizer}s have been notified of the termination.
     */
    @Override
    public CompletableFuture<? extends Application> onExit() {
        return this.terminated
            .thenCompose(__ -> CompletableFuture.allOf(StreamSupport.stream(this.lifecycles.spliterator(), false)
                .map(Lifecycle::onExit)
                .filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new)))
            .thenApply(__ -> this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<? extends Application> suspend() {
        return onStart()
            .thenCompose(__ -> {
                // notify the application customizers prior to suspending
                this.configuration.stream(Customizer.class)
                    .forEach(option -> option.onSuspending(this.platform, this.applicationClass, this));

                return this.process.suspend();
            })
            .thenApply(__ -> this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<? extends Application> resume() {
        return onStart()
            .thenCompose(__ -> {
                // notify the application customizers prior to resuming
                this.configuration.stream(Customizer.class)
                    .forEach(option -> option.onResuming(this.platform, this.applicationClass, this));

                return this.process.resume();
            })
            .thenApply(__ -> this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void shutdown() {
        // notify the application customizers prior to shut down
        this.configuration.stream(Customizer.class)
            .forEach(option -> option.onShuttingDown(this.platform, this.applicationClass, this));

        this.process.shutdown();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        // notify the application customizers prior to destruction
        this.configuration.stream(Customizer.class)
            .forEach(option -> option.onDestroying(this.platform, this.applicationClass, this));

        this.process.destroy();
    }
}
