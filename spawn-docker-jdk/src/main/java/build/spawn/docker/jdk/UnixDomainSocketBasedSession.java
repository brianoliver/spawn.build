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
import build.spawn.docker.Session;
import jakarta.inject.Inject;

import java.io.File;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Optional;

/**
 * A Unix Domain Socket-based {@code Docker} {@link Session}.
 *
 * @author brian.oliver
 * @since Jun-2021
 */
public class UnixDomainSocketBasedSession
    extends AbstractSession {

    /**
     * The {@code docker.sock} {@link File}.
     */
    private static final File DOCKER_SOCK_FILE = resolveDockerSockFile();

    /**
     * Resolves the {@code docker.sock} {@link File} by checking known locations in order of preference.
     *
     * @return the {@code docker.sock} {@link File}
     */
    private static File resolveDockerSockFile() {
        final var candidates = new ArrayList<File>();

        // Docker Desktop on Linux
        candidates.add(new File(System.getProperty("user.home") + "/.docker/desktop/docker.sock"));

        // standard Docker Engine location
        candidates.add(new File("/var/run/docker.sock"));

        return candidates.stream()
            .filter(File::exists)
            .findFirst()
            .orElse(new File("/var/run/docker.sock"));
    }

    /**
     * Constructs a {@link UnixDomainSocketBasedSession} using the default {@code docker.sock} file, recording
     * telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework} for Dependency Injection
     * @param configuration      the {@link Configuration}
     */
    public UnixDomainSocketBasedSession(final InjectionFramework injectionFramework,
                                        final Configuration configuration) {

        this(injectionFramework, DOCKER_SOCK_FILE, configuration);
    }

    /**
     * Constructs a {@link UnixDomainSocketBasedSession} using the default {@code docker.sock} file, recording
     * telemetry via the {@link build.base.telemetry.TelemetryRecorder} produced by the specified
     * {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework} for Dependency Injection
     * @param configuration            the {@link Configuration}
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for the {@link Session}
     */
    public UnixDomainSocketBasedSession(final InjectionFramework injectionFramework,
                                        final Configuration configuration,
                                        final TelemetryRecorderFactory telemetryRecorderFactory) {

        this(injectionFramework, DOCKER_SOCK_FILE, configuration, telemetryRecorderFactory);
    }

    /**
     * Constructs a {@link UnixDomainSocketBasedSession} for the specified Unix socket {@link File}, recording
     * telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework} for Dependency Injection
     * @param socketFile         the Unix socket {@link File}
     * @param configuration      the {@link Configuration}
     */
    public UnixDomainSocketBasedSession(final InjectionFramework injectionFramework,
                                        final File socketFile,
                                        final Configuration configuration) {

        this(injectionFramework, socketFile, configuration, SystemTelemetryRecorder::of);
    }

    /**
     * Constructs a {@link UnixDomainSocketBasedSession} for the specified Unix socket {@link File}, recording
     * telemetry via the {@link build.base.telemetry.TelemetryRecorder} produced by the specified
     * {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework} for Dependency Injection
     * @param socketFile               the Unix socket {@link File}
     * @param configuration            the {@link Configuration}
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for the {@link Session}
     */
    public UnixDomainSocketBasedSession(final InjectionFramework injectionFramework,
                                        final File socketFile,
                                        final Configuration configuration,
                                        final TelemetryRecorderFactory telemetryRecorderFactory) {

        super(injectionFramework, new UnixSocketHttpTransport(socketFile), configuration, telemetryRecorderFactory);
    }

    /**
     * The {@link Session.Factory} for the {@link UnixDomainSocketBasedSession}s.
     */
    public static class Factory
        implements Session.Factory {

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
            try (var _ = SocketChannel.open(UnixDomainSocketAddress.of(DOCKER_SOCK_FILE.toPath()))) {
                return true;
            } catch (final Exception _) {
                return false;
            }
        }

        @Override
        public Optional<Session> create(final Configuration configuration) {
            return isOperational()
                ? Optional.of(new UnixDomainSocketBasedSession(
                    this.injectionFramework, configuration, this.telemetryRecorderFactory))
                : Optional.empty();
        }
    }
}
