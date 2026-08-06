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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link Session.Factory} that uses the <code>DOCKER_HOST</code> environment variable to establish
 * {@link TCPSocketBasedSession}s.
 *
 * @author brian.oliver
 * @since Aug-2022
 */
public class DockerHostVariableBasedSessionFactory
    implements Session.Factory {

    /**
     * The <code>DOCKER_HOST</code> environment variable.
     */
    private final static String DOCKER_HOST = "DOCKER_HOST";

    /**
     * The {@link InetSocketAddress} with which to connect.
     */
    private final AtomicReference<InetSocketAddress> address;

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

    /**
     * Constructs a {@link DockerHostVariableBasedSessionFactory}.
     */
    public DockerHostVariableBasedSessionFactory() {
        this.address = new AtomicReference<>();
    }

    @Override
    public boolean isOperational() {

        // have we already resolved the host address?
        // (we don't need to re-resolve if we have)
        final InetSocketAddress existing = this.address.get();
        if (existing != null) {
            return true;
        }

        // attempt to obtain the DOCKER_HOST as a system property
        String host = System.getProperty(DOCKER_HOST);

        if (host == null || host.isEmpty()) {
            // failing that, attempt to the DOCKER_HOST as a system property
            host = System.getenv(DOCKER_HOST);
        }

        if (host == null || host.isEmpty()) {
            return false;
        }

        try {
            final URI uri = new URI(host);
            final InetSocketAddress address = new InetSocketAddress(uri.getHost(), uri.getPort());

            try (Socket socket = new Socket()) {
                socket.setKeepAlive(false);
                socket.setTcpNoDelay(true);
                socket.connect(address, 0);

                // remember the address as it worked!
                this.address.set(address);

                return true;
            }
        } catch (final Exception __) {
            return false;
        }
    }

    @Override
    public Optional<Session> create(final Configuration configuration) {
        return isOperational()
            ? Optional.of(new TCPSocketBasedSession(
                this.injectionFramework, this.address.get(), configuration, this.telemetryRecorderFactory))
            : Optional.empty();
    }
}
