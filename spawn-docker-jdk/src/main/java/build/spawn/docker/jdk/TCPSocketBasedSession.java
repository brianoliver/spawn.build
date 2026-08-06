package build.spawn.docker.jdk;

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
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.codemodel.dependency.injection.InjectionFramework;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * A TCP-socket-based {@code Docker} {@link build.spawn.docker.Session}.
 *
 * @author brian.oliver
 * @since Aug-2022
 */
public class TCPSocketBasedSession
    extends AbstractSession {

    /**
     * Constructs a {@link TCPSocketBasedSession} for the specified {@link InetSocketAddress}, recording
     * telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework}
     * @param socketAddress      the {@link InetSocketAddress}
     * @param configuration      the {@link Configuration}
     */
    public TCPSocketBasedSession(final InjectionFramework injectionFramework,
                                 final InetSocketAddress socketAddress,
                                 final Configuration configuration) {

        this(injectionFramework, socketAddress, configuration, SystemTelemetryRecorder::of);
    }

    /**
     * Constructs a {@link TCPSocketBasedSession} for the specified {@link InetSocketAddress}, recording
     * telemetry via the {@link build.base.telemetry.TelemetryRecorder} produced by the specified
     * {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework}
     * @param socketAddress            the {@link InetSocketAddress}
     * @param configuration            the {@link Configuration}
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for the
     *                                 {@link build.spawn.docker.Session}
     */
    public TCPSocketBasedSession(final InjectionFramework injectionFramework,
                                 final InetSocketAddress socketAddress,
                                 final Configuration configuration,
                                 final TelemetryRecorderFactory telemetryRecorderFactory) {

        super(injectionFramework,
            new JavaHttpClientTransport(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(),
                "http://" + socketAddress.getHostString() + ":" + socketAddress.getPort()),
            configuration,
            telemetryRecorderFactory);
    }
}
