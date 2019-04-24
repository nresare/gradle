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

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.component.model.IvyArtifactName;

class ExcludeAnyOf extends CompositeExclude {
    public static ExcludeSpec of(ExcludeSpec... components) {
        return new ExcludeAnyOf(ImmutableSet.copyOf(components));
    }

    private ExcludeAnyOf(ImmutableSet<ExcludeSpec> components) {
        super(components);
    }

    @Override
    protected String getDisplayName() {
        return "any of";
    }

    @Override
    public boolean excludesGroup(String group) {
        return components().anyMatch(e -> e.excludesGroup(group));
    }

    @Override
    public boolean excludesModule(String module) {
        return components().anyMatch(e -> e.excludesModule(module));
    }

    @Override
    public boolean excludesArtifact(IvyArtifactName artifactName) {
        return components().anyMatch((e) -> e.excludesArtifact(artifactName));
    }

    ExcludeSpec or(ExcludeSpec other) {
        ImmutableSet.Builder<ExcludeSpec> builder = ImmutableSet.builderWithExpectedSize(1 + size());
        collectInto(builder);
        builder.add(other);
        return new ExcludeAnyOf(builder.build());
    }
}
