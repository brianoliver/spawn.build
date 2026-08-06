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
import build.codemodel.dependency.injection.InjectionFramework;
import build.spawn.docker.Session;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;

/**
 * The {@link Session.Factory} for <code>localhost</code> based {@link TCPSocketBasedSession}s.
 *
 * @author brian.oliver
 * @since Aug-2022
 */
public class LocalHostBasedSessionFactory
    implements Session.Factory {

    /**
     * The {@link InetSocketAddress} for the <code>localhost</code>-based Docker Daemon.
     */
    private final static InetSocketAddress ADDRESS = new InetSocketAddress("localhost", 2375);

    /**
     * The {@link InjectionFramework} to use for Dependency Injection.
     */
    @Inject
    private InjectionFramework injectionFramework;

    /**
     * The {@link TelemetryRecorderFactory} used to create the {@link build.base.telemetry.TelemetryRecorder}
     * for {@link Session}s produced by this {@link Session.Factory}.
     */
    @Inject
    private TelemetryRecorderFactory telemetryRecorderFactory;

    @Override
    public boolean isOperational() {
        try (Socket socket = new Socket()) {
            socket.setKeepAlive(false);
            socket.setTcpNoDelay(true);
            socket.connect(ADDRESS, 0);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    @Override
    public Optional<Session> create(final Configuration configuration) {

        return isOperational()
            ? Optional.of(new TCPSocketBasedSession(
                this.injectionFramework, ADDRESS, configuration, this.telemetryRecorderFactory))
            : Optional.empty();
    }
}
