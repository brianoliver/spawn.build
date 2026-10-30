package build.spawn.application.local;

import build.base.assertion.Eventually;
import build.base.configuration.ConfigurationBuilder;
import build.base.flow.CompletingSubscriber;
import build.base.flow.RecordingSubscriber;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * Ensure the output of an {@link Application} has been delivered to its subscribers by the time
     * {@link Application#onExit()} completes.
     */
    @Test
    default void shouldDeliverAllOutputBeforeExit() throws Exception {

        final var output = new RecordingSubscriber<String>();

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(output))) {

            application.onExit().get();

            assertThat(output.isCompleted()).isTrue();
            assertThat(output.items()).containsExactly("Hello World");
        }
    }

    /**
     * Ensure the standard error of an {@link Application} has been delivered to its subscribers by the time
     * {@link Application#onExit()} completes.
     */
    @Test
    default void shouldDeliverAllErrorOutputBeforeExit() throws Exception {

        final var stdout = new RecordingSubscriber<String>();
        final var stderr = new RecordingSubscriber<String>();

        try (Application application = machine()
            .launch("sh",
                Argument.of("-c"),
                Argument.of("echo out; echo err >&2"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(stdout),
                StandardErrorSubscriber.of(stderr))) {

            application.onExit().get();

            assertThat(stdout.isCompleted()).isTrue();
            assertThat(stdout.items()).containsExactly("out");
            assertThat(stderr.isCompleted()).isTrue();
            assertThat(stderr.items()).containsExactly("err");
        }
    }

    /**
     * Ensure the output of an {@link Application} has been delivered to its subscribers by the time its
     * {@link Customizer}s are notified of the termination, and that {@link Application#onExit()} completes after
     * that notification.
     */
    @Test
    default void shouldDeliverAllOutputBeforeNotifyingCustomizersOfTermination() throws Exception {

        final var output = new RecordingSubscriber<String>();
        final var customizer = new OutputCheckingCustomizer(output);

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(output),
                customizer)) {

            application.onExit().get();

            assertThat(customizer.terminated.get()).isEqualTo(1);
            assertThat(customizer.completedOnTerminated.get()).isTrue();
            assertThat(customizer.itemsOnTerminated.get()).containsExactly("Hello World");
        }
    }

    /**
     * Ensure {@link Application#onExit()} completes, and {@link Customizer}s are notified of the termination, when
     * a descendant of the {@link Application} outlives it.
     */
    @Test
    default void shouldCompleteExitWhenDescendantOutlivesApplication() throws Exception {

        final var output = new RecordingSubscriber<String>();
        final var customizer = new OutputCheckingCustomizer(output);

        try (Application application = machine()
            .launch("sh",
                Argument.of("-c"),
                Argument.of("echo out; sleep 20 & echo $!"),
                Console.ofSystem(),
                StandardOutputSubscriber.of(output),
                customizer)) {

            try {
                // the descendant lives for 20 seconds, so completing well before then means we didn't wait for it
                application.onExit().get(5, TimeUnit.SECONDS);

                assertThat(customizer.terminated.get()).isEqualTo(1);
                assertThat(customizer.itemsOnTerminated.get()).hasSize(2).first().isEqualTo("out");
            }
            finally {
                // the descendant isn't a child of the Application, so closing the Application won't terminate it
                output.items()
                    .skip(1)
                    .findFirst()
                    .map(Long::parseLong)
                    .flatMap(ProcessHandle::of)
                    .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /**
     * Ensure {@link Application#onExit()} completes exceptionally when a {@link Customizer} throws while being
     * notified of the termination.
     */
    @Test
    default void shouldFailExitWhenCustomizerFailsOnTerminated() throws Exception {

        final var customizer = new Customizer<Application>() {
            @Override
            public void onTerminated(final Platform platform,
                                     final Class<? extends Application> applicationClass,
                                     final Application application) {
                throw new IllegalStateException("boom");
            }
        };

        try (Application application = machine()
            .launch("echo",
                Argument.of("Hello World"),
                customizer)) {

            assertThatThrownBy(() -> application.onExit().get())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("boom");
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
     * A {@link Customizer} that records the state of a {@link RecordingSubscriber} at the moment of termination.
     */
    final class OutputCheckingCustomizer
        implements Customizer<Application> {

        private final RecordingSubscriber<String> output;
        private final AtomicInteger terminated = new AtomicInteger();
        private final AtomicBoolean completedOnTerminated = new AtomicBoolean();
        private final AtomicReference<List<String>> itemsOnTerminated = new AtomicReference<>(List.of());

        private OutputCheckingCustomizer(final RecordingSubscriber<String> output) {
            this.output = output;
        }

        @Override
        public void onTerminated(final Platform platform,
                                  final Class<? extends Application> applicationClass,
                                  final Application application) {
            completedOnTerminated.set(output.isCompleted());
            itemsOnTerminated.set(output.items().toList());
            terminated.incrementAndGet();
        }
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
