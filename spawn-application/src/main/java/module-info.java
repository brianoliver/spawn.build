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
/**
 * Module descriptor for build.spawn.application.
 *
 * @author brian.oliver
 * @since Dec-2024
 */
open module build.spawn.application {
    requires transitive build.spawn.option;

    requires transitive build.base.configuration;
    requires transitive build.base.commandline;
    requires transitive build.base.expression;
    requires transitive build.base.flow;
    requires transitive build.base.io;
    requires transitive build.base.naming;
    requires transitive build.base.network;
    requires transitive build.base.option;
    requires transitive build.base.telemetry;
    requires build.base.telemetry.foundation;

    requires transitive build.codemodel.dependency.injection;
    requires transitive build.codemodel.jdk;
    requires transitive build.codemodel.foundation;

    requires transitive jakarta.inject;

    exports build.spawn.application;
    exports build.spawn.application.console;
    exports build.spawn.application.facet;
    exports build.spawn.application.option;

    uses build.spawn.application.LauncherRegistration;
}
