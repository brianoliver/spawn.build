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
/**
 * Module descriptor for build.spawn.platform.local
 *
 * @author brian.oliver
 * @since Jan-2025
 */
open module build.spawn.platform.local {
    requires transitive java.management;

    requires transitive build.spawn.application;

    requires build.base.telemetry.foundation;

    exports build.spawn.platform.local;

    provides build.spawn.application.LauncherRegistration
        with build.spawn.platform.local.LocalLauncherRegistration;
}
