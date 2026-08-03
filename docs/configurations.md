# Gradle configurations

Each producer project shares its Gradle plugin and project dependencies with the consumer root project.

The consumer tasks pull documentation and source code to disk. They also build an index that lets agents find those files.

```mermaid
flowchart TD
    subgraph PRODUCERS["subproject-a"]
        PROJECT_CLASSPATHS["Resolvable configurations<br/>────────────<br/>runtimeClasspath<br/>compileClasspath<br/>testRuntimeClasspath"] --> PROJECT_OUT["ConsumableConfiguration<br/>────────────<br/>outgoingProjectDependencies<br/>Usage: project-dependencies"]
        BUILDSCRIPT_CLASSPATH["NamedDomainObjectProvider&lt;Configuration&gt;<br/>────────────<br/>Buildscript classpath"] --> FILTER_PLUGIN_DEPS(["Provider&lt;List&lt;Dependency&gt;&gt;<br/>────────────<br/>filter(META-INF/gradle-plugins has files)"]) --> PLUGIN_OUT["ConsumableConfiguration<br/>────────────<br/>outgoingGradlePlugins<br/>Usage: gradle-plugin-dependencies"]
    end

    SUBPROJECT_SCOPE["DependencyScopeConfiguration<br/>────────────<br/>subprojects"]

    subgraph ROOT["Root project"]
        SUBPROJECT_SCOPE --> PROJECT_IN["ResolvableConfiguration<br/>────────────<br/>incomingProjectDependencies<br/>Usage: project-dependencies"]
        SUBPROJECT_SCOPE --> PLUGIN_IN["ResolvableConfiguration<br/>────────────<br/>incomingGradlePlugins<br/>Usage: gradle-plugin-dependencies"]
        PROJECT_OUT --> PROJECT_IN
        PLUGIN_OUT --> PLUGIN_IN

        PROJECT_IN --> PROJECT_SOURCES["ArtifactCollection<br/>────────────<br/>projectSources"]
        PROJECT_IN --> PROJECT_DOC_SCOPE["DependencyScopeConfiguration<br/>────────────<br/>projectDependencyDocs"] --> PROJECT_DOC_IN["ResolvableConfiguration<br/>────────────<br/>incomingProjectDependencyDocs"] --> PROJECT_DOCS["ArtifactCollection<br/>────────────<br/>projectDocs"]
        PLUGIN_IN --> PLUGIN_SOURCES["ArtifactCollection<br/>────────────<br/>gradlePluginSources"]
        PLUGIN_IN --> PLUGIN_DOC_SCOPE["DependencyScopeConfiguration<br/>────────────<br/>gradlePluginDocs"] --> PLUGIN_DOC_IN["ResolvableConfiguration<br/>────────────<br/>incomingGradlePluginDocs"] --> PLUGIN_DOCS["ArtifactCollection<br/>────────────<br/>gradlePluginDocs"]

        PROJECT_SOURCES --> PULL_SOURCE["Task: PullArtifacts<br/>────────────<br/>pullSource"]
        PLUGIN_SOURCES --> PULL_SOURCE
        PROJECT_DOCS --> PULL_DOCS["Task: PullArtifacts<br/>────────────<br/>pullDocs"]
        PLUGIN_DOCS --> PULL_DOCS

        PROJECT_SOURCES --> CONSTRUCT_INDEX["Task: ConstructIndex<br/>────────────<br/>constructIndex"]
        PROJECT_DOCS --> CONSTRUCT_INDEX
        PLUGIN_SOURCES --> CONSTRUCT_INDEX
        PLUGIN_DOCS --> CONSTRUCT_INDEX
        PULL_SOURCE --> CONSTRUCT_INDEX
        PULL_DOCS --> CONSTRUCT_INDEX
    end

    ROOT -.-> SUBPROJECT_SCOPE
    SUBPROJECT_SCOPE -. dependsOn .-> PRODUCERS

    classDef consumable fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef scope fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef resolvable fill:#e1f5fe,stroke:#0277bd,stroke-width:2px
    classDef provider fill:#fce4ec,stroke:#c2185b,stroke-width:2px
    classDef task fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px
    classDef operation fill:#fafafa,stroke:#616161,stroke-width:2px,stroke-dasharray:5 5

    class PROJECT_OUT,PLUGIN_OUT consumable
    class SUBPROJECT_SCOPE,PROJECT_DOC_SCOPE,PLUGIN_DOC_SCOPE scope
    class PROJECT_IN,PLUGIN_IN,PROJECT_DOC_IN,PLUGIN_DOC_IN resolvable
    class PROJECT_SOURCES,PROJECT_DOCS,PLUGIN_SOURCES,PLUGIN_DOCS provider
    class PULL_SOURCE,PULL_DOCS,CONSTRUCT_INDEX task
    class FILTER_PLUGIN_DEPS operation
```
