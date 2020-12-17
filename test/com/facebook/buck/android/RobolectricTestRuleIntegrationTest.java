/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class RobolectricTestRuleIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() {
    // TODO(T47912516): Remove once we can upgrade our Robolectric libraries and run this on Java
    //                  11.
    Assume.assumeThat(JavaVersion.getMajorVersion(), Matchers.lessThanOrEqualTo(8));
  }

  @Test
  public void testRobolectricTestBuildsWithDummyR() throws IOException {

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestAddsRequiredPath() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/lib:test_binary_resources").assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();

    ImmutableList<String> robolectricResourceDirectoriesPath =
        requiredPaths.stream()
            .filter(path -> path.contains("robolectric-resource-directories"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, robolectricResourceDirectoriesPath.size());

    ImmutableList<String> robolectricAssetDirectoriesPath =
        requiredPaths.stream()
            .filter(path -> path.contains("robolectric-asset-directories"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, robolectricAssetDirectoriesPath.size());

    ImmutableList<String> robolectricResourceApkPath =
        requiredPaths.stream()
            .filter(path -> path.contains("resource-apk.ap_"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, robolectricResourceApkPath.size());

    ImmutableList<String> robolectricManifestPath =
        requiredPaths.stream()
            .filter(path -> path.contains("TestAndroidManifest.xml"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, robolectricManifestPath.size());

    ImmutableList<String> robolectricRuntimeDepsDirEntries =
        requiredPaths.stream()
            .filter(path -> path.contains("robolectric_dir"))
            .collect(ImmutableList.toImmutableList());
    assertTrue(robolectricRuntimeDepsDirEntries.size() > 0);

    ImmutableList<String> androidJarEntries =
        requiredPaths.stream()
            .filter(path -> path.contains("android.jar"))
            .collect(ImmutableList.toImmutableList());
    assertFalse(androidJarEntries.isEmpty());
  }

  @Test
  public void testRobolectricTestWithLegacyResourcesAddsRequiredPaths() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();

    ImmutableList<String> hilarityAssetsSymlinkPath =
        requiredPaths.stream()
            .filter(path -> path.contains("assets-symlink-tree/assets/hilarity.txt"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, hilarityAssetsSymlinkPath.size());

    ImmutableList<String> hilarityAssetsOriginalPath =
        requiredPaths.stream()
            .filter(path -> path.contains("res/com/sample/base/buck-assets/hilarity.txt"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, hilarityAssetsOriginalPath.size());

    ImmutableList<String> topLayoutResourceSymlinkPath =
        requiredPaths.stream()
            .filter(path -> path.contains("resources-symlink-tree/res/layout/top_layout.xml"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, topLayoutResourceSymlinkPath.size());

    ImmutableList<String> topLayoutResourceOriginalPath =
        requiredPaths.stream()
            .filter(path -> path.contains("res/com/sample/top/res/layout/top_layout.xml"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, topLayoutResourceOriginalPath.size());
  }

  @Test
  public void testNoBootClasspathInRequiredPath() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.addBuckConfigLocalOption("test", "include_boot_classpath_in_required_paths", "false");
    workspace.runBuckTest("//java/com/sample/lib:test_binary_resources").assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();

    ImmutableList<String> androidJarEntries =
        requiredPaths.stream()
            .filter(path -> path.contains("android.jar"))
            .collect(ImmutableList.toImmutableList());
    assertTrue(androidJarEntries.isEmpty());
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInArgument()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInFile()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithRobolectricRuntimeDependencyArgument()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    workspace.runBuckTest("//java/com/sample/lib:test_robolectric_runtime_dep").assertSuccess();
  }

  @Test
  public void robolectricTestBuildsWithBinaryResources() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test_binary_resources").assertSuccess();
  }

  @Test
  public void robolectricTestXWithExternalRunner() throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_with_runner").assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertTrue(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void robolectricTestXWithExternalRunnerWithRobolectricRuntimeDependencyArgument()
      throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_with_runner_runtime_dep");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertFalse(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void robolectricTestXWithExternalRunnerWithoutRobolectricRuntimeDependencyArgument()
      throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_without_runner_runtime_dep_failed");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertTrue(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(1, processResult.getExitCode());
    assertTrue(processResult.getStderr().isPresent());
    assertTrue(processResult.getStderr().get().contains("java.lang.ClassNotFoundException"));
  }
}
