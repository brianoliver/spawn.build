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
/**
 * Module descriptor for build.spawn.docker.
 *
 * @author brian.oliver
 * @since Nov-2024
 */
open module build.spawn.docker.jdk {
    requires transitive build.spawn.option;
    requires transitive build.spawn.docker;

    requires build.codemodel.foundation;
    requires build.codemodel.jdk;
    requires build.codemodel.dependency.injection;

    requires transitive build.base.naming;

    requires java.net.http;
    requires jdk.httpserver;
    requires jakarta.inject;
    requires build.base.io;
    requires build.base.json;
    requires build.base.archiving;
    requires build.base.option;
    requires build.base.flow;
    requires build.base.telemetry;
    requires build.base.telemetry.foundation;

    exports build.spawn.docker.jdk;

    provides build.spawn.docker.Session.Factory with
        build.spawn.docker.jdk.UnixDomainSocketBasedSession.Factory,
        build.spawn.docker.jdk.LocalHostBasedSessionFactory,
        build.spawn.docker.jdk.DockerHostVariableBasedSessionFactory,
        build.spawn.docker.jdk.InternalDockerHostBasedSessionFactory;
}
