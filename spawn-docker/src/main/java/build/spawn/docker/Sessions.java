package build.spawn.docker;

/*-
 * #%L
 * Spawn Docker (Client)
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
import build.base.configuration.Option;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.codemodel.dependency.injection.InjectionFramework;

import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Support for creating and using {@link Session}s.
 *
 * @author brian.oliver
 * @since Aug-2021
 */
public class Sessions {

    /**
     * Private Constructor for {@link Sessions}.
     */
    private Sessions() {
    }

    /**
     * Obtains the discovered {@link Session.Factory}s, recording telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework} to use for Dependency Injection
     * @return a {@link Stream} of the discovered {@link Session.Factory}s
     */
    public static Stream<? extends Session.Factory> factories(final InjectionFramework injectionFramework) {
        return factories(injectionFramework, SystemTelemetryRecorder::of);
    }

    /**
     * Obtains the discovered {@link Session.Factory}s, recording telemetry via the
     * {@link build.base.telemetry.TelemetryRecorder} produced by the specified {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework} to use for Dependency Injection
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for {@link Session}s
     *                                 produced by the discovered {@link Session.Factory}s
     * @return a {@link Stream} of the discovered {@link Session.Factory}s
     */
    public static Stream<? extends Session.Factory> factories(final InjectionFramework injectionFramework,
                                                               final TelemetryRecorderFactory telemetryRecorderFactory) {

        Objects.requireNonNull(injectionFramework, "The InjectionFramework must not be null");
        Objects.requireNonNull(telemetryRecorderFactory, "The TelemetryRecorderFactory must not be null");

        final var serviceLoaderClassLoader = Sessions.class.getClassLoader();
        final var serviceLoader = ServiceLoader.load(Session.Factory.class, serviceLoaderClassLoader);

        final var context = injectionFramework
            .newContext();

        context.bind(InjectionFramework.class).to(injectionFramework);
        context.bind(TelemetryRecorderFactory.class).to(telemetryRecorderFactory);

        return serviceLoader.stream()
            .map(provider -> {
                try {
                    final var sessionFactory = provider.get();
                    return context.inject(sessionFactory);
                }
                catch (final Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull);
    }

    /**
     * Attempt to create a {@link Session} using the first discovered {@link Session.Factory} that can produce a
     * {@link Session} using the provided {@link Option}s, recording telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework} to use for Dependency Injection
     * @param configuration      the {@link Session} {@link Configuration}s
     * @return the {@link Optional} {@link Session} or {@link Optional#empty()} should it not be possible to
     * create a {@link Session}
     */
    public static Optional<Session> createSession(final InjectionFramework injectionFramework,
                                                  final Configuration configuration) {

        return createSession(injectionFramework, configuration, SystemTelemetryRecorder::of);
    }

    /**
     * Attempt to create a {@link Session} using the first discovered {@link Session.Factory} that can produce a
     * {@link Session} using the provided {@link Option}s, recording telemetry via the
     * {@link build.base.telemetry.TelemetryRecorder} produced by the specified {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework} to use for Dependency Injection
     * @param configuration            the {@link Session} {@link Configuration}s
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for the resulting
     *                                 {@link Session}
     * @return the {@link Optional} {@link Session} or {@link Optional#empty()} should it not be possible to
     * create a {@link Session}
     */
    public static Optional<Session> createSession(final InjectionFramework injectionFramework,
                                                  final Configuration configuration,
                                                  final TelemetryRecorderFactory telemetryRecorderFactory) {

        Objects.requireNonNull(injectionFramework, "The InjectionFramework must not be null");

        return factories(injectionFramework, telemetryRecorderFactory)
            .filter(Session.Factory::isOperational)
            .map(factory -> factory.create(configuration))
            .filter(Optional::isPresent)
            .findFirst()
            .map(Optional::orElseThrow);
    }

    /**
     * Attempt to create a {@link Session} using the first discovered {@link Session.Factory} that can produce a
     * {@link Session} using the provided {@link Option}s.
     *
     * @param injectionFramework the {@link InjectionFramework} to use for Dependency Injection
     * @param options            the {@link Session} {@link Option}s
     * @return the {@link Optional} {@link Session} or {@link Optional#empty()} should it not be possible to
     * create a {@link Session}
     */
    public static Optional<Session> createSession(final InjectionFramework injectionFramework,
                                                  final Option... options) {

        return createSession(injectionFramework, Configuration.of(options));
    }
}
