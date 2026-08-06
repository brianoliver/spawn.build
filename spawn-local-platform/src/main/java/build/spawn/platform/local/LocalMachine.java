package build.spawn.platform.local;

/*-
 * #%L
 * Spawn Local Platform
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

import build.base.configuration.ConfigurationBuilder;
import build.base.configuration.Option;
import build.base.network.EphemeralPortSupplier;
import build.base.network.Network;
import build.base.network.PortSupplier;
import build.base.option.TemporaryDirectory;
import build.base.option.WorkingDirectory;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.spawn.application.AbstractTemplatedPlatform;
import build.spawn.application.Machine;

import java.net.InetAddress;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * The local {@link Machine}.
 *
 * @author brian.oliver
 * @since Oct-2018
 */
public class LocalMachine
    extends AbstractTemplatedPlatform
    implements Machine {

    /**
     * The default {@link LocalMachine}.
     */
    private static final LocalMachine MACHINE = new LocalMachine();

    /**
     * The {@link PortSupplier} for this {@link LocalMachine}.
     */
    private final PortSupplier portSupplier;

    /**
     * The process id of the Virtual Machine in which the {@link LocalMachine} was created.
     */
    private final long pid;

    /**
     * Constructs a {@link LocalMachine} without any {@link Option}s.
     */
    public LocalMachine() {
        this(new Option[] {});
    }

    /**
     * Constructs a {@link LocalMachine} using the specified {@link Option}s, recording telemetry via a
     * {@link SystemTelemetryRecorder}.
     *
     * @param options the {@link Option}s
     */
    public LocalMachine(final Option... options) {
        this(SystemTelemetryRecorder::of, options);
    }

    /**
     * Constructs a {@link LocalMachine} using the specified {@link TelemetryRecorderFactory} and {@link Option}s,
     * recording telemetry via the {@link build.base.telemetry.TelemetryRecorder} produced by the specified
     * {@link TelemetryRecorderFactory}.
     *
     * @param telemetryRecorderFactory the {@link TelemetryRecorderFactory} used to create the
     *                                 {@link build.base.telemetry.TelemetryRecorder} for this {@link LocalMachine}
     * @param options                  the {@link Option}s
     */
    public LocalMachine(final TelemetryRecorderFactory telemetryRecorderFactory, final Option... options) {

        super("Local", ConfigurationBuilder.create(options)
            .computeIfNotPresent(WorkingDirectory.class, WorkingDirectory::current)
            .computeIfNotPresent(TemporaryDirectory.class, TemporaryDirectory::current)
            .build(),
            telemetryRecorderFactory);

        // establish a PortSupplier for the LocalMachine
        this.portSupplier = EphemeralPortSupplier.create();

        // attempt to detect the pid of the local Java Virtual Machine
        final var processName = java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .getName();

        try {
            this.pid = Long.parseLong(processName.split("@")[0]);
        }
        catch (final NumberFormatException e) {
            throw new RuntimeException(
                "Failed to determine the pid for the LocalMachine from [" + processName + "]");
        }
    }

    @Override
    public Stream<InetAddress> addresses() {
        return Network.reachableLocalAddresses();
    }

    /**
     * Obtains a {@link PortSupplier} which provides free ports on the {@link LocalMachine}.
     *
     * @return the {@link PortSupplier} for the {@link LocalMachine}.
     */
    public PortSupplier ports() {
        return this.portSupplier;
    }

    /**
     * Obtains the process id of the Java Virtual Machine in which the {@link LocalMachine} was created.
     *
     * @return the {@link OptionalLong}
     */
    public long pid() {
        return this.pid;
    }

    /**
     * Obtains the default {@link LocalMachine}.
     *
     * @return the default {@link LocalMachine}
     */
    public static LocalMachine get() {
        return MACHINE;
    }
}
