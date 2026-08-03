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

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class AiDocsProducePluginTest {
    private static final String DOCS_COORDINATES = "com.example:gradle-plugin:1.2.3:docs";

    @Nested
    class GradlePluginProject {
        @BeforeEach
        void before_each(RootProject root, SubProject library) throws IOException {
            SourceFixtures.install("gradle-plugin", root.path());
            SourceFixtures.install("library/library", library.path());
        }

        @Test
        void applies_project_plugin_to_every_project_and_advertises_root_docs(
                GradleInvoker gradle, RootProject root, SubProject library) throws IOException {
            gradle.withArgs("jar", ":library:jar", "docsJar").buildsSuccessfully();

            assertThat(manifestAttribute(root.buildDir().path().resolve("libs/gradle-plugin-1.2.3.jar")))
                    .isEqualTo(DOCS_COORDINATES);
            assertThat(manifestAttribute(library.buildDir().path().resolve("libs/library-1.2.3.jar")))
                    .isEqualTo(DOCS_COORDINATES);
            assertThat(manifestAttribute(root.buildDir().path().resolve("libs/gradle-plugin-1.2.3-docs.jar")))
                    .isEqualTo(DOCS_COORDINATES);
        }

        @Test
        void docs_jar_contains_only_root_readme(GradleInvoker gradle, RootProject root) throws IOException {
            gradle.withArgs("docsJar").buildsSuccessfully();

            Path docsJar = root.buildDir().path().resolve("libs/gradle-plugin-1.2.3-docs.jar");
            assertThat(jarEntries(docsJar)).containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "README.md");
        }

        @Test
        void maven_publication_builds_and_publishes_all_artifacts(GradleInvoker gradle, RootProject root)
                throws IOException {
            InvocationResult publish =
                    gradle.withArgs("publishAiDocsPublicationToTestRepository").buildsSuccessfully();
            assertThat(publish).task(":docsJar").succeeded();

            Path publication = root.buildDir().path().resolve("test-repository/com/example/gradle-plugin/1.2.3");
            Path publishedDocs = publication.resolve("gradle-plugin-1.2.3-docs.jar");
            assertThat(publication.resolve("gradle-plugin-1.2.3.jar")).exists();
            assertThat(publishedDocs).exists();
            assertThat(publication.resolve("gradle-plugin-1.2.3.pom")).exists();
            assertThat(publication.resolve("gradle-plugin-1.2.3.module")).exists();
            assertThat(jarEntries(publishedDocs)).containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "README.md");
        }
    }

    @Nested
    class LibraryProject {
        @BeforeEach
        void before_each(RootProject root) throws IOException {
            SourceFixtures.install("library", root.path());
        }

        @Test
        void publishes_docs_only_under_the_root_project(GradleInvoker gradle, RootProject root) {
            Path mavenLocal = root.path().resolve("maven-local");

            gradle.withArgs("publishToMavenLocal", "-Dmaven.repo.local=" + mavenLocal)
                    .buildsSuccessfully();

            assertThat(mavenLocal.resolve("com/example/library-root/1.2.3/library-root-1.2.3-docs.jar"))
                    .as("root docs artifact")
                    .exists();
            assertThat(mavenLocal.resolve("com/example/library/1.2.3/library-1.2.3-docs.jar"))
                    .as("main subproject docs artifact")
                    .doesNotExist();
        }

        @Test
        void project_without_a_java_component_publishes_docs_only(GradleInvoker gradle, RootProject root)
                throws IOException {
            InvocationResult publish =
                    gradle.withArgs("publishAiDocsPublicationToTestRepository").buildsSuccessfully();
            assertThat(publish).task(":docsJar").succeeded();

            Path publication = root.buildDir().path().resolve("test-repository/com/example/library-root/1.2.3");
            Path publishedDocs = publication.resolve("library-root-1.2.3-docs.jar");
            assertThat(publication.resolve("library-root-1.2.3.jar")).doesNotExist();
            assertThat(publishedDocs).exists();
            assertThat(publication.resolve("library-root-1.2.3.pom")).exists();
            assertThat(jarEntries(publishedDocs)).containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "README.md");
        }
    }

    @Nested
    class PluginApplication {
        @Test
        void root_plugin_cannot_be_applied_to_a_subproject(GradleInvoker gradle, RootProject root, SubProject library)
                throws IOException {
            SourceFixtures.install("gradle-plugin", root.path());
            SourceFixtures.install("library/library", library.path());
            library.buildGradle().plugins().add("com.palantir.ai-docs.produce.root");

            InvocationResult result = gradle.withArgs(":library:tasks").buildsWithFailure();

            assertThat(result)
                    .output()
                    .contains("com.palantir.ai-docs.produce.root can only be applied to the root project");
        }
    }

    private static String manifestAttribute(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Docs-Coordinates");
        }
    }

    private static Set<String> jarEntries(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(entry -> entry.getName())
                    .collect(Collectors.toSet());
        }
    }
}
