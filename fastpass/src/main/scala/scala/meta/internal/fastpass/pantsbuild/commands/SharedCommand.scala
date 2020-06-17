package scala.meta.internal.fastpass.pantsbuild.commands

import scala.sys.process._
import scala.util.Try
import scala.concurrent.ExecutionContext
import scala.meta.internal.fastpass.Time
import scala.meta.internal.fastpass.Timer
import scala.meta.internal.fastpass.FastpassEnrichments._
import scala.util.Failure
import scala.util.Success
import scala.meta.internal.fastpass.pantsbuild.Export
import scala.meta.internal.fastpass.pantsbuild.BloopPants
import scala.meta.internal.fastpass.pantsbuild.MessageOnlyException
import scala.meta.internal.fastpass.pantsbuild.IntelliJ
import metaconfig.cli.CliApp
import metaconfig.internal.Levenshtein
import scala.meta.internal.fastpass.LogMessages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import bloop.launcher.LauncherMain
import java.nio.charset.StandardCharsets
import bloop.bloopgun.core.Shell
import scala.concurrent.Promise
import scala.meta.internal.fastpass.BuildInfo
import java.nio.file.Files
import scala.meta.io.AbsolutePath
import scala.meta.internal.fastpass.pantsbuild.PantsConfiguration
import scala.util.control.NonFatal
import java.nio.file.Path
import java.nio.file.Paths

object SharedCommand {
  def interpretExport(export: Export): Int = {
    if (!export.pants.isFile) {
      export.app.error(
        s"no Pants build detected, file '${export.pants}' does not exist. " +
          s"To fix this problem, change the working directory to the root of a Pants build."
      )
      1
    } else {
      val workspace = export.workspace
      val timer = new Timer(Time.system)
      val installResult =
        if (export.open.intellij) {
          // there is no need to export bloop projects before starting intellij
          // as it will request buildTargets bsp endpoint that will call fastpass refresh
          Success(None)
        } else {
          BloopPants.bloopInstall(export)(ExecutionContext.global).map(Some(_))
        }
      installResult match {
        case Failure(exception) =>
          exception match {
            case MessageOnlyException(message) =>
              export.app.error(message)
            case _ =>
              export.app.error(s"fastpass failed to run")
              exception.printStackTrace(export.app.out)
          }
          1
        case Success(exportResult) =>
          IntelliJ.writeBsp(
            export.project,
            export.common,
            export.export.coursierBinary,
            exportResult
          )
          exportResult.foreach { result =>
            val targets =
              LogMessages.pluralName("Pants target", result.exportedTargets)
            export.app.info(
              s"exported ${targets} to project '${export.project.name}' in $timer"
            )
          }
          SwitchCommand.runSymlinkOrWarn(
            export.project,
            export.common,
            export.app,
            isStrict = false
          )
          symlinkProjectViewRoots(export.project)
          if (export.export.canBloopExit) {
            restartBloopIfNewSettings(AbsolutePath(workspace))
          }
          if (export.open.isEmpty) {
            OpenCommand.onEmpty(export.project, export.app)
          } else {
            OpenCommand.run(
              export.open.withProject(export.project).withWorkspace(workspace),
              export.app
            )
          }
          0
      }
    }
  }

  def restartBloopIfNewSettings(workspace: AbsolutePath): Unit = {
    val isUpdatedBloopSettings =
      BloopGlobalSettings.update(workspace, defaultJavaHomePath)
    if (isUpdatedBloopSettings) {
      restartBloopServer()
    } else {
      restartOldBloopServer()
    }
  }

  def symlinkProjectViewRoots(project: Project): Unit = {
    try {
      val workspace = AbsolutePath(project.common.workspace)
      deleteSymlinkDirectories(project.bspRoot)
      val projectViewRoots = PantsConfiguration
        .sourceRoots(workspace, project.targets)
        .map(_.toNIO)
      projectViewRoots.foreach { root =>
        val link = project.bspRoot.toNIO.resolve(root.getFileName())
        if (!Files.exists(link)) {
          Files.createSymbolicLink(link, root)
        }
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(e)
    }
  }

  private def deleteSymlinkDirectories(dir: AbsolutePath): Unit = {
    dir.list
      .map(_.toNIO)
      .filter(path =>
        Files.isSymbolicLink(path) &&
          Files.isDirectory(Files.readSymbolicLink(path))
      )
      .foreach { symlink => Files.deleteIfExists(symlink) }
  }
  def withOneProject(
      action: String,
      projects: List[String],
      common: SharedOptions,
      app: CliApp
  )(fn: Project => Int): Int =
    projects match {
      case Nil =>
        app.error(s"no projects to $action")
        1
      case name :: Nil =>
        Project.fromName(name, common) match {
          case Some(project) =>
            fn(project)
          case None =>
            SharedCommand.noSuchProject(name, app, common)
        }
      case projects =>
        app.error(
          s"expected 1 project to $action but received ${projects.length} arguments '${projects.mkString(" ")}'"
        )
        1
    }

  def noSuchProject(name: String, app: CliApp, common: SharedOptions): Int = {
    val candidates = Project.names(common)
    val closest = Levenshtein.closestCandidate(name, candidates)
    val didYouMean = closest match {
      case Some(candidate) => s"\n\tDid you mean '$candidate'?"
      case None => ""
    }
    app.error(s"project '$name' does not exist$didYouMean")
    1
  }

  def complete(
      context: TabCompletionContext,
      allowsMultipleProjects: Boolean = false
  ): List[TabCompletionItem] = {
    Project
      .fromCommon(SharedOptions())
      .map(project => TabCompletionItem(project.name))
  }

  /** Upgrades the Bloop server if it's known to be an old version. */
  private def restartOldBloopServer(): Unit = {
    val isOutdated = Set[String](
      "1.4.0-RC1-235-3231567a", "1.4.0-RC1-190-ef7d8dba",
      "1.4.0-RC1-167-61fbbe08", "1.4.0-RC1-69-693de22a",
      "1.4.0-RC1+33-dfd03f53", "1.4.0-RC1"
    )
    Try {
      val version = List("bloop", "--version").!!.linesIterator
        .find(_.startsWith("bloop v"))
        .getOrElse("")
        .stripPrefix("bloop v")
      if (isOutdated(version)) {
        scribe.info(s"shutting down old version of Bloop '$version'")
        restartBloopServer()
      }
    }
  }

  private def restartBloopServer(): Unit = {
    List("bloop", "exit").!
    new LauncherMain(
      clientIn = System.in,
      clientOut = System.out,
      out = System.out,
      charset = StandardCharsets.UTF_8,
      shell = Shell.default,
      userNailgunHost = None,
      userNailgunPort = None,
      startedServer = Promise[Unit]()
    ).runLauncher(
      bloopVersionToInstall = BuildInfo.bloopVersion,
      skipBspConnection = true,
      serverJvmOptions = Nil
    )
  }

  private def defaultJavaHomePath: Option[Path] = {
    defaultJavaHome.map(Paths.get(_))
  }

  private def defaultJavaHome: Option[String] = {
    Option(System.getenv("JAVA_HOME")).orElse(
      Option(System.getProperty("java.home"))
    )
  }

  def runScalafmtSymlink(
      project: Project,
      common: SharedOptions
  ): Unit = {
    val workspace = common.workspace
    val out = project.root.bspRoot.toNIO
    val inScalafmt = {
      var link = workspace.resolve(".scalafmt.conf")
      // Configuration file may be symbolic link.
      while (Files.isSymbolicLink(link)) {
        link = Files.readSymbolicLink(link)
      }
      // Symbolic link may be relative to workspace directory.
      if (link.isAbsolute()) link
      else workspace.resolve(link)
    }
    val outScalafmt = out.resolve(".scalafmt.conf")
    if (
      !out.startsWith(workspace) &&
      Files.exists(inScalafmt) && {
        !Files.exists(outScalafmt) ||
        Files.isSymbolicLink(outScalafmt)
      }
    ) {
      Files.deleteIfExists(outScalafmt)
      Files.createDirectories(outScalafmt.getParent())
      Files.createSymbolicLink(outScalafmt, inScalafmt)
    }
  }

}