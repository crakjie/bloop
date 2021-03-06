package bloop.engine

import bloop.CompileMode
import bloop.bsp.BspServer
import bloop.cli._
import bloop.cli.CliParsers.CommandsMessages
import bloop.cli.completion.{Case, Mode}
import bloop.io.{AbsolutePath, RelativePath, SourceWatcher}
import bloop.logging.DebugFilter
import bloop.testing.{LoggingEventHandler, TestInternals}
import bloop.engine.tasks.{CompilationTask, LinkTask, Tasks}
import bloop.cli.Commands.CompilingCommand
import bloop.data.{Platform, Project}
import bloop.engine.Feedback.XMessageString
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import monix.eval.Task

object Interpreter {
  // This is stack-safe because of Monix's trampolined execution
  def execute(action: Action, stateTask: Task[State]): Task[State] = {
    def execute(action: Action, stateTask: Task[State], inRecursion: Boolean): Task[State] = {
      stateTask.flatMap { state =>
        action match {
          // We keep it case because there is a 'match may not be exhaustive' false positive by scalac
          // Looks related to existing bug report https://github.com/scala/bug/issues/10251
          case Exit(exitStatus: ExitStatus) if exitStatus.isOk =>
            Task.now(state.mergeStatus(exitStatus))
          case Exit(exitStatus: ExitStatus) => Task.now(state.mergeStatus(exitStatus))
          case Print(msg, _, next) =>
            state.logger.info(msg)
            execute(next, Task.now(state), true)
          case Run(Commands.About(_), next) =>
            execute(next, printAbout(state), true)
          case Run(cmd: Commands.ValidatedBsp, next) =>
            execute(next, runBsp(cmd, state), true)
          case Run(cmd: Commands.Clean, next) =>
            execute(next, clean(cmd, state), true)
          case Run(cmd: Commands.Compile, next) =>
            execute(next, compile(cmd, state, inRecursion), true)
          case Run(cmd: Commands.Console, next) =>
            execute(next, console(cmd, state, inRecursion), true)
          case Run(cmd: Commands.Projects, next) =>
            execute(next, showProjects(cmd, state), true)
          case Run(cmd: Commands.Test, next) =>
            execute(next, test(cmd, state, inRecursion), true)
          case Run(cmd: Commands.Run, next) =>
            execute(next, run(cmd, state, inRecursion), true)
          case Run(cmd: Commands.Configure, next) =>
            execute(next, configure(cmd, state), true)
          case Run(cmd: Commands.Autocomplete, next) =>
            execute(next, autocomplete(cmd, state), true)
          case Run(cmd: Commands.Link, next) =>
            execute(next, link(cmd, state, inRecursion), true)
          case Run(cmd: Commands.Help, _) =>
            val msg = "The handling of `help` does not happen in the `Interpreter`"
            val printAction = Print(msg, cmd.cliOptions.common, Exit(ExitStatus.UnexpectedError))
            execute(printAction, Task.now(state), true)
          case Run(cmd: Commands.Bsp, _) =>
            val msg = "Internal error: The `bsp` command must be validated before use"
            val printAction = Print(msg, cmd.cliOptions.common, Exit(ExitStatus.UnexpectedError))
            execute(printAction, Task.now(state), true)
        }
      }
    }

    execute(action, stateTask, inRecursion = false)
  }

  private final val t = "    "
  private final val line = System.lineSeparator()
  private def printAbout(state: State): Task[State] = Task {
    import state.logger
    val bloopName = bloop.internal.build.BuildInfo.bloopName
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")

    logger.info(s"$bloopName v$bloopVersion")
    logger.info("")
    logger.info(s"Running on Scala v$scalaVersion and Zinc v$zincVersion")
    logger.info(s"Maintained by the Scala Center ($developers)")

    state.mergeStatus(ExitStatus.Ok)
  }

  private def runBsp(cmd: Commands.ValidatedBsp, state: State): Task[State] = {
    val scheduler = ExecutionContext.bspScheduler
    BspServer.run(cmd, state, RelativePath(".bloop"), scheduler)
  }

  private[bloop] def watch(project: Project, state: State)(f: State => Task[State]): Task[State] = {
    val reachable = Dag.dfs(state.build.getDagFor(project))
    val allSources = reachable.iterator.flatMap(_.sources.toList).map(_.underlying)
    val watcher = SourceWatcher(project, allSources.toList, state.logger)
    val fg = (state: State) =>
      f(state).map { state =>
        watcher.notifyWatch()
        State.stateCache.updateBuild(state)
    }

    if (!BspServer.isWindows)
      state.logger.info("\u001b[H\u001b[2J")
    // Force the first execution before relying on the file watching task
    fg(state).flatMap(newState => watcher.watch(newState, fg))
  }

  private def runCompile(
      cmd: CompilingCommand,
      state0: State,
      project: Project,
      deduplicateFailures: Boolean,
      excludeRoot: Boolean = false
  ): Task[State] = {
    // Make new state cleaned of all compilation products if compilation is not incremental
    val state: Task[State] = {
      if (cmd.incremental) Task.now(state0)
      else Tasks.clean(state0, state0.build.projects, true)
    }

    val compilerMode: CompileMode.ConfigurableMode = CompileMode.Sequential
    val compileTask = state.flatMap { state =>
      val config = ReporterKind.toReporterConfig(cmd.reporter)
      val createReporter = (project: Project, cwd: AbsolutePath) =>
        CompilationTask.toReporter(project, cwd, config, state.logger)
      CompilationTask.compile(
        state,
        project,
        createReporter,
        deduplicateFailures,
        compilerMode,
        cmd.pipeline,
        excludeRoot
      )
    }

    compileTask.map(_.mergeStatus(ExitStatus.Ok))
  }

  private def compile(
      cmd: Commands.Compile,
      state: State,
      deduplicateFailures: Boolean
  ): Task[State] = {
    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        if (!cmd.watch) runCompile(cmd, state, project, deduplicateFailures)
        else watch(project, state)(runCompile(cmd, _, project, deduplicateFailures))
      case None => Task.now(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): Task[State] = Task {
    import state.logger
    if (cmd.dotGraph) {
      val contents = Dag.toDotGraph(state.build.dags)
      logger.info(contents)
    } else {
      val configDirectory = state.build.origin.syntax
      logger.debug(s"Projects loaded from '$configDirectory':")(DebugFilter.All)
      state.build.projects.map(_.name).sorted.foreach(logger.info)
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def compileAnd[C <: CompilingCommand](
      cmd: C,
      state: State,
      project: Project,
      excludeRoot: Boolean,
      deduplicateFailures: Boolean,
      nextAction: String
  )(next: State => Task[State]): Task[State] = {
    runCompile(cmd, state, project, deduplicateFailures, excludeRoot).flatMap { compiled =>
      if (compiled.status != ExitStatus.CompilationError) next(compiled)
      else {
        Task.now(
          state.withDebug(s"Failed compilation for $project. Skipping $nextAction...")(
            DebugFilter.Compilation
          )
        )
      }
    }
  }

  private def console(cmd: Commands.Console, state: State, sequential: Boolean): Task[State] = {
    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        compileAnd(cmd, state, project, cmd.excludeRoot, sequential, "`console`")(
          state => Tasks.console(state, project, cmd.excludeRoot)
        )
      case None => Task.now(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def test(cmd: Commands.Test, state: State, sequential: Boolean): Task[State] = {
    Tasks.pickTestProject(cmd.project, state) match {
      case Some(project) =>
        def doTest(state: State): Task[State] = {
          val testFilter = TestInternals.parseFilters(cmd.only)
          val cwd = project.baseDirectory
          compileAnd(cmd, state, project, false, sequential, "`test`") { state =>
            val handler = new LoggingEventHandler(state.logger)
            Tasks.test(state, project, cwd, cmd.includeDependencies, cmd.args, testFilter, handler)
          }
        }

        if (cmd.watch) watch(project, state)(doTest _) else doTest(state)

      case None =>
        Task.now(reportMissing(cmd.project :: Nil, state))
    }
  }

  type ProjectLookup = (List[Project], List[String])
  private def lookupProjects(names: List[String], state: State): ProjectLookup = {
    val build = state.build
    val result = List[Project]() -> List[String]()
    names.foldLeft(result) {
      case ((projects, missing), name) =>
        build.getProjectFor(name) match {
          case Some(project) => (project :: projects) -> missing
          case None => projects -> (name :: missing)
        }
    }
  }

  private def autocomplete(cmd: Commands.Autocomplete, state: State): Task[State] = Task {
    cmd.mode match {
      case Mode.ProjectBoundCommands =>
        state.logger.info(Commands.projectBound)
      case Mode.Commands =>
        for {
          (name, args) <- CommandsMessages.messages
          completion <- cmd.format.showCommand(name, args)
        } state.logger.info(completion)
      case Mode.Projects =>
        for {
          project <- state.build.projects
          completion <- cmd.format.showProject(project)
        } state.logger.info(completion)
      case Mode.Flags =>
        for {
          command <- cmd.command
          message <- CommandsMessages.messages.toMap.get(command)
          arg <- message.args
          completion <- cmd.format.showArg(command, Case.kebabizeArg(arg))
        } state.logger.info(completion)
      case Mode.Reporters =>
        for {
          reporter <- ReporterKind.reporters
          completion <- cmd.format.showReporter(reporter)
        } state.logger.info(completion)
      case Mode.Protocols =>
        for {
          protocol <- BspProtocol.protocols
          completion <- cmd.format.showProtocol(protocol)
        } state.logger.info(completion)
      case Mode.MainsFQCN =>
        for {
          projectName <- cmd.project
          project <- state.build.getProjectFor(projectName)
          main <- Tasks.findMainClasses(state, project)
          completion <- cmd.format.showMainName(main)
        } state.logger.info(completion)
      case Mode.TestsFQCN =>
        for {
          projectName <- cmd.project
          placeholder <- List.empty[String]
          completion <- cmd.format.showTestName(placeholder)
        } state.logger.info(completion)
    }

    state
  }

  private def configure(cmd: Commands.Configure, state: State): Task[State] = Task {
    if (cmd.threads == Commands.DefaultThreadNumber) state
    else {
      // Don't support dynamic thread configuration because underlying thread pools sometimes can't
      state.withWarn(s"Dynamic thread configuration has been deprecated and is a no-op.")
    }
  }

  private def clean(cmd: Commands.Clean, state: State): Task[State] = {
    if (cmd.project.isEmpty)
      Tasks
        .clean(state, state.build.projects, cmd.includeDependencies)
        .map(_.mergeStatus(ExitStatus.Ok))
    else {
      val (projects, missing) = lookupProjects(cmd.project, state)
      if (missing.isEmpty)
        Tasks.clean(state, projects, cmd.includeDependencies).map(_.mergeStatus(ExitStatus.Ok))
      else Task.now(reportMissing(missing, state))
    }
  }

  private[bloop] def link(cmd: Commands.Link, state: State, sequential: Boolean): Task[State] = {
    def doLink(project: Project)(state: State): Task[State] = {
      compileAnd(cmd, state, project, false, sequential, "`link`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(state) => Task.now(state)
          case Right(mainClass) =>
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithNative(cmd, project, state, mainClass, target, platform)

              case platform @ Platform.Js(config, _, _) =>
                val target = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithJs(cmd, project, state, mainClass, target, platform)

              case Platform.Jvm(_, _, _) =>
                val msg = Feedback.noLinkFor(project)
                Task.now(state.withError(msg, ExitStatus.InvalidCommandLineOption))
            }
        }
      }
    }

    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        val linkTask = doLink(project) _
        if (cmd.watch) watch(project, state)(linkTask) else linkTask(state)
      case None => Task.now(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def run(cmd: Commands.Run, state: State, sequential: Boolean): Task[State] = {
    def doRun(project: Project)(state: State): Task[State] = {
      val cwd = project.baseDirectory
      compileAnd(cmd, state, project, false, sequential, "`run`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(state) => Task.now(state) // If we got here, we have already reported errors
          case Right(mainClass) =>
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask
                  .linkMainWithNative(cmd, project, state, mainClass, target, platform)
                  .flatMap { state =>
                    val args = (target.syntax +: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
                  }
              case platform @ Platform.Js(config, _, _) =>
                val target = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithJs(cmd, project, state, mainClass, target, platform).flatMap {
                  state =>
                    // We use node to run the program (is this a special case?)
                    val args = ("node" +: target.syntax +: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
                }
              case Platform.Jvm(javaEnv, _, _) =>
                Tasks.runJVM(state, project, javaEnv, cwd, mainClass, cmd.args.toArray)
            }
        }
      }
    }

    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        val runTask = doRun(project) _
        if (cmd.watch) watch(project, state)(runTask) else runTask(state)
      case None => Task.now(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def reportMissing(projectNames: List[String], state: State): State = {
    val projects = projectNames.mkString("'", "', '", "'")
    val configDirectory = state.build.origin.syntax
    state
      .withError(s"No projects named $projects were found in '$configDirectory'")
      .withError(s"Use the `projects` command to list all existing projects")
      .mergeStatus(ExitStatus.InvalidCommandLineOption)
  }

  private[bloop] def getMainClass(
      state: State,
      project: Project,
      cliMainClass: Option[String]
  ): Either[State, String] = {
    cliMainClass match {
      case Some(userMainClass) => Right(userMainClass)
      case None =>
        project.platform.userMainClass match {
          case Some(configMainClass) => Right(configMainClass)
          case None =>
            Tasks.findMainClasses(state, project) match {
              case Nil =>
                val msg = Feedback.missingMainClass(project)
                Left(state.withError(msg, ExitStatus.InvalidCommandLineOption))
              case List(mainClass) => Right(mainClass)
              case mainClasses =>
                val msg = Feedback
                  .expectedDefaultMainClass(project)
                  .suggest(Feedback.listMainClasses(mainClasses))
                Left(state.withError(msg, ExitStatus.InvalidCommandLineOption))
            }

        }
    }
  }
}
