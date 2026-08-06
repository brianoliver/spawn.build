package build.spawn.docker.jdk.command;

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
import build.base.json.JsonValue;
import build.spawn.docker.Image;
import build.spawn.docker.jdk.HttpTransport;
import build.spawn.docker.option.ImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code Docker Engine} {@link Command} to create an {@code Image} using the
 * <a href="https://docs.docker.com/engine/api/v1.41/#operation/ImageBuild">Build Image</a> command.
 *
 * @author brian.oliver
 * @since Jul-2021
 */
public class BuildImage
    extends AbstractBlockingCommand<Optional<String>> {

    /**
     * The {@link Path} to the {@code Docker Context} for building the {@link Image}.
     */
    private final Path contextPath;

    /**
     * The {@link Configuration} for building the {@link Image}.
     */
    private final Configuration configuration;

    /**
     * Constructs a {@link BuildImage} {@link Command}.
     *
     * @param contextPath   the Docker Context {@link Path} from which to build the {@link Image}
     * @param configuration {@link Configuration} to build the {@link Image}
     */
    public BuildImage(final Path contextPath,
                      final Configuration configuration) {

        this.contextPath = Objects.requireNonNull(contextPath, "The Context Path must not be null");
        this.configuration = configuration == null
            ? Configuration.empty()
            : configuration;
    }

    @Override
    protected HttpTransport.Request createRequest() {
        final var tagParams = new StringBuilder("?q=false");
        this.configuration.stream(ImageName.class)
            .forEach(name -> tagParams.append("&t=").append(name.get()));
        try {
            return HttpTransport.Request
                .post("/build" + tagParams, Files.readAllBytes(this.contextPath))
                .withContentType("application/x-tar");
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read build context", e);
        }
    }

    @Override
    protected Optional<String> createResult(final HttpTransport.Response response)
        throws IOException {

        // establish the CompletingObserver observe when image ID has been generated
        // (we want to capture {"aux":{"ID":"sha256:f65c628a75fe8b3e982165d1a4ceaf521fadab8da2136702c59e184d7be3e243"})
        final var completingSubscriber = new CompletingSubscriber<JsonValue>();
        final var onImageBuilt = completingSubscriber.when(
            json -> json.asObject().has("aux"),
            json -> json.get("aux").getString("ID"));

        final CompletableFuture<?> onSuccess = completingSubscriber.when(
            json -> json.asObject().has("stream"),
            json -> json.getString("stream").contains("Successful"));

        // process the entire InputStream from the Response to essentially wait for the image to be created
        final var processor = new JsonNodeInputStreamProcessor(recorder());
        processor.process(response.bodyStream(), completingSubscriber);

        // we've completed building when the ImageId is available and "Successful" has been observed
        onSuccess.join();

        return Optional.of(onImageBuilt.join());
    }
}
