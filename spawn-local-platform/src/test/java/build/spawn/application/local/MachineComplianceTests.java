package build.spawn.application.local;

import build.base.assertion.Eventually;
import build.base.configuration.ConfigurationBuilder;
import build.base.flow.CompletingSubscriber;
import build.base.foundation.CompletableFutures;
import build.base.option.WorkingDirectory;
import build.spawn.application.Application;
import build.spawn.application.Console;
import build.spawn.application.Customizer;
import build.spawn.application.Machine;
import build.spawn.application.Platform;
import build.spawn.application.option.Argument;
import build.spawn.application.option.StandardErrorSubscriber;
import build.spawn.application.option.StandardOutputSubscriber;
import build.spawn.option.EnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compliance tests for all {@link Machine}s.
 *
 * @author graeme.campbell
 * @since Jun-2019
 */
public interface MachineComplianceTests {

    /**
     * Obtain the {@link Machine} on which to execute tests.
     *
     * @return the {@link Machine}
     */
    Machine machine();

    /**
     * Ensure a {@link Machine} can launch a JVM.
     */
    @Test
    default void shouldLaunchVirtualMachine() {

        try (Application application = machine()
            .launch("java",
                Argument.of("-help"),
                Console.ofSystem())) {

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine} can launch "echo hello world"
     */
    @Test
    default void shouldOutputHelloWorld() {

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                Console.ofSystem())) {

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine}'s name can be evaluated by a resolvable {@link Argument}.
     */
    @Test
    default void shouldOutputHelloLocalByEvaluatingMachineName() {

        final var machine = machine();
        final var completingSubscriber = new CompletingSubscriber<String>();
        final var nameObserved = completingSubscriber
            .when(s -> s.contains("Hello " + machine.name()));

        try (Application application = machine
            .launch("echo",
                Argument.of("Hello ${machine.name()}"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(completingSubscriber))) {

            Eventually.assertThat(nameObserved)
                .isCompleted();

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine} can launch an Executable which is an expression.
     */
    @Test
    default void shouldEvaluateExecutableEchoExpression() {

        final var completingSubscriber = new CompletingSubscriber<String>();

        final var helloWorldObserved = completingSubscriber.when(s -> s.contains("Hello World"));

        try (Application application = machine()
            .launch("${'e'.concat('cho')}",
                Argument.of("Hello World"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(completingSubscriber))) {

            Eventually.assertThat(helloWorldObserved)
                .isCompleted();

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine} can launch an application using a {@link WorkingDirectory} which is an expression.
     */
    @Test
    default void shouldEvaluateWorkingDirectoryExpression() {

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                Console.ofSystem(),
                WorkingDirectory.of("${'/usr/'.concat('local')}"))) {

            assertThat(application.configuration()
                .getValue(WorkingDirectory.class))
                .isEqualTo("/usr/local");

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine} can set {@link EnvironmentVariable}s using expressions and that they are reflected
     * in the launched {@link Application}'s running environment.
     */
    @Test
    default void shouldEvaluateEnvironmentVariableWithExpression() {

        final var machine = machine();
        final var completingObserver = new CompletingSubscriber<String>();
        final var localObserved = completingObserver
            .when(s -> s.contains(machine.name()));

        try (Application application = machine
            .launch("printenv",
                Argument.of("LC_MACHINE_NAME"),
                Console.ofSystem(),
                EnvironmentVariable.of("LC_MACHINE_NAME", "${machine.name()}"),
                StandardOutputSubscriber.of(completingObserver))) {

            Eventually.assertThat(localObserved)
                .isCompleted();

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }
    }

    /**
     * Ensure a {@link Machine} can launch java and we can observe stderr.
     */
    @Test
    default void shouldObserveLaunchingAVirtualMachine() {

        final var completingSubscriber = new CompletingSubscriber<String>();

        // establish a CompletableFuture that is completed when "Usage:" is observed
        final var usageObserved = completingSubscriber.when(s -> s.startsWith("Usage:"));

        // launch "java" with the "-help" argument
        try (Application java = machine()
            .launch("java",
                Argument.of("-help"),
                Console.ofSystem(),
                StandardErrorSubscriber.of(completingSubscriber))) {

            // ensure the application started
            Eventually.assertThat(java.onStart())
                .isCompleted();

            // ensure we observe "Usage:" instructions from "java"
            Eventually.assertThat(usageObserved)
                .isCompleted();

            // ensure "java" exits
            Eventually.assertThat(java.onExit())
                .isCompleted();

            // ensure "java" exits with the expected value
            assertThat(java.exitValue())
                .isPresent();

            assertThat(java.exitValue().getAsInt())
                .isEqualTo(0);
        }
    }

    /**
     * Ensure {@link Customizer}s methods are invoked when launching an {@link Application}.
     */
    @Test
    default void shouldObserveApplicationCustomizerCallbacks() {

        // establish a Customizer that counts invocations to ensure expected callbacks occur
        final var customizer = new CountingCustomizer();

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                customizer)) {

            Eventually.assertThat(application.onStart())
                .isCompleted();

            Eventually.assertThat(application.onExit())
                .isCompleted();
        }

        assertThat(customizer.onLaunching.get()).isEqualTo(1);
        assertThat(customizer.onLaunched.get()).isEqualTo(1);
        assertThat(customizer.onStart.get()).isEqualTo(1);
        assertThat(customizer.onShuttingDown.get()).isEqualTo(1);
        assertThat(customizer.onTerminated.get()).isEqualTo(1);
        assertThat(customizer.onDestroying.get()).isEqualTo(0);
        assertThat(customizer.onSuspending.get()).isEqualTo(0);
        assertThat(customizer.onResuming.get()).isEqualTo(0);
    }

    /**
     * A {@link Customizer} that counts invocations of each lifecycle callback.
     */
    final class CountingCustomizer
        implements Customizer<Application> {

        private final AtomicInteger onLaunching = new AtomicInteger();
        private final AtomicInteger onLaunched = new AtomicInteger();
        private final AtomicInteger onStart = new AtomicInteger();
        private final AtomicInteger onSuspending = new AtomicInteger();
        private final AtomicInteger onResuming = new AtomicInteger();
        private final AtomicInteger onShuttingDown = new AtomicInteger();
        private final AtomicInteger onDestroying = new AtomicInteger();
        private final AtomicInteger onTerminated = new AtomicInteger();

        @Override
        public void onLaunching(final Platform platform,
                                 final Class<? extends Application> applicationClass,
                                 final ConfigurationBuilder configurationBuilder) {
            onLaunching.incrementAndGet();
        }

        @Override
        public void onLaunched(final Platform platform,
                                final Class<? extends Application> applicationClass,
                                final Application application) {
            onLaunched.incrementAndGet();
        }

        @Override
        public CompletableFuture<? extends Application> onStart(final Platform platform,
                                                                  final Class<? extends Application> applicationClass,
                                                                  final Application application) {
            onStart.incrementAndGet();
            return CompletableFutures.completedFuture();
        }

        @Override
        public void onSuspending(final Platform platform,
                                  final Class<? extends Application> applicationClass,
                                  final Application application) {
            onSuspending.incrementAndGet();
        }

        @Override
        public void onResuming(final Platform platform,
                                final Class<? extends Application> applicationClass,
                                final Application application) {
            onResuming.incrementAndGet();
        }

        @Override
        public void onShuttingDown(final Platform platform,
                                    final Class<? extends Application> applicationClass,
                                    final Application application) {
            onShuttingDown.incrementAndGet();
        }

        @Override
        public void onDestroying(final Platform platform,
                                  final Class<? extends Application> applicationClass,
                                  final Application application) {
            onDestroying.incrementAndGet();
        }

        @Override
        public void onTerminated(final Platform platform,
                                  final Class<? extends Application> applicationClass,
                                  final Application application) {
            onTerminated.incrementAndGet();
        }
    }
}
