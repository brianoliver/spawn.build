package build.spawn.platform.local.jdk;

/*-
 * #%L
 * Spawn Local JDK
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

import build.base.foundation.Exceptional;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.spawn.jdk.Architecture;
import build.spawn.jdk.JDK;
import build.spawn.jdk.OperatingSystem;
import build.spawn.jdk.option.JDKHome;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects the locally installed, operational and available {@link JDK}s.
 *
 * @author brian.oliver
 * @since Nov-2019
 */
public interface JDKDetector {

    /**
     * Obtains a {@link Stream} of candidate {@link java.nio.file.Path}s for {@link JDK} installations,
     * based on OS-specific glob patterns — without launching any subprocesses to verify them.
     * <p>
     * This is the cheap, fast phase of detection. Callers that only need to know whether any
     * {@link JDK}s are likely present (e.g. capability checks) should prefer this over
     * {@link #detect()}, which reads each candidate's {@code release} metadata file.
     *
     * @return a {@link Stream} of candidate {@link java.nio.file.Path}s
     */
    default Stream<Path> paths() {
        return Stream.empty();
    }

    /**
     * Obtains a {@link Stream} of the available {@link JDK}s.
     *
     * @return a {@link Stream} of {@link JDK}s
     */
    Stream<JDK> detect();

    /**
     * Obtains a {@link Stream} of the available {@link JDK}s built for the specified {@link OperatingSystem}
     * and {@link Architecture}, e.g. to locate a foreign {@link JDK} staged for cross-target {@code jlink}ing.
     *
     * @param operatingSystem the required {@link OperatingSystem}
     * @param architecture    the required {@link Architecture}
     * @return a {@link Stream} of matching {@link JDK}s
     */
    default Stream<JDK> detect(final OperatingSystem operatingSystem, final Architecture architecture) {
        return detect()
            .filter(jdk -> jdk.operatingSystem() == operatingSystem && jdk.architecture() == architecture);
    }

    /**
     * Obtains a {@link Stream} of the {@link JDKDetector}s that are available.
     *
     * @return a {@link Stream} of {@link JDKDetector}s
     */
    static Stream<JDKDetector> stream() {

        // establish a ServiceLoader to discover JDKDetectors
        final var serviceLoader = ServiceLoader.load(JDKDetector.class);

        // establish a collection of JDK.Detectors
        final var detectors = new ArrayList<JDKDetector>();
        for (final var detector : serviceLoader) {
            detectors.add(detector);
        }

        return detectors.stream();
    }

    /**
     * Obtains the current {@link JDK} based on the Virtual Machine in which this method is executed.
     *
     * @return the current {@link JDK}
     * @see JDK#current()
     */
    static JDK current() {
        return JDK.current();
    }

    /**
     * Attempts to create a {@link JDK} for the specified {@link Path} to a Java Home by reading its
     * {@code release} metadata file.
     * <p>
     * Should the specified {@link Path} refer to a Java Runtime Environment (JRE), an attempt will be made to
     * locate the {@link JDK} based on the specified {@link Path}.
     *
     * @param path the {@link Path}
     * @return the {@link Exceptional} {@link JDK}, otherwise {@link Exceptional#empty()}
     */
    static Exceptional<JDK> of(final Path path) {
        return of(path, PrintStreamTelemetryRecorder.of(
            URI.create("spawn://jdk-detector"), System.out, System.err));
    }

    /**
     * Attempts to create a {@link JDK} for the specified {@link Path} to a Java Home by reading its
     * {@code release} metadata file, recording detection diagnostics with the specified {@link TelemetryRecorder}.
     * <p>
     * Should the specified {@link Path} refer to a Java Runtime Environment (JRE), an attempt will be made to
     * locate the {@link JDK} based on the specified {@link Path}.
     *
     * @param path     the {@link Path}
     * @param recorder the {@link TelemetryRecorder} used to record detection diagnostics
     * @return the {@link Exceptional} {@link JDK}, otherwise {@link Exceptional#empty()}
     */
    static Exceptional<JDK> of(final Path path, final TelemetryRecorder recorder) {
        // use provided path as the basis of the JDK location
        Path home = path;

        // ensure the path exists
        if (!home.toFile().exists()) {
            return Exceptional.empty();
        }

        if (home.endsWith("jre")) {
            // when a JRE, we have to look for the parent (to find the JDK)
            home = home.getParent();
        }

        if (!home.resolve("bin/java").toFile().exists() && !home.resolve("bin/java.exe").toFile().exists()) {
            recorder.warn("The JDK Home [%s] does not contain bin/java or bin/java.exe", home);
            return Exceptional.empty();
        }

        // read the release file to determine the JDK version — no subprocess needed
        final Path releaseFile = home.resolve("release");
        if (!releaseFile.toFile().exists()) {
            recorder.warn("The JDK Home [%s] does not contain a release file", home);
            return Exceptional.empty();
        }

        // patterns to match JAVA_VERSION="...", OS_NAME="..." and OS_ARCH="..." in the release file
        final var VERSION = Pattern.compile("JAVA_VERSION=\"(.+?)\"");
        final var OS_NAME = Pattern.compile("OS_NAME=\"(.+?)\"");
        final var OS_ARCH = Pattern.compile("OS_ARCH=\"(.+?)\"");

        try {
            final var releaseContent = Files.readString(releaseFile);
            final var matcher = VERSION.matcher(releaseContent);

            if (matcher.find()) {
                final var javaVersion = JDKVersion.of(matcher.group(1));
                final var javaHome = JDKHome.of(home.toString());

                // OS_NAME / OS_ARCH describe the platform the JDK was built for, which may differ
                // from the host running this detection (e.g. a foreign JDK staged for cross-jlinking)
                final var osNameMatcher = OS_NAME.matcher(releaseContent);
                final var operatingSystem = osNameMatcher.find()
                    ? OperatingSystem.of(osNameMatcher.group(1))
                    : OperatingSystem.current();

                final var osArchMatcher = OS_ARCH.matcher(releaseContent);
                final var architecture = osArchMatcher.find()
                    ? Architecture.of(osArchMatcher.group(1))
                    : Architecture.current();

                return Exceptional.of(JDK.of(javaVersion, javaHome, operatingSystem, architecture));
            }
            else {
                recorder.warn("Could not detect Java version from release file at [%s]", releaseFile);
                return Exceptional.empty();
            }
        }
        catch (final IOException e) {
            recorder.warn(e, "Failed to read release file at [%s]", releaseFile);
            return Exceptional.ofException(e);
        }
    }

    /**
     * Attempts to detect the default {@link JDK} for the currently running virtual machine by reading its
     * {@code release} metadata file from {@code java.home}.
     *
     * @return the {@link Optional} default {@link JDK}, otherwise {@link Optional#empty()} if it can't be detected
     */
    static Optional<JDK> detectDefault() {
        return of(Path.of(System.getProperty("java.home"))).optional();
    }
}
