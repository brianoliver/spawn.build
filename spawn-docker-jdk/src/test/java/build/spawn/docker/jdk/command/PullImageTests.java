package build.spawn.docker.jdk.command;

import build.base.configuration.Configuration;
import build.base.json.JsonParseException;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.spawn.docker.jdk.Authenticator;
import build.spawn.docker.jdk.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for how {@link PullImage} interprets the {@code /images/create} progress stream.
 *
 * @author reed.vonredwitz
 * @since Aug-2026
 */
class PullImageTests {

    /**
     * A newline-delimited JSON progress stream that ends with the {@code Docker Engine}'s terminal
     * {@code Status: ...} object, as emitted when layers were actually downloaded.
     */
    private static final String DOWNLOADED_STREAM = String.join("\n",
        "{\"status\":\"Pulling from library/alpine\",\"id\":\"latest\"}",
        "{\"status\":\"Pulling fs layer\",\"progressDetail\":{},\"id\":\"9fb3aa2f8b80\"}",
        "{\"status\":\"Download complete\",\"progressDetail\":{},\"id\":\"9fb3aa2f8b80\"}",
        "{\"status\":\"Digest: sha256:abc123\"}",
        "{\"status\":\"Status: Downloaded newer image for alpine:latest\"}");

    /**
     * A no-op {@link PrintStreamTelemetryRecorder} that stands in for the injected one in these tests.
     */
    private static final PrintStreamTelemetryRecorder RECORDER =
        PrintStreamTelemetryRecorder.of(URI.create("spawn://test"), System.out, System.err);

    /**
     * Creates a {@link PullImage} for the given image name with the dependencies {@code createResult}
     * and {@code submit} need ({@link AbstractCommand#recorder} and the {@link Authenticator}) injected
     * by reflection, since there is no DI container in these tests.
     *
     * @param nameOrId the image name or id
     * @return the configured command
     * @throws Exception if reflection fails
     */
    private static PullImage newCommand(final String nameOrId) throws Exception {
        final var command = new PullImage(nameOrId, Configuration.empty());
        inject(command, AbstractCommand.class, "recorder", RECORDER);
        inject(command, PullImage.class, "authenticator", Authenticator.NONE);
        return command;
    }

    /**
     * Sets a (possibly private) field declared by {@code declaringType} on {@code target}.
     *
     * @param target        the instance to mutate
     * @param declaringType the class that declares the field
     * @param name          the field name
     * @param value         the value to set
     * @throws Exception if the field is missing or inaccessible
     */
    private static void inject(final Object target,
                               final Class<?> declaringType,
                               final String name,
                               final Object value) throws Exception {
        final Field field = declaringType.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * A stub {@link HttpTransport.Response} that serves a fixed body and a {@code 200} status code.
     */
    private static final class StubResponse
        implements HttpTransport.Response {

        private final byte[] body;

        StubResponse(final String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public String header(final String name) {
            return null;
        }

        @Override
        public InputStream bodyStream() {
            return new ByteArrayInputStream(this.body);
        }

        @Override
        public void cancel() {
            // nothing to cancel
        }

        @Override
        public void close() {
            // nothing to close
        }
    }

    /**
     * A stream that reaches the terminal {@code Status: ...} object is a successful pull, and the
     * result carries the requested image name.
     */
    @Test
    void shouldSucceedWhenTerminalStatusObserved() throws Exception {
        final var result = newCommand("alpine:latest").createResult(new StubResponse(DOWNLOADED_STREAM));

        assertThat(result).contains("alpine:latest");
    }

    /**
     * The terminal object emitted when the local image was already current is also a success.
     */
    @Test
    void shouldSucceedWhenImageAlreadyUpToDate() throws Exception {
        final var stream = "{\"status\":\"Status: Image is up to date for alpine:latest\"}";

        final var result = newCommand("alpine:latest").createResult(new StubResponse(stream));

        assertThat(result).contains("alpine:latest");
    }

    /**
     * An in-band {@code error} object (delivered while the HTTP status stays {@code 200}) fails the
     * command, surfacing the {@code Docker Engine}'s error message.
     */
    @Test
    void shouldFailOnInBandError() throws Exception {
        final var stream = String.join("\n",
            "{\"status\":\"Pulling from library/alpine\",\"id\":\"nope\"}",
            "{\"errorDetail\":{\"message\":\"manifest unknown\"},"
                + "\"error\":\"manifest for alpine:nope not found\"}");

        assertThatThrownBy(() -> newCommand("alpine:nope").createResult(new StubResponse(stream)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("alpine:nope")
            .hasMessageContaining("manifest for alpine:nope not found");
    }

    /**
     * A line that cannot be parsed as JSON surfaces through the subscriber's {@code onError}, failing
     * the command and keeping the underlying {@link Throwable} as the cause.
     */
    @Test
    void shouldFailOnUnparseableLine() throws Exception {
        final var stream = String.join("\n",
            "{\"status\":\"Pulling from library/alpine\",\"id\":\"latest\"}",
            "this is not json");

        assertThatThrownBy(() -> newCommand("alpine:latest").createResult(new StubResponse(stream)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("alpine:latest")
            .hasCauseInstanceOf(JsonParseException.class);
    }

    /**
     * A stream that ends before the terminal {@code Status: ...} object (eg: the connection dropped
     * mid-pull) is treated as a failure rather than a success.
     */
    @Test
    void shouldFailWhenStreamEndsBeforeCompletion() throws Exception {
        final var stream = String.join("\n",
            "{\"status\":\"Pulling from library/alpine\",\"id\":\"latest\"}",
            "{\"status\":\"Downloading\",\"progressDetail\":{\"current\":10,\"total\":100},\"id\":\"9fb3aa2f8b80\"}");

        assertThatThrownBy(() -> newCommand("alpine:latest").createResult(new StubResponse(stream)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("ended before the pull completed");
    }

    /**
     * End-to-end through {@link PullImage#submit()}: the {@link IOException} raised while processing the
     * response is not thrown as-is - {@code AbstractCommand} routes it through {@code onRequestFailed},
     * which wraps any non-{@link RuntimeException} in a {@link RuntimeException} keeping it as the cause.
     */
    @Test
    void shouldWrapResponseFailureFromSubmit() throws Exception {
        final var stream = "{\"error\":\"manifest for alpine:nope not found\"}";

        final var command = newCommand("alpine:nope");
        inject(command, AbstractCommand.class, "transport", (HttpTransport) request -> new StubResponse(stream));

        assertThatThrownBy(command::submit)
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(IOException.class)
            .cause()
            .isInstanceOf(IOException.class)
            .hasMessageContaining("manifest for alpine:nope not found");
    }
}
