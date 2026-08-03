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
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class AiDocsConsumeRootPluginTest {
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

            CheckAgentInstructions.writeAgentInstructions(agentInstructionsDirectory(root));
            Path locate = agentInstructionsDirectory(root).resolve("locate");
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
        root.buildGradle()
                .plugins()
                .add("java-library")
                .add("com.palantir.ai-docs.produce.root")
                .add("com.palantir.ai-docs.consume.root");
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
