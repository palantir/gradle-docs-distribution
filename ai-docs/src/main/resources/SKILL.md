---
name: read-dependencies
description: Read before answering any question or doing any implementation that requires understanding services, libraries, Gradle plugins (lint, compile, packaging, publishing, distribute, bundling, Docker).
---

# Find relevant dependencies

Read `$ROOT_PROJECT/build/ai-docs/dependency-index.yaml` to find relevant Gradle plugins and project dependencies to read. Run `./gradlew constructIndex` if it does not exist.

# Locate relevant docs and source

```bash
./.claude/skills/read-dependencies/locate --type=plugin \
    com.palantir.sls-docker \
    com.palantir.publish-dist

./.claude/skills/read-dependencies/locate --type=project-dependency \
    com.palantir.witchcraft:witchcraft
```
