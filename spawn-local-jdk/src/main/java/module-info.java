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
/**
 * Module descriptor for build.spawn.platform.local.jdk.
 *
 * @author brian.oliver
 * @since Jan-2025
 */
open module build.spawn.platform.local.jdk {
    requires transitive build.base.foundation;
    requires build.base.archiving;
    requires build.base.commandline;
    requires build.base.flow;
    requires build.base.io;
    requires build.base.naming;
    requires build.base.telemetry;
    requires build.base.telemetry.foundation;

    requires transitive build.spawn.application;
    requires transitive build.spawn.jdk;
    requires transitive build.spawn.platform.local;

    exports build.spawn.platform.local.jdk;

    uses build.spawn.platform.local.jdk.JDKDetector;

    provides build.spawn.platform.local.jdk.JDKDetector
        with build.spawn.platform.local.jdk.JDKHomeBasedPatternDetector;

    provides build.spawn.application.LauncherRegistration
        with build.spawn.platform.local.jdk.LocalJDKLauncherRegistration;
}
