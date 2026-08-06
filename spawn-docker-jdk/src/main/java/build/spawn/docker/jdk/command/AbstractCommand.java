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

import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Context;
import build.spawn.docker.jdk.HttpTransport;
import jakarta.inject.Inject;

import java.io.IOException;

/**
 * An abstract {@link Command} that uses an {@link HttpTransport} to submit request(s) and receive responses,
 * that of which may be processed to produce a result.
 *
 * @param <T> the type of result produced by the {@link Command}
 * @author brian.oliver
 * @since Jun-2021
 */
public abstract class AbstractCommand<T>
    implements Command<T> {

    /**
     * The {@link HttpTransport} to use for executing the {@link Command}.
     */
    @Inject
    private HttpTransport transport;

    /**
     * The dependency injection {@link Context} for the {@link Command}.
     */
    @Inject
    private Context context;

    /**
     * The {@link TelemetryRecorder} used to record diagnostics for the {@link Command}.
     */
    @Inject
    private TelemetryRecorder recorder;

    /**
     * Obtains the {@link HttpTransport} to use for executing {@link Command}s.
     *
     * @return the {@link HttpTransport}
     */
    protected HttpTransport transport() {
        return this.transport;
    }

    /**
     * Obtains the {@link TelemetryRecorder} to use for recording diagnostics for the {@link Command}.
     *
     * @return the {@link TelemetryRecorder}
     */
    protected TelemetryRecorder recorder() {
        return this.recorder;
    }

    /**
     * Creates a new dependency injection {@link Context}, based on the {@link Context} used to create the
     * {@link Command}.
     *
     * @return a new {@link Context}
     */
    protected Context createContext() {
        return this.context.newContext();
    }

    /**
     * Obtains the {@link HttpTransport.Request} to execute for the {@link Command}.
     *
     * @return the {@link HttpTransport.Request}
     */
    abstract protected HttpTransport.Request createRequest();

    /**
     * Creates the result for the {@link Command} based on the successful {@link HttpTransport.Response}.
     *
     * @param response the {@link HttpTransport.Response}
     * @return the result
     * @throws IOException should processing the response fail
     */
    abstract protected T createResult(HttpTransport.Response response)
        throws IOException;

    /**
     * Handle when the {@link HttpTransport.Request} has been created for the {@link Command}, but not yet sent.
     *
     * @param request the {@link HttpTransport.Request}
     */
    abstract protected void onRequestCreated(HttpTransport.Request request);

    /**
     * Handle when the {@link HttpTransport.Response} has been received for the {@link Command}.
     *
     * @param response the {@link HttpTransport.Response}
     */
    abstract protected void onResponseReceived(HttpTransport.Response response);

    /**
     * Handle when the execution of a {@link HttpTransport.Request} was successful.
     *
     * @param request  the {@link HttpTransport.Request}
     * @param response the {@link HttpTransport.Response}
     * @param result   the result
     */
    abstract protected void onSuccessfulRequest(HttpTransport.Request request,
                                                HttpTransport.Response response,
                                                T result);

    /**
     * Handle when the execution of a {@link HttpTransport.Request} was unsuccessful.
     *
     * @param request  the {@link HttpTransport.Request}
     * @param response the {@link HttpTransport.Response}
     * @throws IOException should the request be unsuccessful
     */
    protected void onUnsuccessfulRequest(final HttpTransport.Request request,
                                         final HttpTransport.Response response)
        throws IOException {

        throw new IOException("Request failed with " + response.statusCode()
            + ". Failed to execute " + request.method() + " " + request.path());
    }

    /**
     * Handle when the execution of a {@link HttpTransport.Request} failed with the specified {@link Throwable}.
     *
     * @param request   the {@link HttpTransport.Request}
     * @param throwable the {@link Throwable}
     * @return the result if the request was recoverable
     */
    protected T onRequestFailed(final HttpTransport.Request request,
                                final Throwable throwable) {

        throw (throwable instanceof RuntimeException)
            ? (RuntimeException) throwable
            : new RuntimeException("Request Failed", throwable);
    }

    /**
     * Handle when attempting to process the {@link HttpTransport.Response} failed.
     *
     * @param request   the {@link HttpTransport.Request}
     * @param response  the {@link HttpTransport.Response}
     * @param throwable the {@link Throwable}
     */
    public void onUnprocessableResponse(final HttpTransport.Request request,
                                        final HttpTransport.Response response,
                                        final Throwable throwable) {
        response.close();
    }

    @Override
    public T submit() {
        final var request = createRequest();

        try {
            onRequestCreated(request);

            final var response = transport().execute(request);

            onResponseReceived(response);

            try {
                if (!response.isSuccessful()) {
                    onUnsuccessfulRequest(request, response);
                }

                final var result = createResult(response);

                onSuccessfulRequest(request, response, result);

                return result;

            } catch (final Throwable throwable) {
                onUnprocessableResponse(request, response, throwable);
                throw throwable;
            }

        } catch (final Throwable recoverable) {
            return onRequestFailed(request, recoverable);
        }
    }
}
