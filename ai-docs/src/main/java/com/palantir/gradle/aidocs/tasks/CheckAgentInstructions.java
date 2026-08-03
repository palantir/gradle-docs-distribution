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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Checks the installed read-dependencies skill and repairs it when Gradle runs with {@code --write-locks}. */
// Gradle file snapshots omit permission bits, while this task validates executable permission:
// https://github.com/gradle/gradle/blob/v9.3.1/platforms/core-execution/snapshots/src/main/java/org/gradle/internal/snapshot/impl/DirectorySnapshotter.java#L453-L457
@DisableCachingByDefault(because = "Caching could miss a non-executable locate script")
public abstract class CheckAgentInstructions extends DefaultTask {
    @Inject
    public CheckAgentInstructions(ProjectLayout projectLayout) {
        getSkillDirectory().convention(projectLayout.getProjectDirectory().dir(".claude/skills/read-dependencies"));
        getWriteLocks().convention(getProject().getGradle().getStartParameter().isWriteDependencyLocks());
    }

    @Internal
    public abstract DirectoryProperty getSkillDirectory();

    @Input
    public abstract Property<Boolean> getWriteLocks();

    @TaskAction
    public final void check() {
        Path skillDirectory = getSkillDirectory().get().getAsFile().toPath();
        try {
            if (getWriteLocks().get()) {
                writeAgentInstructions(skillDirectory);
            } else {
                checkAgentInstructions(skillDirectory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update or check read-dependencies agent instructions", e);
        }
    }

    @VisibleForTesting
    @SuppressWarnings("VisibleForTestingPackagePrivate")
    public static void writeAgentInstructions(Path skillDirectory) throws IOException {
        Files.createDirectories(skillDirectory);
        for (String fileName : AgentInstructionResources.FILE_NAMES) {
            Files.write(skillDirectory.resolve(fileName), AgentInstructionResources.read(fileName));
        }
        Path locate = skillDirectory.resolve("locate");
        if (!locate.toFile().setExecutable(true, true)) {
            throw new RuntimeException("Failed to make read-dependencies locate executable: " + locate);
        }
    }

    private static void checkAgentInstructions(Path skillDirectory) throws IOException {
        for (String fileName : AgentInstructionResources.FILE_NAMES) {
            if (!fileEquals(skillDirectory.resolve(fileName), AgentInstructionResources.read(fileName))) {
                throw invalidAgentInstructions();
            }
        }
        if (!Files.isExecutable(skillDirectory.resolve("locate"))) {
            throw invalidAgentInstructions();
        }
    }

    private static boolean fileEquals(Path file, byte[] expected) throws IOException {
        return Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), expected);
    }

    private static RuntimeException invalidAgentInstructions() {
        return new RuntimeException("The read-dependencies agent skill is missing, outdated, or not executable. Run"
                + " `gw check --write-locks` or `./gradlew check --write-locks`.");
    }
}
