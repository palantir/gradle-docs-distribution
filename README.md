# gradle-docs-distribution

`gradle-docs-distribution` provides two Gradle plugins that give AI agents local source code and documentation for a repository's dependencies.

- `com.palantir.ai-docs.produce.root` publishes documentation from the root project and records its coordinates in project JARs.
- `com.palantir.ai-docs.consume.root` resolves those sources and docs, pulls them into `build/ai-docs`, and points agents to them.

`com.palantir.ai-docs` applies both plugins.

## Configuration wiring

Producer projects share their Gradle plugin and project dependencies with the consumer root project. The consumer uses them to build a dependency index.

Modern Gradle uses configurations and artifact transforms to share this information. See [Gradle configurations](docs/configurations.md) for the wiring between projects.
