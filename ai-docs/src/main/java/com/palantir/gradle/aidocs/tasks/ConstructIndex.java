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

package com.palantir.gradle.aidocs.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.palantir.gradle.aidocs.types.Location;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Builds concise YAML indexes that map dependencies to their pulled source and documentation. */
public abstract class ConstructIndex extends DefaultTask {
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    @Inject
    public ConstructIndex(ProjectLayout projectLayout) {
        getDependencyIndex()
                .convention(projectLayout.getProjectDirectory().file("build/ai-docs/dependency-index.yaml"));
        getDependencyLocations()
                .convention(projectLayout.getProjectDirectory().file("build/ai-docs/dependency-locations.yaml"));
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDirectory();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getDocsDirectory();

    @Input
    public abstract MapProperty<String, Location> getGradlePluginIdToLocations();

    @Input
    public abstract MapProperty<String, Location> getProjectDependencyIdToLocations();

    @OutputFile
    public abstract RegularFileProperty getDependencyIndex();

    @OutputFile
    public abstract RegularFileProperty getDependencyLocations();

    @TaskAction
    public final void construct() {
        Path docsDirectory = getDocsDirectory().get().getAsFile().toPath();
        Map<String, Location> gradlePlugins = getGradlePluginIdToLocations().get();
        Map<String, Location> projectDeps = getProjectDependencyIdToLocations().get();

        String dependencyIndex = dependencyIndex(docsDirectory, gradlePlugins, projectDeps);
        String dependencyLocations = dependencyLocations(gradlePlugins, projectDeps);
        write(getDependencyIndex().get().getAsFile().toPath(), dependencyIndex, "dependency index");
        write(getDependencyLocations().get().getAsFile().toPath(), dependencyLocations, "dependency locations");
    }

    static String constructIndexSnippet(String readme) {
        boolean foundHeading = false;
        List<String> paragraph = new ArrayList<>();
        for (String line : readme.lines().toList()) {
            if (!foundHeading) {
                foundHeading = line.startsWith("# ");
                continue;
            }

            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                if (!paragraph.isEmpty()) {
                    break;
                }
                continue;
            }
            if (trimmedLine.startsWith("#")) {
                break;
            }
            paragraph.add(trimmedLine);
        }
        return foundHeading ? String.join(" ", paragraph) : "";
    }

    private static String dependencyIndex(
            Path docsDirectory, Map<String, Location> gradlePlugins, Map<String, Location> projectDeps) {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("gradlePlugins", indexEntries(docsDirectory, gradlePlugins));
        index.put("projectDependencies", indexEntries(docsDirectory, projectDeps));
        return yaml(index, "dependency index");
    }

    private static Map<String, String> indexEntries(Path docsDirectory, Map<String, Location> locations) {
        Map<String, String> entries = new TreeMap<>();
        locations.forEach((key, location) -> entries.put(
                key,
                location.docsPath()
                        .flatMap(path -> readReadme(docsDirectory.resolve(path)))
                        .map(ConstructIndex::constructIndexSnippet)
                        .filter(snippet -> !snippet.isEmpty())
                        .orElse(null)));
        return entries;
    }

    private static String dependencyLocations(Map<String, Location> gradlePlugins, Map<String, Location> projectDeps) {
        Map<String, Object> locations = new LinkedHashMap<>();
        locations.put("gradlePlugins", locationEntries(gradlePlugins));
        locations.put("projectDependencies", locationEntries(projectDeps));
        return yaml(locations, "dependency locations");
    }

    private static Map<String, Map<String, String>> locationEntries(Map<String, Location> locations) {
        Map<String, Map<String, String>> entries = new TreeMap<>();
        locations.forEach((key, location) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("group", location.group());
            fields.put("version", location.version());
            fields.put("code", location.codeName());
            location.docsName().ifPresent(docs -> fields.put("docs", docs));
            entries.put(key, fields);
        });
        return entries;
    }

    private static Optional<String> readReadme(Path docsDirectory) {
        Path readme = docsDirectory.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(readme, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read documentation README: " + readme, e);
        }
    }

    private static String yaml(Object value, String description) {
        try {
            return YAML_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + description, e);
        }
    }

    private static void write(Path output, String content, String description) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + description + ": " + output, e);
        }
    }
}
