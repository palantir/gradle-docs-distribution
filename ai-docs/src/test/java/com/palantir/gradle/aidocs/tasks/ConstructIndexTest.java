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

package com.palantir.gradle.aidocs.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstructIndexTest {
    @Test
    void snippet_is_first_paragraph_under_first_h1() {
        assertThat(ConstructIndex.constructIndexSnippet("""
            Intro before the heading.

            # Dependency

            First line of the summary
            continues here.

            ## Details

            Not part of the snippet.
            """)).isEqualTo("First line of the summary continues here.");
    }

    @Test
    void snippet_is_empty_without_an_h1() {
        assertThat(ConstructIndex.constructIndexSnippet("""
            ## Only an H2

            Details.
            """)).isEmpty();
    }
}
