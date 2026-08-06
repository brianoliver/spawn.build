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
import build.base.flow.CompletingSubscriber;
import build.base.flow.Publicist;
import build.base.flow.Publisher;
import build.base.flow.SubscriberRegistry;
import build.base.json.JsonObject;
import build.base.option.Email;
import build.base.option.Password;
import build.base.option.Username;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.codemodel.dependency.injection.ConfigurationResolver;
import build.codemodel.dependency.injection.Context;
import build.codemodel.dependency.injection.InjectionFramework;
import build.spawn.docker.Event;
import build.spawn.docker.Image;
import build.spawn.docker.Images;
import build.spawn.docker.Network;
import build.spawn.docker.Networks;
import build.spawn.docker.Session;
import build.spawn.docker.jdk.command.Authenticate;
import build.spawn.docker.jdk.command.BuildImage;
import build.spawn.docker.jdk.command.Command;
import build.spawn.docker.jdk.command.CreateNetwork;
import build.spawn.docker.jdk.command.DeleteNetwork;
import build.spawn.docker.jdk.command.GetSystemEvents;
import build.spawn.docker.jdk.command.GetSystemInformation;
import build.spawn.docker.jdk.command.InspectImage;
import build.spawn.docker.jdk.command.InspectNetwork;
import build.spawn.docker.jdk.command.PullImage;
import build.spawn.docker.jdk.model.DockerImage;
import build.spawn.docker.option.DockerAPIVersion;
import build.spawn.docker.option.DockerRegistry;
import build.spawn.docker.option.IdentityToken;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * An abstract {@link Session} implementation.
 *
 * @author brian.oliver
 * @since June-2021
 */
public class AbstractSession
    implements Session, Images {

    /**
     * The {@link Configuration} for the {@link Session}.
     */
    private final Configuration configuration;

    /**
     * The {@link HttpTransport} for communicating with the Docker Engine.
     */
    private final HttpTransport transport;

    /**
     * The dependency injection {@link Context} to use for creating {@link Command}s.
     */
    private final Context context;

    /**
     * The {@link Publicist} for {@code Docker Engine} {@link Event}s that have been received.
     */
    private final Publicist<Event> publicist;

    /**
     * The {@linl CompletingSubscriber} for {@code Docker Engine} {@link Event}s.}
     */
    private final CompletingSubscriber<Event> eventSubscriber;

    /**
     * The {@code Docker Engine} Server Version.
     */
    private final String serverVersion;

    /**
     * The implementation of {@link Networks}.
     */
    private final Networks networks = new NetworksImpl();

    /**
     * The {@link GetSystemEvents} {@link Command} to control the processing of {@code Docker Engine} {@link Event}s
     * for the {@link Session}.
     */
    private final GetSystemEvents systemEvents;

    /**
     * Constructs an {@link AbstractSession} using the specified {@link HttpTransport} and {@link Configuration},
     * recording telemetry via a {@link SystemTelemetryRecorder}.
     *
     * @param injectionFramework the {@link InjectionFramework} to use for {@link build.codemodel.dependency.injection.Dependency} injection
     * @param transport          the {@link HttpTransport} for communicating with the Docker Engine
     * @param configuration      the {@link Configuration}
     */
    protected AbstractSession(final InjectionFramework injectionFramework,
                              final HttpTransport transport,
                              final Configuration configuration) {

        this(injectionFramework, transport, configuration, SystemTelemetryRecorder::of);
    }

    /**
     * Constructs an {@link AbstractSession} using the specified {@link HttpTransport}, {@link Configuration}
     * and {@link TelemetryRecorderFactory}.
     *
     * @param injectionFramework       the {@link InjectionFramework} to use for
     *                                 {@link build.codemodel.dependency.injection.Dependency} injection
     * @param transport                the {@link HttpTransport} for communicating with the Docker Engine
     * @param configuration            the {@link Configuration}
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link TelemetryRecorder} for the {@link Session}
     */
    @SuppressWarnings("unchecked")
    protected AbstractSession(final InjectionFramework injectionFramework,
                              final HttpTransport transport,
                              final Configuration configuration,
                              final TelemetryRecorderFactory telemetryRecorderFactory) {

        Objects.requireNonNull(injectionFramework, "The InjectionFramework must not be null");
        Objects.requireNonNull(transport, "The HttpTransport must not be null");
        Objects.requireNonNull(telemetryRecorderFactory, "The TelemetryRecorderFactory must not be null");

        this.transport = transport;

        final TelemetryRecorder recorder = telemetryRecorderFactory
            .apply(URI.create("spawn://" + getClass().getSimpleName()));

        this.configuration = configuration == null
            ? Configuration.empty()
            : configuration;

        // include the DockerAPIVersion (if it's defined)
        this.configuration.getOptional(DockerAPIVersion.class)
            .ifPresent(DockerAPIVersion::get);

        // establish the Publicist for Docker Engine Events for the Connection
        this.publicist = new SubscriberRegistry<>();

        // establish the CompletingSubscriber allowing CompletableFutures to be completed
        // when required Docker Events are Observed
        this.eventSubscriber = new CompletingSubscriber<>();
        this.publicist.subscribe(this.eventSubscriber);

        // establish the dependency injection context
        this.context = injectionFramework
            .newContext();

        this.context.addResolver(ConfigurationResolver.of(configuration));

        this.context.bind(HttpTransport.class).to(this.transport);
        this.context.bind(Session.class).to(this);
        this.context.bind(AbstractSession.class).to(this);
        this.context.bind((Class) getClass()).to(this);
        this.context.bind(Configuration.class).to(this.configuration);
        this.context.bind(Publicist.class).to(this.publicist);
        this.context.bind(Publisher.class).to(this.publicist);
        this.context.bind(CompletingSubscriber.class).to(this.eventSubscriber);
        this.context.bind(TelemetryRecorder.class).to(recorder);

        // attempt to authenticate (when there's a Username, Password, and DockerRegistry) and capture an IdentityToken
        final Optional<String> xRegistryAuth;

        if (this.configuration.isPresent(Username.class)
            && this.configuration.isPresent(Password.class)
            && this.configuration.isPresent(DockerRegistry.class)) {

            // attempt to authenticate
            final var identityToken = createContext()
                .create(Authenticate.class)
                .submit();

            // bind the IdentityToken obtained (even though it may be empty!)
            this.context.bind(IdentityToken.class)
                .to(identityToken);

            // establish the Authentication JSON
            final var jsonBuilder = JsonObject.builder();

            if (identityToken.isEmpty()) {
                this.configuration.getOptionalValue(Username.class)
                    .ifPresent(username -> jsonBuilder.put("username", username));
                this.configuration.getOptionalValue(Password.class)
                    .ifPresent(password -> jsonBuilder.put("password", password));
                this.configuration.getOptionalValue(Email.class)
                    .ifPresent(email -> jsonBuilder.put("email", email));
                this.configuration.getOptionalValue(DockerRegistry.class)
                    .ifPresent(url -> jsonBuilder.put("serveraddress", url.getHost()));
            }
            else {
                jsonBuilder.put("identitytoken", identityToken.get());
            }

            xRegistryAuth = Optional.of(jsonBuilder.build().toJsonString());
        }
        else {
            // determine the Configuration-provided IdentityToken
            final var identityToken = this.configuration
                .getOptional(IdentityToken.class)
                .orElse(IdentityToken.empty());

            // obtain the IdentityToken
            this.context.bind(IdentityToken.class)
                .to(identityToken);

            // capture the non-empty IdentityToken for authentication
            if (identityToken.isEmpty()) {
                xRegistryAuth = Optional.empty();
            }
            else {
                xRegistryAuth = Optional.of(
                    JsonObject.builder()
                        .put("identitytoken", identityToken.get())
                        .build()
                        .toJsonString());
            }
        }

        // establish the Authenticator for the Session
        this.context
            .bind(Authenticator.class)
            .to(xRegistryAuth
                .map(json -> {
                    final var encoded = new String(Base64
                        .getEncoder()
                        .encode(json.getBytes(StandardCharsets.UTF_8)));

                    return (Authenticator) builder -> builder.withHeader("X-Registry-Auth", encoded);
                })
                .orElse(Authenticator.NONE));

        // subscribe to the Docker Engine system events
        // (we can then use these to notify our local representations of events,
        //  eg: container starting/stopping/dying etc!)
        // (note: if we can't successfully do this, we can't use the Connection)
        this.systemEvents = createContext()
            .create(GetSystemEvents.class);

        this.systemEvents
            .submit();

        // obtain the SystemInformation from the Docker Engine
        // (from which we can obtain information for compatibility checking)
        final var info = createContext()
            .create(GetSystemInformation.class)
            .submit();

        this.serverVersion = info.getServerVersion();
    }

    /**
     * Creates a new dependency injection {@link Context}.
     *
     * @return a new {@link Context}
     */
    protected Context createContext() {
        final Context context = this.context.newContext();
        context.bind(Context.class).to(context);
        return context;
    }

    @Override
    public Images images() {
        return this;
    }

    @Override
    public Networks networks() {
        return this.networks;
    }

    @Override
    public Publisher<Event> events() {
        return this.publicist;
    }

    @Override
    public Information inspect() {
        return createContext()
            .create(GetSystemInformation.class)
            .submit();
    }

    @Override
    public Optional<Image> get(final String nameOrId) {
        return get(nameOrId, this.configuration);
    }

    @Override
    public Optional<Image> get(final String nameOrId, final Configuration configuration) {
        return createContext()
            .inject(new InspectImage(nameOrId, configuration))
            .submit()
            .map(info -> createContext()
                .inject(new DockerImage(info.imageId())));
    }

    @Override
    public Optional<Image> pull(final String nameOrId, final Configuration configuration) {
        // If options are passed in, use them, if not, use the options for the session
        return createContext()
            .inject(new PullImage(nameOrId, configuration))
            .submit()
            .flatMap(s -> get(s, configuration));
    }

    @Override
    public Optional<Image> pull(final String nameOrId) {
        return pull(nameOrId, this.configuration);
    }

    @Override
    public Optional<Image> build(final Path contextPath,
                                 final Configuration configuration) {

        return createContext()
            .inject(new BuildImage(contextPath, configuration))
            .submit()
            .flatMap(this::get);
    }

    @Override
    public void close() {
        // complete publishing Events
        this.publicist.complete();

        // close the reading of System Events
        this.systemEvents.close();

        // HttpTransport implementations manage their own connection lifecycle
    }

    class NetworksImpl
        implements Networks {

        @Override
        public Optional<Network.Information> inspect(final String nameOrId) {
            return createContext()
                .inject(new InspectNetwork(nameOrId))
                .submit();
        }

        @Override
        public Optional<Network.Information> create(final String name) {
            return createContext()
                .inject(new CreateNetwork(name, Configuration.empty()))
                .submit()
                .flatMap(this::inspect);
        }

        @Override
        public boolean delete(final String name) {
            return createContext()
                .inject(new DeleteNetwork(name))
                .submit();
        }
    }
}
