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

import build.base.flow.Publicist;
import build.base.flow.Subscriber;
import build.base.json.JsonValue;
import build.base.naming.UniqueNameGenerator;
import build.spawn.docker.Event;
import build.spawn.docker.jdk.HttpTransport;
import build.spawn.docker.jdk.event.ActionEvent;
import jakarta.inject.Inject;

import java.io.IOException;

/**
 * The {@code Docker Daemon} {@link Command} to request a stream of system events using the
 * <a href="https://docs.docker.com/engine/api/v1.41/#operation/SystemEvents">System Events</a> command.
 *
 * @author brian.oliver
 * @since Jun-2021
 */
public class GetSystemEvents
    extends AbstractNonBlockingCommand<Void> {

    /**
     * The {@link Publicist} for {@code Docker Engine} {@link Event}s.
     */
    @Inject
    private Publicist<Event> publisher;

    @Override
    protected HttpTransport.Request createRequest() {
        return HttpTransport.Request.get("/events");
    }

    @Override
    protected Void createResult(final HttpTransport.Response response) {

        // create a unique name for the GetSystemEvents thread
        final var uniqueNameGenerator = new UniqueNameGenerator(".");
        final var name = uniqueNameGenerator.next();

        // create a Thread to commence reading the event stream from the response
        final Runnable runnable = () -> {
            // establish the Json-based Subscriber for System Events
            final var jsonSubscriber = new Subscriber<JsonValue>() {
                @Override
                public void onNext(final JsonValue item) {
                    // establish a Context to use for creating Events
                    final var context = createContext();
                    context.bind(JsonValue.class).to(item);

                    // publish "Action" events as ActionEvents
                    if (item.asObject().has("Action")) {
                        final var event = context.create(ActionEvent.class);
                        GetSystemEvents.this.publisher.publish(event);
                    }
                }

                @Override
                public void onError(final Throwable throwable) {
                    GetSystemEvents.this.onProcessingError(throwable);
                }

                @Override
                public void onComplete() {
                    GetSystemEvents.this.onProcessingCompletion();
                }
            };

            // process the entire InputStream from the Response to essentially wait for the image to be created
            final var processor = new JsonNodeInputStreamProcessor(recorder());
            try {
                processor.process(response.bodyStream(), jsonSubscriber);
            } catch (final IOException e) {
                GetSystemEvents.this.onProcessingError(e);
            }
        };

        Thread.ofVirtual()
            .name("docker-system-events-" + name)
            .start(runnable);

        return null;
    }

    @Override
    protected void onSuccessfulRequest(final HttpTransport.Request request,
                                       final HttpTransport.Response response,
                                       final Void result) {
        // no result to capture for a Void command — processing continues via the virtual thread above
    }
}
