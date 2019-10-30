/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.rules.attr;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;

/**
 * {@link com.facebook.buck.core.rules.BuildRule} instances that support multiple outputs via output
 * labels.
 */
public interface HasMultipleOutputs extends BuildRule {
  /**
   * Returns the {@link SourcePath} to the output associated with the given output label, or {@code
   * null} if it does not exist.
   */
  @Nullable
  SourcePath getSourcePathToOutput(String outputLabel);

  /** Returns a map of {@link SourcePath} instances keyed by associated output labels. */
  ImmutableMap<String, SourcePath> getSourcePathsByOutputsLabels();
}