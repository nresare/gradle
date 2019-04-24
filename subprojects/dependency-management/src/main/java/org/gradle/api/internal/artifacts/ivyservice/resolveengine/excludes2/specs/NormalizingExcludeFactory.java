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

import org.gradle.api.specs.CompositeSpec;

import javax.annotation.Nullable;

public class NormalizingExcludeFactory extends DefaultExcludeFactory {
    @Override
    public ExcludeSpec anyOf(ExcludeSpec... specs) {
        if (specs.length == 0) {
            return nothing();
        }
        ExcludeSpec union = null;
        for (ExcludeSpec spec : specs) {
            union = performUnion(union, spec);
        }
        return union;
    }

    private ExcludeSpec performUnion(@Nullable ExcludeSpec left, ExcludeSpec right) {
        if (left == null) {
            return right;
        }
        if (left.equals(right)) {
            return left;
        }
        if (left instanceof CompositeSpec) {
            ExcludeSpec origLeft = left;
            left = right;
            right = origLeft;
        }
        if (left instanceof ExcludeEverything) {
            return left;
        }
        if (right instanceof ExcludeEverything) {
            return right;
        }
        if (left instanceof ExcludeNothing) {
            return right;
        }
        if (right instanceof ExcludeNothing) {
            return left;
        }
        if (left instanceof DefaultExclude) {
            return performUnion((DefaultExclude) left, right);
        }
        if (left instanceof ExcludeAllOf) {
            return performUnion((ExcludeAllOf) left, right);
        }
        if (left instanceof ExcludeAnyOf) {
            return performUnion((ExcludeAnyOf) left, right);
        }
        throw new UnsupportedOperationException("Unexpected spec type: " + left);
    }

    private ExcludeSpec performUnion(DefaultExclude left, ExcludeSpec right) {
        if (right instanceof DefaultExclude) {
            return performUnion(left, (DefaultExclude) right);
        }
        return super.anyOf(left, right);
    }

    private ExcludeSpec performUnion(DefaultExclude left, DefaultExclude right) {
        ExcludeSpec merged = trySimplifyUnion(left, right);
        if (merged != null) {
            return merged;
        }
        merged = trySimplifyUnion(right, left);
        if (merged != null) {
            return merged;
        }
        return super.anyOf(left, right);
    }

    private ExcludeSpec trySimplifyUnion(DefaultExclude left, DefaultExclude right) {
        if (left.group != null && left.group.equals(right.group)) {
            if (left.module == null && (right.module != null || right.artifact != null)) {
                return left;
            }
        }
        return null;
    }

    private ExcludeSpec performUnion(ExcludeAllOf left, ExcludeSpec right) {
        return allOf(left.components().map(e -> performUnion(e, right)).toArray(ExcludeSpec[]::new));
    }

    private ExcludeSpec performUnion(ExcludeAnyOf left, ExcludeSpec right) {
        return left.or(right);
    }

}
