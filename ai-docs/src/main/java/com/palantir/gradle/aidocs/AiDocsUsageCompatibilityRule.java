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

import java.util.Set;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.Usage;

public abstract class AiDocsUsageCompatibilityRule implements AttributeCompatibilityRule<Usage> {
    private static final Set<String> AI_DOCS_USAGES =
            Set.of(AiDocsConsumeRootPlugin.PROJECT_DEPENDENCIES, AiDocsConsumeRootPlugin.GRADLE_PLUGINS);
    private static final String JAVA_USAGE = Usage.JAVA_RUNTIME;

    @Override
    public final void execute(CompatibilityCheckDetails<Usage> details) {
        Usage consumer = details.getConsumerValue();
        Usage producer = details.getProducerValue();
        if (consumer != null
                && producer != null
                && AI_DOCS_USAGES.contains(consumer.getName())
                && JAVA_USAGE.equals(producer.getName())) {
            details.compatible();
        }
    }
}
