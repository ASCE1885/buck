/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.command.Build;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

public class BuildCommand extends AbstractCommand {

  private static final String KEEP_GOING_LONG_ARG = "--keep-going";
  private static final String BUILD_REPORT_LONG_ARG = "--build-report";
  private static final String JUST_BUILD_LONG_ARG = "--just-build";
  private static final String DEEP_LONG_ARG = "--deep";
  private static final String POPULATE_CACHE_LONG_ARG = "--populate-cache";
  private static final String SHALLOW_LONG_ARG = "--shallow";
  private static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";

  @Option(
      name = KEEP_GOING_LONG_ARG,
      usage = "Keep going when some targets can't be made.")
  private boolean keepGoing = false;

  @Option(
      name = BUILD_REPORT_LONG_ARG,
      usage = "File where build report will be written.")
  @Nullable
  private Path buildReport = null;

  @Nullable
  @Option(
      name = JUST_BUILD_LONG_ARG,
      usage = "For debugging, limits the build to a specific target in the action graph.",
      hidden = true)
  private String justBuildTarget = null;

  @Option(
      name = DEEP_LONG_ARG,
      usage =
          "Perform a \"deep\" build, which makes the output of all transitive dependencies" +
          " available.",
      forbids = SHALLOW_LONG_ARG)
  private boolean deepBuild = false;

  @Option(
      name = POPULATE_CACHE_LONG_ARG,
      usage =
          "Performs a cache population, which makes the output of all unchanged " +
              "transitive dependencies available (if these outputs are available " +
              "in the remote cache). Does not build changed or unavailable dependencies locally.",
      forbids = {SHALLOW_LONG_ARG, DEEP_LONG_ARG})
  private boolean populateCacheOnly = false;

  @Option(
      name = SHALLOW_LONG_ARG,
      usage =
          "Perform a \"shallow\" build, which only makes the output of all explicitly listed" +
          " targets available.",
      forbids = DEEP_LONG_ARG)
  private boolean shallowBuild = false;

  @Option(
      name = REPORT_ABSOLUTE_PATHS,
      usage =
          "Reports errors using absolute paths to the source files instead of relative paths.")
  private boolean shouldReportAbsolutePaths = false;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  private boolean buildTargetsHaveBeenCalculated;

  public List<String> getArguments() {
    return arguments;
  }

  private boolean isArtifactCacheDisabled = false;

  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }

  public BuildCommand() {
    this(ImmutableList.<String>of());
  }

  public BuildCommand(List<String> arguments) {
    this.arguments.addAll(arguments);
  }

  public Optional<CachingBuildEngine.BuildMode> getBuildEngineMode() {
    Optional<CachingBuildEngine.BuildMode> mode = Optional.absent();
    if (deepBuild) {
      mode = Optional.of(CachingBuildEngine.BuildMode.DEEP);
    }
    if (populateCacheOnly) {
      mode = Optional.of(CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE);
    }
    if (shallowBuild) {
      mode = Optional.of(CachingBuildEngine.BuildMode.SHALLOW);
    }
    return mode;
  }

  public void setArtifactCacheDisabled(boolean value) {
    isArtifactCacheDisabled = value;
  }

  public boolean isArtifactCacheDisabled() {
    return isArtifactCacheDisabled;
  }

  public boolean isKeepGoing() {
    return keepGoing;
  }

  protected boolean shouldReportAbsolutePaths() {
    return shouldReportAbsolutePaths;
  }

  public void setKeepGoing(boolean keepGoing) {
    this.keepGoing = keepGoing;
  }

  /**
   * @return an absolute path or {@link Optional#absent()}.
   */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.fromNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  Build createBuild(
      BuckConfig buckConfig,
      ActionGraph graph,
      BuildRuleResolver ruleResolver,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      Console console,
      BuckEventBus eventBus,
      Optional<TargetDevice> targetDevice,
      Platform platform,
      ImmutableMap<String, String> environment,
      ObjectMapper objectMapper,
      Clock clock,
      Optional<AdbOptions> adbOptions,
      Optional<TargetDeviceOptions> targetDeviceOptions) {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("Creating a build with %d threads.\n", buckConfig.getNumThreads());
    }
    return new Build(
        graph,
        ruleResolver,
        targetDevice,
        androidPlatformTargetSupplier,
        buildEngine,
        artifactCache,
        buckConfig.createDefaultJavaPackageFinder(),
        console,
        buckConfig.getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled(),
        shouldReportAbsolutePaths(),
        eventBus,
        platform,
        environment,
        objectMapper,
        clock,
        getConcurrencyLimit(buckConfig),
        adbOptions,
        targetDeviceOptions);
  }

  @Nullable private Build lastBuild;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  @Override
  @SuppressWarnings("PMD.PrematureDeclaration")
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    return run(params, ImmutableSet.<String>of());
  }

  protected int run(CommandRunnerParams params, ImmutableSet<String> additionalTargets)
      throws IOException, InterruptedException {
    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = params.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        params.getBuckEventBus().post(ConsoleEvent.severe(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)))));
      }
      return 1;
    }

    if (!additionalTargets.isEmpty()){
      this.arguments.addAll(additionalTargets);
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    BuildEvent.Started started = BuildEvent.started(getArguments());
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          started,
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(started);
    }

    try (CommandThreadManager pool = new CommandThreadManager(
        "Build",
        params.getBuckConfig().getWorkQueueExecutionOrder(),
        getConcurrencyLimit(params.getBuckConfig()))) {
      // Parse the build files to create a ActionGraph.
      Pair<ActionGraph, BuildRuleResolver> actionGraphAndResolver =
          createActionGraphAndResolver(params, pool.getExecutor());
      if (actionGraphAndResolver == null) {
        return 1;
      }

      int exitCode = executeBuild(params, actionGraphAndResolver, pool.getExecutor());
      params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));
      return exitCode;
    }
  }

  @Nullable
  public Pair<ActionGraph, BuildRuleResolver> createActionGraphAndResolver(
      CommandRunnerParams params,
      Executor executor)
      throws IOException, InterruptedException {
    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = params.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        params.getBuckEventBus().post(ConsoleEvent.severe(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)))));
      }
      return null;
    }

    // Parse the build files to create a ActionGraph.
    Pair<ActionGraph, BuildRuleResolver> actionGraphAndResolver;
    try {
      Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  getArguments()));
      buildTargets = result.getFirst();
      buildTargetsHaveBeenCalculated = true;
      TargetGraphToActionGraph targetGraphToActionGraph =
          new TargetGraphToActionGraph(
              params.getBuckEventBus(),
              new BuildTargetNodeToBuildRuleTransformer());
      actionGraphAndResolver = Preconditions.checkNotNull(
          targetGraphToActionGraph.apply(result.getSecond()));
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return null;
    }

    // If the user specified an explicit build target, use that.
    if (justBuildTarget != null) {
      BuildTarget explicitTarget = BuildTargetParser.INSTANCE.parse(
          justBuildTarget,
          BuildTargetPatternParser.fullyQualified(),
          params.getCell().getCellRoots());
      Iterable<BuildRule> actionGraphRules =
          Preconditions.checkNotNull(actionGraphAndResolver.getFirst().getNodes());
      ImmutableSet<BuildTarget> actionGraphTargets =
          ImmutableSet.copyOf(Iterables.transform(actionGraphRules, HasBuildTarget.TO_TARGET));
      if (!actionGraphTargets.contains(explicitTarget)) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            "Targets specified via `--just-build` must be a subset of action graph."));
        return null;
      }
      buildTargets = ImmutableSet.of(explicitTarget);
    }

    return actionGraphAndResolver;
  }

  protected int executeBuild(
      CommandRunnerParams params,
      Pair<ActionGraph, BuildRuleResolver> actionGraphAndResolver,
      ListeningExecutorService executor)
      throws IOException, InterruptedException {

    ArtifactCache artifactCache = params.getArtifactCache();
    if (isArtifactCacheDisabled()) {
      artifactCache = new NoopArtifactCache();
    }

    try (Build build = createBuild(
        params.getBuckConfig(),
        actionGraphAndResolver.getFirst(),
        actionGraphAndResolver.getSecond(),
        params.getAndroidPlatformTargetSupplier(),
        new CachingBuildEngine(
            executor,
            params.getFileHashCache(),
            getBuildEngineMode().or(params.getBuckConfig().getBuildEngineMode()),
            params.getBuckConfig().getDependencySchedulingOrder(),
            params.getBuckConfig().getBuildDepFiles(),
            params.getBuckConfig().getBuildMaxDepFileCacheEntries(),
            actionGraphAndResolver.getSecond()),
        artifactCache,
        params.getConsole(),
        params.getBuckEventBus(),
        Optional.<TargetDevice>absent(),
        params.getPlatform(),
        params.getEnvironment(),
        params.getObjectMapper(),
        params.getClock(),
        Optional.<AdbOptions>absent(),
        Optional.<TargetDeviceOptions>absent())) {
      lastBuild = build;
      return build.executeAndPrintFailuresToEventBus(
          buildTargets,
          isKeepGoing(),
          params.getBuckEventBus(),
          params.getConsole(),
          getPathToBuildReport(params.getBuckConfig()));
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  Build getBuild() {
    Preconditions.checkNotNull(lastBuild);
    return lastBuild;
  }

  public ImmutableList<BuildTarget> getBuildTargets() {
    Preconditions.checkState(buildTargetsHaveBeenCalculated);
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

  @Override
  protected ImmutableList<String> getOptions() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(super.getOptions());
    if (keepGoing) {
      builder.add(KEEP_GOING_LONG_ARG);
    }
    if (buildReport != null) {
      builder.add(BUILD_REPORT_LONG_ARG);
      builder.add(buildReport.toString());
    }
    if (justBuildTarget != null) {
      builder.add(JUST_BUILD_LONG_ARG);
      builder.add(justBuildTarget);
    }
    if (shouldReportAbsolutePaths) {
      builder.add(REPORT_ABSOLUTE_PATHS);
    }
    return builder.build();
  }
}
