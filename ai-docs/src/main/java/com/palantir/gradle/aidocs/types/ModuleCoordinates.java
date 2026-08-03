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

package com.palantir.gradle.aidocs.types;

import java.util.Optional;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;

/** Converts a resolved module artifact into stable dependency notation and destination paths. */
public record ModuleCoordinates(String group, String name, String version) {
    public static Optional<ModuleCoordinates> from(ResolvedArtifactResult artifact) {
        ComponentIdentifier identifier = artifact.getId().getComponentIdentifier();
        if (identifier instanceof ModuleComponentIdentifier module) {
            return Optional.of(new ModuleCoordinates(module.getGroup(), module.getModule(), module.getVersion()));
        }
        return Optional.empty();
    }

    public String dependencyNotation() {
        return "%s:%s:%s".formatted(group, name, version);
    }

    public String docsDependencyNotation() {
        return dependencyNotation() + ":docs";
    }

    public String projectDependencyKey() {
        return group + ':' + name;
    }

    public String destinationPath() {
        return group.replace('.', '/') + '/' + name + '/' + version;
    }
}
