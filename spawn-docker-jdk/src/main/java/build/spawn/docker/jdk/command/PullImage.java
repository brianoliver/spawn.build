package build.spawn.docker.jdk.command;

/*-
 * #%L
 * Spawn Docker (JDK Client)
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
import build.base.flow.Subscriber;
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.spawn.docker.jdk.Authenticator;
import build.spawn.docker.jdk.HttpTransport;
import build.spawn.docker.option.ImageName;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The {@code Docker Daemon} {@link Command} to pull an image using the
 * <a href="https://docs.docker.com/engine/api/v1.41/#operation/ImageCreate">Create Image</a> command.
 * <p>
 * The {@code /images/create} response is a stream of newline-delimited JSON progress objects that the
 * {@code Docker Engine} keeps open until the pull has completed (or failed). This {@link Command} blocks
 * until that stream has been fully consumed and the {@code Docker Engine} has emitted its terminal
 * {@code Status: ...} progress object, regardless of whether layers were actually downloaded or the
 * local image was already up to date. A {@code Docker Engine} failure is reported in-band via an
 * {@code error} field while the HTTP status remains {@code 200}; a stream that ends without either a
 * terminal status or an error (eg: the connection dropped mid-pull) is also treated as a failure.
 *
 * @author brian.oliver
 * @since Jun-2021
 */
public class PullImage
    extends AbstractBlockingCommand<Optional<String>> {

    /**
     * The {@link Authenticator} for the {@link Request}.
     */
    @Inject
    private Authenticator authenticator;

    /**
     * The name or id of the image to inspect.
     */
    private final String nameOrId;

    /**
     * The {@link Configuration}s for the image to pull.
     */
    private final Configuration configuration;

    /**
     * Constructs a {@link PullImage} {@link Command}.
     *
     * @param nameOrId      the name or id of the image to pull
     * @param configuration the {@link Configuration} for the image to pull
     */
    public PullImage(final String nameOrId, final Configuration configuration) {
        this.nameOrId = nameOrId;
        this.configuration = configuration == null
            ? Configuration.empty()
            : configuration;
    }

    @Override
    protected HttpTransport.Request createRequest() {
        final var names = ImageName.namesWithDockerRegistry(this.nameOrId, this.configuration);
        final var imageName = String.join("/", names);
        final var body = ("fromImage=" + imageName).getBytes(StandardCharsets.UTF_8);
        return this.authenticator.apply(
            HttpTransport.Request
                .post("/images/create", body)
                .withContentType("application/x-www-form-urlencoded"));
    }

    @Override
    protected Optional<String> createResult(final HttpTransport.Response response)
        throws IOException {

        // consume the entire progress stream, blocking until the Docker Engine finishes the pull;
        // JsonNodeInputStreamProcessor delivers every progress object synchronously on this thread, so
        // plain fields on the holder below are sufficient - no need for atomics. We capture any in-band
        // error (reported while the HTTP status stays 200) and note whether the terminal "Status: ..."
        // progress object was seen so a truncated stream isn't mistaken for success.
        final var outcome = new PullOutcome();

        new JsonNodeInputStreamProcessor(recorder())
            .process(response.bodyStream(), new Subscriber<JsonValue>() {
                @Override
                public void onNext(final JsonValue item) {
                    if (!(item instanceof JsonObject object)) {
                        return;
                    }
                    if (object.has("error")) {
                        outcome.recordError(object.getString("error"));
                    } else if (object.has("status")
                        && object.getString("status").startsWith("Status: ")) {
                        outcome.recordCompletion();
                    }
                }

                @Override
                public void onError(final Throwable throwable) {
                    outcome.recordFailure(throwable);
                }
            });

        if (outcome.error != null) {
            throw new IOException("Failed to pull the image '" + this.nameOrId + "': " + outcome.error,
                outcome.cause);
        }

        if (!outcome.completed) {
            throw new IOException("Failed to pull the image '" + this.nameOrId
                + "': the Docker Engine progress stream ended before the pull completed");
        }

        return Optional.of(this.nameOrId);
    }

    /**
     * A mutable holder for what was observed while consuming the {@code /images/create} progress stream.
     * Only ever touched from the single thread {@link JsonNodeInputStreamProcessor} uses to deliver
     * progress objects, so it needs no synchronisation.
     */
    private static final class PullOutcome {

        /**
         * The first error reported - an in-band {@code Docker Engine} error message or the message of a
         * stream delivery failure - or {@code null} if none was seen.
         */
        private String error;

        /**
         * The {@link Throwable} behind a stream delivery failure, or {@code null} for an in-band
         * {@code Docker Engine} error (which arrives as data, not an exception) or when none was seen.
         */
        private Throwable cause;

        /**
         * Whether the terminal {@code Status: ...} progress object was observed.
         */
        private boolean completed;

        /**
         * Records the first in-band {@code Docker Engine} error seen; subsequent errors are ignored so
         * the earliest cause wins.
         *
         * @param message the error message
         */
        private void recordError(final String message) {
            if (this.error == null) {
                this.error = message;
            }
        }

        /**
         * Records the first stream delivery failure seen, keeping the {@link Throwable} as the cause.
         *
         * @param throwable the failure
         */
        private void recordFailure(final Throwable throwable) {
            if (this.error == null) {
                this.error = throwable.getMessage() == null
                    ? throwable.toString()
                    : throwable.getMessage();
                this.cause = throwable;
            }
        }

        /**
         * Records that the {@code Docker Engine} emitted its terminal status object.
         */
        private void recordCompletion() {
            this.completed = true;
        }
    }
}
