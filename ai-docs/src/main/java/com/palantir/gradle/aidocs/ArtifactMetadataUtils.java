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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

final class ArtifactMetadataUtils {
    private static final String GRADLE_PLUGIN_DESCRIPTOR_PREFIX = "META-INF/gradle-plugins/";
    private static final String GRADLE_PLUGIN_DESCRIPTOR_SUFFIX = ".properties";
    private static final Pattern DOCS_COORDINATES = Pattern.compile("[^:]+:[^:]+:[^:]+:docs");

    private ArtifactMetadataUtils() {}

    static Optional<String> docsCoordinates(File artifact) {
        try (JarFile jar = new JarFile(artifact)) {
            if (jar.getManifest() == null) {
                return Optional.empty();
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            return Optional.ofNullable(attributes.getValue(AiDocsProducePlugin.DOCS_COORDINATES_ATTRIBUTE))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(ArtifactMetadataUtils::validateDocsCoordinates);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact manifest: " + artifact, e);
        }
    }

    static Set<String> gradlePluginIds(File artifact) {
        try (JarFile jar = new JarFile(artifact)) {
            Set<String> pluginIds = new TreeSet<>();
            jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith(GRADLE_PLUGIN_DESCRIPTOR_PREFIX))
                    .filter(name -> name.endsWith(GRADLE_PLUGIN_DESCRIPTOR_SUFFIX))
                    .map(name -> name.substring(
                            GRADLE_PLUGIN_DESCRIPTOR_PREFIX.length(),
                            name.length() - GRADLE_PLUGIN_DESCRIPTOR_SUFFIX.length()))
                    .filter(name -> !name.isEmpty())
                    .forEach(pluginIds::add);
            return pluginIds;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect Gradle plugin artifact: " + artifact, e);
        }
    }

    static boolean isGradlePlugin(File artifact) {
        return !gradlePluginIds(artifact).isEmpty();
    }

    private static String validateDocsCoordinates(String coordinates) {
        if (!DOCS_COORDINATES.matcher(coordinates).matches()) {
            throw new RuntimeException("Invalid Docs-Coordinates manifest attribute: " + coordinates);
        }
        return coordinates;
    }
}
