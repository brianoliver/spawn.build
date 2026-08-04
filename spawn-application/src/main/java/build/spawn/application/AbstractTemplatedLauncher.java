package build.spawn.application;

/*-
 * #%L
 * Spawn Application
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

import build.base.commandline.CommandLine;
import build.base.configuration.Configuration;
import build.base.configuration.ConfigurationBuilder;
import build.base.naming.UniqueNameGenerator;
import build.base.option.TemporaryDirectory;
import build.base.option.WorkingDirectory;
import build.base.table.Table;
import build.base.table.Tabular;
import build.base.table.option.CellSeparator;
import build.base.table.option.RowComparator;
import build.base.table.option.TableName;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Binding;
import build.codemodel.dependency.injection.Context;
import build.codemodel.dependency.injection.Dependency;
import build.codemodel.dependency.injection.Resolver;
import build.codemodel.dependency.injection.ValueBinding;
import build.codemodel.foundation.usage.GenericTypeUsage;
import build.spawn.application.facet.Facet;
import build.spawn.application.facet.Faceted;
import build.spawn.application.option.Argument;
import build.spawn.application.option.DiagnosticName;
import build.spawn.application.option.DiagnosticNameProvider;
import build.spawn.application.option.Executable;
import build.spawn.application.option.Name;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An abstract {@link TemplatedLauncher}.
 *
 * @param <A> the type of {@link Application} that will be launched
 * @param <P> the type of {@link Platform} on which the {@link Application} will be launched
 * @param <N> the type of {@link java.lang.Process} representing managing the {@link Application} when it's launched
 * @author graeme.campbell
 * @since Apr-2019
 */
public abstract class AbstractTemplatedLauncher<A extends Application, P extends Platform, N extends Process>
    implements TemplatedLauncher<A, P, N> {

    /**
     * The {@link TelemetryRecorder}.
     */
    @Inject
    protected TelemetryRecorder recorder;

    /**
     * A {@link UniqueNameGenerator} to generate unique {@link Application} names.
     */
    @Inject
    protected UniqueNameGenerator uniqueNameGenerator;

    /**
     * The {@link Table} used to log diagnostic information when launching and closing an {@link Application}.
     */
    @Inject
    protected Table diagnostics;

    /**
     * The {@link Platform} supplied dependency injection {@link Context}.
     */
    @Inject
    protected Context platformContext;

    @Override
    public Name getName(final ConfigurationBuilder options) {
        return getExecutable(options)
            .map(Executable::get)
            .map(Name::of)
            .orElseGet(() -> Name.of(this.uniqueNameGenerator.next()
                .toLowerCase()
                .replace(' ', '.')));
    }

    @Override
    @SuppressWarnings("unchecked")
    public A launch(final P platform,
                    final Class<? extends A> applicationClass,
                    final Configuration configuration) {

        Objects.requireNonNull(platform, "The Platform must not be null");
        Objects.requireNonNull(applicationClass, "The Application Class must not be null");
        Objects.requireNonNull(configuration, "The Configuration must not be null");

        // establish a dependency injection context for launching
        final var launchContext = this.platformContext.newContext();

        // establish a ConfigurationBuilder capturing the launch Options
        final var launchOptions = ConfigurationBuilder.create()
            .include(configuration);

        // establish a table to capture launch diagnostics information
        this.diagnostics.options().add(RowComparator.orderByColumn(0));
        this.diagnostics.options().add(CellSeparator.of(" : "));
        this.diagnostics.addRow("Platform", platform.name());

        // discover the Application "Implementation" class
        final Class<? extends Application> implementationClass = Application
            .getImplementationClass(applicationClass);

        this.diagnostics.addRow("Application Class", applicationClass.getCanonicalName());
        this.diagnostics.addRow("Application Implementation", implementationClass.getCanonicalName());

        // TODO: complete for Java-based Debugging (this should be part of the JDKApplication)
        //        // automatically enable remote debugging for launched application when the current JVM is in debug mode
        //        launchOptions.addIfNotPresent(Debugging.class, Debugging::autoDetect);

        // determine the initial customizers to prepare the application
        // (we start with those that have been specified in the OptionsByType,
        // but more may be added as the Application is prepared)
        final LinkedHashSet<Customizer<A>> customizersToPrepare = launchOptions
            .stream(Customizer.class)
            .map(customizer -> (Customizer<A>) customizer)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // the customizers that have been used to prepare the application
        final LinkedHashSet<Customizer<A>> customizers = new LinkedHashSet<>();

        // allow each discovered customizer to prepare the application,
        // and allow newly introduced customizers introduced during preparation to prepare as well!
        while (!customizersToPrepare.isEmpty()) {
            // remove the next customizer
            final Iterator<Customizer<A>> iterator = customizersToPrepare.iterator();
            final Customizer<A> customizer = iterator.next();
            iterator.remove();

            // notify the customizer to prepare the application (if it hasn't done so already)
            if (!customizers.contains(customizer)) {
                customizer.onPreparing(platform, applicationClass, launchOptions);
                customizers.add(customizer);
            }

            // look for newly introduced customizers that may have been added to the OptionsByType
            launchOptions
                .stream(Customizer.class)
                .filter(c -> !customizers.contains(c))
                .forEach(customizersToPrepare::add);
        }

        // notify the customizers we're about to launch the application
        customizers.forEach(customizer -> customizer.onLaunching(platform, applicationClass, launchOptions));

        // convert any CommandLine options to arguments and add them
        launchOptions.stream(CommandLine.class)
            .flatMap(CommandLine::arguments)
            .map(Argument::of)
            .forEach(launchOptions::add);

        // establish a Name for the Application
        launchOptions.computeIfNotPresent(Name.class, () -> getName(launchOptions));
        final var name = launchOptions.get(Name.class);

        // establish a DiagnosticName for the Application given the launch options
        final DiagnosticName diagnosticName = launchOptions
            .get(DiagnosticNameProvider.class)
            .create(launchOptions, name);

        launchOptions.add(diagnosticName);

        // determine the Executable to launch
        getExecutable(launchOptions)
            .ifPresent(executable -> launchOptions.addIfNotPresent(Executable.class, executable));

        // establish the WorkingDirectory (when defined)
        launchOptions.computeIfNotPresent(
            WorkingDirectory.class,
            () -> platform.configuration().get(WorkingDirectory.class));

        // establish the TemporaryDirectory
        launchOptions.computeIfNotPresent(
            TemporaryDirectory.class,
            () -> platform.configuration().get(TemporaryDirectory.class));

        // --- establish the diagnostics table for the Tabular Options ---

        // establish the map of tables, keyed by type of Tabular Option
        final HashMap<Class<? extends Tabular>, Table> tables = new HashMap<>();

        // establish tables for each of the types of Tabular Options
        // (those that don't supply a table, will use the diagnostics table)
        // and tabularize each of the Tabular Options into their respective tables
        launchOptions.stream(Tabular.class)
            .filter(Tabular::hasTabularContent)
            .sequential()
            .peek(tabular ->
                tables.computeIfAbsent(tabular.getClass(),
                    tabularClass -> tabular.getTableSupplier().orElse(() -> this.diagnostics).get()))
            .forEach(tabular -> tabular.tabularize(tables.get(tabular.getClass())));

        // add the non-diagnostic tables, into the diagnostics table (using their respective TableName)
        tables.entrySet().stream()
            .filter(entry -> entry.getValue() != this.diagnostics)
            .forEach(entry -> {
                final Class<? extends Tabular> tabularClass = entry.getKey();
                final Table table = entry.getValue();
                table.options()
                    .computeIfNotPresent(
                        TableName.class,
                        () -> TableName.of(tabularClass.getSimpleName()));

                final TableName tableName = table.options().get(TableName.class);

                this.diagnostics.addRow(tableName.get(), table.toString());
            });

        // allow the launch Configuration to be injected
        final var launchConfiguration = launchOptions.build();
        launchContext.bind(Configuration.class).to(launchConfiguration);

        // create the process for the Application
        final N process = createProcess(platform, launchOptions);

        // allow the process interfaces to be used for injection
        launchContext.bind((Class) process.getClass()).to(process);
        launchContext.bind(process).asAllInterfaces(i -> !i.equals(Addressable.class));

        // instantiate individual facets
        final Set<Facet<?>> facets = launchOptions.stream(Facet.class)
            .map(facet -> (Facet<?>) facet)
            .map(facet -> {
                final Object implementation = facet.getFactory().apply(launchContext);

                // allow injection of facets individually and as Iterable<T>
                launchContext.bind((Class<Object>) facet.getInterface()).to(implementation);
                launchContext.bindSet((Class<Object>) facet.getInterface()).add(implementation);

                // create a synthetic Facet that always resolves to the same implementation
                return Facet.of(facet.getInterface(), _ -> facet.getInterface().cast(implementation));
            })
            .collect(Collectors.toSet());

        // fallback: return an empty Iterable<T> for any T not populated via bindSet above
        launchContext.addResolver(new Resolver<Iterable<Object>>() {
            @Override
            public Optional<? extends Binding<Iterable<Object>>> resolve(final Dependency dependency) {
                if (dependency.typeUsage() instanceof GenericTypeUsage genericTypeUsage
                    && genericTypeUsage.typeName().canonicalName().equals(Iterable.class.getCanonicalName())
                    && genericTypeUsage.parameters().count() == 1) {
                    return Optional.of(new ValueBinding<Iterable<Object>>() {
                        @Override
                        public Iterable<Object> value() {
                            return List.of();
                        }

                        @Override
                        public Dependency dependency() {
                            return dependency;
                        }
                    });
                }

                return Optional.empty();
            }
        });

        // --- create the application ---
        final Facet<A> applicationFacet = Facet.of(
            (Class<A>) applicationClass,
            (Class<? extends A>) implementationClass);

        final Facet<?>[] allFacets = Stream.concat(
                Stream.of(applicationFacet),
                facets.stream())
            .toArray(Facet[]::new);

        // --- create the faceted wrapper around all facets ---
        final A implementation = (A) Faceted.create(launchContext, allFacets);
        customizers.forEach(customizer -> customizer.onLaunched(platform, applicationClass, implementation));

        return implementation;
    }
}
