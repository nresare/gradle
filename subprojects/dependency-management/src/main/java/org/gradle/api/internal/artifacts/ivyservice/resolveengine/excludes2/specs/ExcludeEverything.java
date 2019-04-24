/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes2.specs;

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.internal.component.model.IvyArtifactName;

class ExcludeEverything implements ExcludeSpec {
    private static final ExcludeEverything INSTANCE = new ExcludeEverything();

    public static ExcludeSpec get() {
        return INSTANCE;
    }

    private ExcludeEverything() {
    }

    @Override
    public boolean excludes(ModuleIdentifier module) {
        return true;
    }

    @Override
    public boolean excludesGroup(String group) {
        return true;
    }

    @Override
    public boolean excludesModule(String module) {
        return true;
    }

    @Override
    public boolean excludesArtifact(IvyArtifactName artifactName) {
        return true;
    }

    @Override
    public String toString() {
        return "{excludes everything}";
    }

    @Override
    public int hashCode() {
        return 1;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }
}
