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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

public abstract class AiDocsProduceRootPlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new RuntimeException("com.palantir.ai-docs.produce.root can only be applied to the root project");
        }

        project.allprojects(subproject -> subproject.getPluginManager().apply(AiDocsProducePlugin.class));
        project.getPluginManager().apply(MavenPublishPlugin.class);

        TaskProvider<Jar> docsJar = project.getTasks().register("docsJar", Jar.class, task -> {
            task.setDescription("Builds the root project's documentation JAR.");
            task.getDestinationDirectory()
                    .set(project.getLayout().getBuildDirectory().dir("libs"));
            task.getArchiveBaseName().set(project.getName());
            task.getArchiveVersion()
                    .set(project.provider(() -> project.getVersion().toString()));
            task.getArchiveClassifier().set("docs");
            task.from(project.getLayout().getProjectDirectory().file("README.md"));
        });

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        project.afterEvaluate(_project -> configurePublication(project, publishing, docsJar));
    }

    private static void configurePublication(
            Project project, PublishingExtension publishing, TaskProvider<Jar> docsJar) {
        publishing.getPublications().register("aiDocs", MavenPublication.class, publication -> {
            publication.setGroupId(project.getGroup().toString());
            publication.setArtifactId(project.getName());
            publication.setVersion(project.getVersion().toString());
            project.getComponents().stream()
                    .filter(component -> component.getName().equals("java"))
                    .findFirst()
                    .ifPresent(publication::from);
            publication.artifact(docsJar);
        });
    }
}
