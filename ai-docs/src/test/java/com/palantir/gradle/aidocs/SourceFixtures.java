/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.aidocs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * These tests include two fixtures: a {@code gradle-plugin} and a {@code library}
 * {@code source-repository} contains source code.
 * {@code maven-repository} contain their corresponding published artifacts.
 */
final class SourceFixtures {
    private SourceFixtures() {}

    static void install(String fixture, Path destination) throws IOException {
        Path sourceDirectory = sourceRepository().resolve(fixture);
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path source : paths.toList()) {
                Path target =
                        destination.resolve(sourceDirectory.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static Path sourceRepository() {
        URL resource = Optional.ofNullable(SourceFixtures.class.getResource("/source-repository"))
                .orElseThrow(() -> new IllegalStateException("Missing source repository fixture"));
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid source repository fixture path", e);
        }
    }
}
