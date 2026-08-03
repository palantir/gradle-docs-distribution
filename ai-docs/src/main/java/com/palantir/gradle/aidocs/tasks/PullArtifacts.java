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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Extracts resolved artifacts into deterministic dependency-specific directories. */
public abstract class PullArtifacts extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getArtifacts();

    @Input
    public abstract MapProperty<String, String> getDestinations();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract ArchiveOperations getArchiveOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public final void pull() {
        Map<String, File> artifacts = new TreeMap<>();
        getArtifacts().getFiles().forEach(artifact -> artifacts.put(artifact.getAbsolutePath(), artifact));
        Map<String, String> destinations = getDestinations().get();
        if (!artifacts.keySet().equals(destinations.keySet())) {
            throw new RuntimeException("Resolved artifacts do not match destination keys: %s artifacts, %s destinations"
                    .formatted(artifacts.keySet(), destinations.keySet()));
        }

        Path output = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create artifact output directory: " + output, e);
        }

        for (Map.Entry<String, String> artifactDestination : new TreeMap<>(destinations).entrySet()) {
            File artifact = Optional.ofNullable(artifacts.get(artifactDestination.getKey()))
                    .orElseThrow(() ->
                            new IllegalStateException("Missing resolved artifact: " + artifactDestination.getKey()));
            Path destination = output.resolve(artifactDestination.getValue());
            getFileSystemOperations().copy(spec -> {
                spec.from(getArchiveOperations().zipTree(artifact));
                spec.into(destination);
            });
        }
    }
}
