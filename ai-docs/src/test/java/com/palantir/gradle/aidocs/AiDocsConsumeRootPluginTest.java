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
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class AiDocsConsumeRootPluginTest {
    private static final String INVALID_AGENT_INSTRUCTIONS = "The read-dependencies agent skill is missing, outdated,"
            + " or not executable. Run `gw check --write-locks` or `./gradlew check --write-locks`.";

    @Nested
    class ArtifactResolution {
        @Test
        void constructs_dependency_index_from_project_dependency_and_gradle_plugin(
                GradleInvoker gradle, RootProject root) throws IOException, InterruptedException {
            Path repository = mavenRepository();
            configureConsumer(root, repository);

            InvocationResult result = gradle.withArgs("constructIndex").buildsSuccessfully();

            assertThat(result).task(":pullSource").succeeded();

            // All the source files are the same
            Path source = root.buildDir().path().resolve("ai-docs/source/com/example");
            Path sourceRepository = SourceFixtures.sourceRepository();
            assertThat(source.resolve("library/1.0.0/com/example/Library.java"))
                    .hasSameTextualContentAs(
                            sourceRepository.resolve("library/library/src/main/java/com/example/Library.java"));
            assertThat(source.resolve("gradle-plugin/1.0.0/com/example/ExamplePlugin.java"))
                    .hasSameTextualContentAs(
                            sourceRepository.resolve("gradle-plugin/src/main/java/com/example/ExamplePlugin.java"));

            // All the docs files are the same
            Path docs = root.buildDir().path().resolve("ai-docs/docs/com/example");
            assertThat(docs.resolve("library-root/1.0.0/README.md"))
                    .as("project dependency docs")
                    .hasSameTextualContentAs(sourceRepository.resolve("library/README.md"));
            assertThat(docs.resolve("gradle-plugin/1.0.0/README.md"))
                    .as("Gradle plugin docs")
                    .hasSameTextualContentAs(sourceRepository.resolve("gradle-plugin/README.md"));
            assertThat(docs.resolve("gradle-plugin/1.0.0/docs"))
                    .as("documentation excluded from README-only JAR")
                    .doesNotExist();

            // language=yaml
            root.buildDir().file("ai-docs/dependency-index.yaml").assertThat().hasContent("""
                gradlePlugins:
                  com.example.fixture: The Gradle plugin gives agents plugin guidance.
                projectDependencies:
                  com.example:library: The library gives agents project dependency guidance.
                """);
            // language=yaml
            root.buildDir()
                    .file("ai-docs/dependency-locations.yaml")
                    .assertThat()
                    .hasContent("""
                        gradlePlugins:
                          com.example.fixture:
                            group: com.example
                            version: 1.0.0
                            code: gradle-plugin
                            docs: gradle-plugin
                        projectDependencies:
                          com.example:library:
                            group: com.example
                            version: 1.0.0
                            code: library
                            docs: library-root
                        """);

            // locate script can find the files
            CheckAgentInstructions.writeAgentInstructions(agentInstructionsDirectory(root));
            Path locate = root.path().resolve(".claude/skills/read-dependencies/locate");
            Process process = new ProcessBuilder(locate.toString(), "--type=project-dependency", "com.example:library")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as("locate exit code").isZero();
            assertThat(output.lines())
                    .containsExactly(
                            source.resolve("library/1.0.0").toString(),
                            docs.resolve("library-root/1.0.0").toString());
        }

        @Test
        void gradle_plugin_dependencies_are_direct_only(GradleInvoker gradle, RootProject root) {
            Path repository = mavenRepository();
            configureRootWithBuildscriptPlugin(root, repository);

            gradle.withArgs("constructIndex").buildsSuccessfully();

            // language=yaml
            root.buildDir().file("ai-docs/dependency-index.yaml").assertThat().hasContent("""
                gradlePlugins:
                  com.example.fixture: The Gradle plugin gives agents plugin guidance.
                projectDependencies: {}
                """);
            assertThat(root.buildDir().path().resolve("ai-docs/source/com/example/library"))
                    .as("transitive buildscript dependency")
                    .doesNotExist();
        }
    }

    @Nested
    class CheckFailures {
        @Test
        void check_fails_when_agent_instructions_are_missing(GradleInvoker gradle, RootProject root) {
            root.buildGradle().plugins().add("com.palantir.ai-docs");

            InvocationResult result = gradle.withArgs("check").buildsWithFailure();

            assertThat(result).output().contains(INVALID_AGENT_INSTRUCTIONS);
        }

        @Test
        void check_fails_when_agent_instructions_are_outdated(GradleInvoker gradle, RootProject root)
                throws IOException {
            root.buildGradle().plugins().add("com.palantir.ai-docs");
            CheckAgentInstructions.writeAgentInstructions(agentInstructionsDirectory(root));
            root.file(".claude/skills/read-dependencies/SKILL.md").overwrite("outdated");

            InvocationResult result = gradle.withArgs("check").buildsWithFailure();

            assertThat(result).output().contains(INVALID_AGENT_INSTRUCTIONS);
        }

        @Test
        void check_fails_when_locate_is_not_executable(GradleInvoker gradle, RootProject root) throws IOException {
            root.buildGradle().plugins().add("com.palantir.ai-docs");
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
            root.buildGradle().plugins().add("com.palantir.ai-docs");
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
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(root.path().resolve(".claude/skills/read-dependencies/locate"));
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
            root.buildGradle().plugins().add("com.palantir.ai-docs");

            gradle.withArgs("--write-locks").buildsSuccessfully();

            root.directory(".claude/skills/read-dependencies").assertThat().doesNotExist();
        }
    }

    @Nested
    class PluginApplication {
        @Test
        void root_plugin_cannot_be_applied_to_a_subproject(GradleInvoker gradle, SubProject library) {
            library.buildGradle().plugins().add("com.palantir.ai-docs.consume.root");

            InvocationResult result = gradle.withArgs(":library:tasks").buildsWithFailure();

            assertThat(result)
                    .output()
                    .contains("com.palantir.ai-docs.consume.root can only be applied to the root project");
        }
    }

    private static void configureConsumer(RootProject root, Path repository) {
        configureRootWithBuildscriptPlugin(root, repository);
        root.buildGradle().append("""
            dependencies {
                implementation 'com.example:library:1.0.0'
            }
            """);
    }

    private static void configureRootWithBuildscriptPlugin(RootProject root, Path repository) {
        root.buildGradle().append(buildscript(repository));
        root.buildGradle().plugins().add("java-library").add("com.palantir.ai-docs");
        root.buildGradle().append("""
            repositories {
                maven { url = uri('%s') }
            }
            """, repository);
    }

    private static String buildscript(Path repository) {
        return """
            buildscript {
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    classpath 'com.example:gradle-plugin:1.0.0'
                }
            }
            """.formatted(repository);
    }

    private static Path agentInstructionsDirectory(RootProject root) {
        return root.path().resolve(".claude/skills/read-dependencies");
    }

    private static void makeLocateNonExecutable(RootProject root) throws IOException {
        Files.setPosixFilePermissions(
                agentInstructionsDirectory(root).resolve("locate"),
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static Path mavenRepository() {
        URL resource = Optional.ofNullable(AiDocsConsumeRootPluginTest.class.getResource("/maven-repository"))
                .orElseThrow(() -> new IllegalStateException("Missing Maven repository fixture"));
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid Maven repository fixture path", e);
        }
    }
}
