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

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.aidocs.tasks.CheckAgentInstructions;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class CheckAgentInstructionsTest {
    private static final String INVALID_AGENT_INSTRUCTIONS = "The read-dependencies agent skill is missing, outdated,"
            + " or not executable. Run `gw check --write-locks` or `./gradlew check --write-locks`.";

    @Nested
    class CheckFailures {
        @Test
        void check_fails_when_agent_instructions_are_missing(GradleInvoker gradle, RootProject root) {
            applyAiDocsPlugins(root);

            InvocationResult result = gradle.withArgs("check").buildsWithFailure();

            assertThat(result).output().contains(INVALID_AGENT_INSTRUCTIONS);
        }

        @Test
        void check_fails_when_agent_instructions_are_outdated(GradleInvoker gradle, RootProject root)
                throws IOException {
            applyAiDocsPlugins(root);
            CheckAgentInstructions.writeAgentInstructions(agentInstructionsDirectory(root));
            root.file(".claude/skills/read-dependencies/SKILL.md").overwrite("outdated");

            InvocationResult result = gradle.withArgs("check").buildsWithFailure();

            assertThat(result).output().contains(INVALID_AGENT_INSTRUCTIONS);
        }

        @Test
        void check_fails_when_locate_is_not_executable(GradleInvoker gradle, RootProject root) throws IOException {
            applyAiDocsPlugins(root);
            CheckAgentInstructions.writeAgentInstructions(agentInstructionsDirectory(root));
            makeLocateNonExecutable(root);

            InvocationResult result = gradle.withArgs("check").buildsWithFailure();

            assertThat(result).output().contains(INVALID_AGENT_INSTRUCTIONS);
        }
    }

    @Nested
    class AgentInstructionUpdates {
        @Test
        @DisabledConfigurationCache("Gradle does not reuse configuration cache when --write-locks is present")
        void check_with_write_locks_creates_updates_repairs_and_is_idempotent(GradleInvoker gradle, RootProject root)
                throws IOException {
            applyAiDocsPlugins(root);
            root.file("AGENTS.md").overwrite("""
                # Existing agent instructions

                Keep this file unchanged.
                """);

            InvocationResult initialWrite =
                    gradle.withArgs("check", "--write-locks").buildsSuccessfully();
            assertThat(initialWrite).task(":checkAgentInstructions").succeeded();
            String agentsMd = root.file("AGENTS.md").text();
            String skill =
                    root.file(".claude/skills/read-dependencies/SKILL.md").text();
            String locate = root.file(".claude/skills/read-dependencies/locate").text();

            root.file(".claude/skills/read-dependencies/SKILL.md").overwrite("outdated");
            root.file(".claude/skills/read-dependencies/locate").overwrite("outdated");
            makeLocateNonExecutable(root);
            gradle.withArgs("check", "--write-locks").buildsSuccessfully();

            assertThat(root.file("AGENTS.md").text()).isEqualTo(agentsMd);
            assertThat(root.file(".claude/skills/read-dependencies/SKILL.md").text())
                    .isEqualTo(skill);
            assertThat(root.file(".claude/skills/read-dependencies/locate").text())
                    .isEqualTo(locate);
            assertThat(agentsMd).isEqualTo("""
                # Existing agent instructions

                Keep this file unchanged.
                """);
            assertThat(skill)
                    .contains(
                            "description: Read before answering any question or doing any implementation that requires"
                                    + " understanding services, libraries, Gradle plugins (lint, compile, packaging,"
                                    + " publishing, distribute, bundling, Docker).",
                            "# Find relevant dependencies",
                            "build/ai-docs/dependency-index.yaml");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    agentInstructionsDirectory(root).resolve("locate"));
            assertThat(permissions).contains(PosixFilePermission.OWNER_EXECUTE);

            gradle.withArgs("check", "--write-locks").buildsSuccessfully();
            assertThat(root.file(".claude/skills/read-dependencies/SKILL.md").text())
                    .isEqualTo(skill);
            assertThat(root.file(".claude/skills/read-dependencies/locate").text())
                    .isEqualTo(locate);
            gradle.withArgs("check").buildsSuccessfully();
        }

        @Test
        @DisabledConfigurationCache("Gradle does not reuse configuration cache when --write-locks is present")
        void bare_write_locks_does_not_create_agent_instructions(GradleInvoker gradle, RootProject root) {
            applyAiDocsPlugins(root);

            gradle.withArgs("--write-locks").buildsSuccessfully();

            root.directory(".claude/skills/read-dependencies").assertThat().doesNotExist();
        }
    }

    private static void applyAiDocsPlugins(RootProject root) {
        root.buildGradle().plugins().add("com.palantir.ai-docs");
    }

    private static Path agentInstructionsDirectory(RootProject root) {
        return root.path().resolve(".claude/skills/read-dependencies");
    }

    private static void makeLocateNonExecutable(RootProject root) throws IOException {
        Files.setPosixFilePermissions(
                agentInstructionsDirectory(root).resolve("locate"),
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
}
