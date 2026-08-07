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

import build.base.flow.Subscriber;
import build.base.flow.Subscription;
import build.base.json.Json;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link InputStreamProcessor} that reads NDJSON (newline-delimited JSON) and emits one
 * {@link JsonValue} per line.
 *
 * @author brian.oliver
 * @since Jun-2021
 */
public class JsonNodeInputStreamProcessor
    implements InputStreamProcessor<JsonValue> {

    /**
     * The {@link TelemetryRecorder} used to record processing failures.
     */
    private final TelemetryRecorder recorder;

    /**
     * Constructs a {@link JsonNodeInputStreamProcessor}, recording telemetry to {@link System#err}.
     */
    public JsonNodeInputStreamProcessor() {
        this(PrintStreamTelemetryRecorder.of(URI.create("spawn://docker-jdk"), System.out, System.err));
    }

    /**
     * Constructs a {@link JsonNodeInputStreamProcessor}.
     *
     * @param recorder the {@link TelemetryRecorder} used to record processing failures
     */
    public JsonNodeInputStreamProcessor(final TelemetryRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "The TelemetryRecorder must not be null");
    }

    @Override
    public void process(final InputStream inputStream,
                        final Subscriber<? super JsonValue> subscriber) {

        final var cancelled = new AtomicBoolean(false);

        final var subscription = new Subscription() {
            @Override
            public void request(final long number) {}

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        };

        subscriber.onSubscribe(subscription);

        boolean failed = false;
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.get() && !failed && (line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    subscriber.onNext(Json.parse(trimmed));
                } catch (final Throwable throwable) {
                    this.recorder.warn(throwable, "Failed to parse or deliver a JSON line from the stream");
                    failed = true;
                    subscriber.onError(throwable);
                }
            }
        } catch (final ClosedChannelException e) {
            // Response#cancel() closes the underlying channel; a read blocked at that moment surfaces here as
            // a ClosedChannelException (or its AsynchronousCloseException subtype) rather than propagating to
            // onError — this is the normal mechanism used to stop a long-running stream (eg: system events),
            // so it's an expected termination, not a failure, and isn't recorded as telemetry
        } catch (final Throwable throwable) {
            this.recorder.warn(throwable, "Failed while processing the JSON input stream");
            if (!failed) {
                subscriber.onError(throwable);
            }
        } finally {
            try {
                if (!failed) {
                    subscriber.onComplete();
                }
                inputStream.close();
            } catch (final IOException e) {
                this.recorder.warn(e, "Failed to close the JSON input stream");
            }
        }
    }
}
