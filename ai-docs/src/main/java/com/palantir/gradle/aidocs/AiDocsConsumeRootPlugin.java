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

import com.palantir.gradle.aidocs.tasks.CheckAgentInstructions;
import com.palantir.gradle.aidocs.tasks.ConstructIndex;
import com.palantir.gradle.aidocs.tasks.PullArtifacts;
import com.palantir.gradle.aidocs.types.Location;
import com.palantir.gradle.aidocs.types.ModuleCoordinates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/** Resolves project and Gradle plugin dependencies into source and documentation trees for AI agents. */
public abstract class AiDocsConsumeRootPlugin implements Plugin<Project> {
    public static final String PROJECT_DEPENDENCIES = AiDocsUsages.PROJECT_DEPENDENCIES;
    public static final String GRADLE_PLUGINS = AiDocsUsages.GRADLE_PLUGINS;

    @Override
    public final void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new RuntimeException("com.palantir.ai-docs.consume.root can only be applied to the root project");
        }

        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getDependencies()
                .getAttributesSchema()
                .attribute(
                        Usage.USAGE_ATTRIBUTE,
                        strategy -> strategy.getCompatibilityRules().add(AiDocsUsageCompatibilityRule.class));

        DependencyScopeConfiguration subprojects =
                project.getConfigurations().dependencyScope("subprojects").get();
        project.getSubprojects()
                .forEach(subproject -> project.getDependencies().add(subprojects.getName(), subproject));

        // These configurations resolve the main artifacts that supply source variants and docs coordinates.
        ResolvableConfiguration incomingProjectDependencies = project.getConfigurations()
                .resolvable("incomingProjectDependencies", incoming -> {
                    Configuration selfProjectDeps =
                            project.getConfigurations().getByName(AiDocsProducePlugin.OUTGOING_PROJECT_DEPENDENCIES);
                    incoming.extendsFrom(subprojects, selfProjectDeps);
                    setUsageAttribute(project, incoming, PROJECT_DEPENDENCIES);
                })
                .get();
        ResolvableConfiguration incomingGradlePlugins = project.getConfigurations()
                .resolvable("incomingGradlePlugins", incoming -> {
                    Configuration selfGradlePlugins =
                            project.getConfigurations().getByName(AiDocsProducePlugin.OUTGOING_GRADLE_PLUGINS);
                    incoming.extendsFrom(subprojects, selfGradlePlugins);
                    setUsageAttribute(project, incoming, GRADLE_PLUGINS);
                })
                .get();

        // These scopes receive the docs dependencies discovered from main artifact manifests.
        DependencyScopeConfiguration projectDocsDependencies = project.getConfigurations()
                .dependencyScope("projectDependencyDocs")
                .get();
        DependencyScopeConfiguration gradlePluginDocsDependencies =
                project.getConfigurations().dependencyScope("gradlePluginDocs").get();

        // These configurations resolve the discovered docs artifacts.
        ResolvableConfiguration incomingProjectDependencyDocs = project.getConfigurations()
                .resolvable(
                        "incomingProjectDependencyDocs",
                        configuration -> configuration.extendsFrom(projectDocsDependencies))
                .get();
        ResolvableConfiguration incomingGradlePluginDocs = project.getConfigurations()
                .resolvable(
                        "incomingGradlePluginDocs",
                        configuration -> configuration.extendsFrom(gradlePluginDocsDependencies))
                .get();

        Provider<Set<ResolvedArtifactResult>> projectMainArtifacts = moduleArtifacts(incomingProjectDependencies);
        Provider<Set<ResolvedArtifactResult>> gradlePluginMainArtifacts = moduleArtifacts(incomingGradlePlugins);
        projectDocsDependencies.getDependencies().addAllLater(docsDependencies(project, projectMainArtifacts));
        gradlePluginDocsDependencies
                .getDependencies()
                .addAllLater(docsDependencies(project, gradlePluginMainArtifacts));

        ArtifactCollection projectSources = sources(project, incomingProjectDependencies);
        ArtifactCollection projectDocs =
                incomingProjectDependencyDocs.getIncoming().getArtifacts();
        ArtifactCollection gradlePluginSources = sources(project, incomingGradlePlugins);
        ArtifactCollection gradlePluginDocs =
                incomingGradlePluginDocs.getIncoming().getArtifacts();

        TaskProvider<PullArtifacts> pullSource = registerPullSource(project, projectSources, gradlePluginSources);
        TaskProvider<PullArtifacts> pullDocs = registerPullDocs(project, projectDocs, gradlePluginDocs);
        registerConstructIndex(
                project,
                projectMainArtifacts,
                projectDocs,
                gradlePluginMainArtifacts,
                gradlePluginDocs,
                pullSource,
                pullDocs);
        registerCheckAgentInstructions(project);
    }

    private static TaskProvider<PullArtifacts> registerPullSource(
            Project project, ArtifactCollection projectSources, ArtifactCollection gradlePluginSources) {
        Provider<Map<String, String>> destinations = projectSources
                .getResolvedArtifacts()
                .zip(gradlePluginSources.getResolvedArtifacts(), AiDocsConsumeRootPlugin::destinationsByArtifactPath);
        return project.getTasks().register("pullSource", PullArtifacts.class, task -> {
            task.getArtifacts().from(projectSources.getArtifactFiles(), gradlePluginSources.getArtifactFiles());
            task.getDestinations().set(destinations);
            task.getOutputDirectory()
                    .set(project.getLayout().getProjectDirectory().dir("build/ai-docs/source"));
        });
    }

    private static TaskProvider<PullArtifacts> registerPullDocs(
            Project project, ArtifactCollection projectDocs, ArtifactCollection gradlePluginDocs) {
        Provider<Map<String, String>> destinations = projectDocs
                .getResolvedArtifacts()
                .zip(gradlePluginDocs.getResolvedArtifacts(), AiDocsConsumeRootPlugin::destinationsByArtifactPath);
        return project.getTasks().register("pullDocs", PullArtifacts.class, task -> {
            task.getArtifacts().from(projectDocs.getArtifactFiles(), gradlePluginDocs.getArtifactFiles());
            task.getDestinations().set(destinations);
            task.getOutputDirectory()
                    .set(project.getLayout().getProjectDirectory().dir("build/ai-docs/docs"));
        });
    }

    private static void registerConstructIndex(
            Project project,
            Provider<Set<ResolvedArtifactResult>> projectMainArtifacts,
            ArtifactCollection projectDocs,
            Provider<Set<ResolvedArtifactResult>> gradlePluginMainArtifacts,
            ArtifactCollection gradlePluginDocs,
            TaskProvider<PullArtifacts> pullSource,
            TaskProvider<PullArtifacts> pullDocs) {
        Provider<Map<String, Location>> projectDependencyLocations = projectMainArtifacts.zip(
                projectDocs.getResolvedArtifacts(), AiDocsConsumeRootPlugin::projectDependencyLocations);
        Provider<Map<String, Location>> gradlePluginLocations = gradlePluginMainArtifacts.zip(
                gradlePluginDocs.getResolvedArtifacts(), AiDocsConsumeRootPlugin::gradlePluginLocations);

        project.getTasks().register("constructIndex", ConstructIndex.class, task -> {
            task.getSourceDirectory().set(pullSource.flatMap(PullArtifacts::getOutputDirectory));
            task.getDocsDirectory().set(pullDocs.flatMap(PullArtifacts::getOutputDirectory));
            task.getProjectDependencyIdToLocations().set(projectDependencyLocations);
            task.getGradlePluginIdToLocations().set(gradlePluginLocations);
        });
    }

    private static void registerCheckAgentInstructions(Project project) {
        TaskProvider<CheckAgentInstructions> checkAgentInstructions =
                project.getTasks().register("checkAgentInstructions", CheckAgentInstructions.class);
        project.getTasks()
                .named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(check -> check.dependsOn(checkAgentInstructions));
    }

    private static void setUsageAttribute(Project project, Configuration configuration, String usage) {
        configuration
                .getAttributes()
                .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, usage));
    }

    private static ArtifactCollection sources(Project project, ResolvableConfiguration configuration) {
        return configuration
                .getIncoming()
                .artifactView(view -> {
                    view.withVariantReselection();
                    view.componentFilter(ModuleComponentIdentifier.class::isInstance);
                    view.getAttributes()
                            .attribute(
                                    DocsType.DOCS_TYPE_ATTRIBUTE,
                                    project.getObjects().named(DocsType.class, DocsType.SOURCES));
                })
                .getArtifacts();
    }

    private static Provider<Set<ResolvedArtifactResult>> moduleArtifacts(ResolvableConfiguration configuration) {
        return configuration
                .getIncoming()
                .artifactView(view -> view.componentFilter(ModuleComponentIdentifier.class::isInstance))
                .getArtifacts()
                .getResolvedArtifacts();
    }

    private static Provider<List<Dependency>> docsDependencies(
            Project project, Provider<Set<ResolvedArtifactResult>> mainArtifacts) {
        return mainArtifacts.map(artifacts -> docsDependencies(project, artifacts));
    }

    private static List<Dependency> docsDependencies(Project project, Set<ResolvedArtifactResult> artifacts) {
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        artifacts.forEach(artifact -> ArtifactMetadataUtils.docsCoordinates(artifact.getFile())
                .ifPresent(coordinates -> {
                    Dependency dependency = project.getDependencies().create(coordinates);
                    if (dependency instanceof ModuleDependency moduleDependency) {
                        moduleDependency.setTransitive(false);
                    }
                    dependencies.put(coordinates, dependency);
                }));
        return List.copyOf(dependencies.values());
    }

    private static Map<String, String> destinationsByArtifactPath(
            Set<ResolvedArtifactResult> first, Set<ResolvedArtifactResult> second) {
        Map<String, String> destinations = new TreeMap<>();
        addDestinations(destinations, first);
        addDestinations(destinations, second);
        return destinations;
    }

    private static void addDestinations(Map<String, String> destinations, Set<ResolvedArtifactResult> artifacts) {
        artifacts.forEach(artifact -> ModuleCoordinates.from(artifact)
                .ifPresent(coordinates ->
                        destinations.put(artifact.getFile().getAbsolutePath(), coordinates.destinationPath())));
    }

    private static Map<String, Location> projectDependencyLocations(
            Set<ResolvedArtifactResult> artifacts, Set<ResolvedArtifactResult> docsArtifacts) {
        Map<String, ModuleCoordinates> docs = docsByDependencyNotation(docsArtifacts);
        Map<String, Location> locations = new TreeMap<>();
        artifacts.forEach(artifact -> ModuleCoordinates.from(artifact)
                .ifPresent(coordinates -> locations.put(
                        coordinates.projectDependencyKey(), location(coordinates, docsFor(artifact, docs)))));
        return locations;
    }

    private static Map<String, Location> gradlePluginLocations(
            Set<ResolvedArtifactResult> artifacts, Set<ResolvedArtifactResult> docsArtifacts) {
        Map<String, ModuleCoordinates> docs = docsByDependencyNotation(docsArtifacts);
        Map<String, Location> locations = new TreeMap<>();
        artifacts.forEach(artifact -> ModuleCoordinates.from(artifact).ifPresent(coordinates -> {
            Location location = location(coordinates, docsFor(artifact, docs));
            ArtifactMetadataUtils.gradlePluginIds(artifact.getFile())
                    .forEach(pluginId -> locations.put(pluginId, location));
        }));
        return locations;
    }

    private static Location location(ModuleCoordinates code, Optional<ModuleCoordinates> docs) {
        return new Location(code.group(), code.version(), code.name(), docs.map(ModuleCoordinates::name));
    }

    private static Map<String, ModuleCoordinates> docsByDependencyNotation(Set<ResolvedArtifactResult> artifacts) {
        Map<String, ModuleCoordinates> docs = new TreeMap<>();
        artifacts.forEach(artifact -> ModuleCoordinates.from(artifact)
                .ifPresent(coordinates -> docs.put(coordinates.docsDependencyNotation(), coordinates)));
        return docs;
    }

    private static Optional<ModuleCoordinates> docsFor(
            ResolvedArtifactResult artifact, Map<String, ModuleCoordinates> docs) {
        return ArtifactMetadataUtils.docsCoordinates(artifact.getFile()).map(docs::get);
    }
}
