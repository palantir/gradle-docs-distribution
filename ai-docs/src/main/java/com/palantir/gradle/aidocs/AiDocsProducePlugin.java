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

import com.palantir.gradle.aidocs.types.ModuleCoordinates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConsumableConfiguration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.tasks.Jar;

public abstract class AiDocsProducePlugin implements Plugin<Project> {
    public static final String DOCS_COORDINATES_ATTRIBUTE = "Docs-Coordinates";
    static final String OUTGOING_PROJECT_DEPENDENCIES = "outgoingProjectDependencies";
    static final String OUTGOING_GRADLE_PLUGINS = "outgoingGradlePlugins";

    @Override
    public final void apply(Project project) {
        NamedDomainObjectProvider<ConsumableConfiguration> outgoingProjectDependencies = project.getConfigurations()
                .consumable(
                        OUTGOING_PROJECT_DEPENDENCIES,
                        configuration ->
                                configureAttributes(project, configuration, AiDocsUsages.PROJECT_DEPENDENCIES));
        NamedDomainObjectProvider<ConsumableConfiguration> outgoingGradlePlugins = project.getConfigurations()
                .consumable(
                        OUTGOING_GRADLE_PLUGINS,
                        configuration -> configureAttributes(project, configuration, AiDocsUsages.GRADLE_PLUGINS));

        configureJarManifest(project);
        configureOutgoingProjectDependencies(project, outgoingProjectDependencies);
        configureOutgoingGradlePlugins(project, outgoingGradlePlugins);
    }

    private static void configureJarManifest(Project project) {
        Project rootProject = project.getRootProject();
        Provider<String> docsCoordinates = project.provider(() ->
                "%s:%s:%s:docs".formatted(rootProject.getGroup(), rootProject.getName(), rootProject.getVersion()));

        project.getTasks()
                .withType(Jar.class)
                .configureEach(
                        jar -> jar.getManifest().attributes(Map.of(DOCS_COORDINATES_ATTRIBUTE, docsCoordinates)));
    }

    private static void configureOutgoingProjectDependencies(
            Project project, NamedDomainObjectProvider<ConsumableConfiguration> outgoingProjectDependencies) {
        project.getPluginManager()
                .withPlugin(
                        "java",
                        _plugin -> outgoingProjectDependencies.configure(configuration -> configuration.extendsFrom(
                                project.getConfigurations().getByName("runtimeClasspath"),
                                project.getConfigurations().getByName("compileClasspath"),
                                project.getConfigurations().getByName("testRuntimeClasspath"))));
    }

    private static void configureOutgoingGradlePlugins(
            Project project, NamedDomainObjectProvider<ConsumableConfiguration> outgoingGradlePlugins) {
        NamedDomainObjectProvider<Configuration> classpath =
                project.getBuildscript().getConfigurations().named("classpath");
        Provider<Set<ComponentIdentifier>> directComponents = classpath.flatMap(configuration -> configuration
                .getIncoming()
                .getResolutionResult()
                .getRootComponent()
                .map(root -> root.getDependencies().stream()
                        .filter(ResolvedDependencyResult.class::isInstance)
                        .map(ResolvedDependencyResult.class::cast)
                        .map(dependency -> dependency.getSelected().getId())
                        .collect(Collectors.toSet())));
        Provider<List<Dependency>> directPluginDependencies = classpath
                .flatMap(configuration ->
                        configuration.getIncoming().getArtifacts().getResolvedArtifacts())
                .zip(directComponents, (artifacts, direct) -> directPluginDependencies(project, artifacts, direct));
        outgoingGradlePlugins.configure(
                configuration -> configuration.getDependencies().addAllLater(directPluginDependencies));
    }

    private static List<Dependency> directPluginDependencies(
            Project project, Set<ResolvedArtifactResult> artifacts, Set<ComponentIdentifier> directComponents) {
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        artifacts.stream()
                .filter(artifact -> directComponents.contains(artifact.getId().getComponentIdentifier()))
                .filter(artifact -> ModuleCoordinates.from(artifact).isPresent())
                .filter(artifact -> ArtifactMetadataUtils.isGradlePlugin(artifact.getFile()))
                .forEach(artifact -> ModuleCoordinates.from(artifact).ifPresent(coordinates -> {
                    String notation = coordinates.dependencyNotation();
                    Dependency dependency = project.getDependencies().create(notation);
                    if (dependency instanceof ModuleDependency moduleDependency) {
                        moduleDependency.setTransitive(false);
                    }
                    dependencies.put(notation, dependency);
                }));
        return List.copyOf(dependencies.values());
    }

    private static void configureAttributes(Project project, Configuration configuration, String usage) {
        configuration
                .getAttributes()
                .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, usage))
                .attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY))
                .attribute(Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.EXTERNAL))
                .attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
    }
}
