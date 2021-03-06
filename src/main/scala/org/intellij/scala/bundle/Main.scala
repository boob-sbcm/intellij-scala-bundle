package org.intellij.scala.bundle

import java.io.File
import java.net.URL

import org.intellij.scala.bundle.Descriptor._
import org.intellij.scala.bundle.Mapper.{matches, _}

import scala.Function._

/**
  * @author Pavel Fatin
  */
object Main {
  private val Version = "2018-09-14"

  def main(args: Array[String]): Unit = {
    val target = file("./target")

    val repository = target / "repository"

    repository.mkdir()

    Components.All.filter(_.downloadable).par.foreach { component =>
      downloadComponent(repository, component)
    }

    val commands = Seq(
      () => build(repository, Components.All, Descriptors.Windows)(target / s"intellij-scala-bundle-$Version-windows.zip"),
      () => build(repository, Components.All, Descriptors.Linux)(target / s"intellij-scala-bundle-$Version-linux.tar.gz"),
      () => build(repository, Components.All, Descriptors.Mac)(target / s"intellij-scala-bundle-$Version-osx.tar.gz"))

    commands.par.foreach(_.apply())

    info(s"Done.")
  }

  private object Versions {
    val Idea = "182.4323.46"
    val IdeaWindows = "2018.2.3" // for idea.exe only
    val ScalaPlugin = "2018.2.11"
    val Sdk = "8u152b1248.8"
    val Scala = "2.12.6"
  }

  private object Components {
    object Idea {
      val Bundle = Component(s"https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC/${Versions.Idea}/ideaIC-${Versions.Idea}.zip")
      val Windows = Component(s"https://download.jetbrains.com/idea/ideaIC-${Versions.IdeaWindows}.win.zip")
      val ScalaPlugin = Component(s"https://plugins.jetbrains.com/files/1347/49539/scala-intellij-bin-${Versions.ScalaPlugin}.zip")
      val Resources = Component("../../src/main/resources")
    }

    object Sdk {
      val Windows = Component(s"https://bintray.com/jetbrains/intellij-jdk/download_file?file_path=jbsdk${Versions.Sdk}_windows_x64.tar.gz")
      val Linux = Component(s"https://bintray.com/jetbrains/intellij-jdk/download_file?file_path=jbsdk${Versions.Sdk}_linux_x64.tar.gz")
      val Mac = Component(s"https://bintray.com/jetbrains/intellij-jdk/download_file?file_path=jbsdk${Versions.Sdk}_osx_x64.tar.gz")
    }

    object Scala {
      val Windows = Component(s"https://downloads.lightbend.com/scala/${Versions.Scala}/scala-${Versions.Scala}.zip")
      val Unix = Component(s"https://downloads.lightbend.com/scala/${Versions.Scala}/scala-${Versions.Scala}.tgz")
      val Sources = Component(s"https://github.com/scala/scala/archive/v${Versions.Scala}.tar.gz")
    }

    val Repository = Component("./")

    val All = Seq(
      Idea.Bundle, Idea.Windows, Idea.ScalaPlugin, Idea.Resources,
      Sdk.Windows, Sdk.Linux, Sdk.Mac,
      Scala.Windows, Scala.Unix, Scala.Sources,
      Repository
    )
  }

  private object Descriptors {
    import Components._

    private val Common: Descriptor = {
      case Idea.Bundle =>
        matches("bin/appletviewer\\.policy") |
          matches("bin/log\\.xml") |
          matches("lib/platform-impl\\.jar") & repack("lib/platform-impl.jar", 0) { (source, destination) =>
            source.collect(-(matches("com/intellij/ui/AppUIUtil.class") | matches("com/intellij/idea/StartupUtil.class"))).foreach(destination(_))
            using(Source(file("./src/main/resources")))(_.collect(
              matches("AppUIUtil.class") & to("com/intellij/ui/") |
              matches("StartupUtil.class") & to("com/intellij/idea/") |
                matches("BundleAgreement.html") & to("com/intellij/ui/")).foreach(destination(_)))
          } |
          matches("lib/.*") - matches("lib/libpty.*") - matches("lib/platform-impl.jar") |
          matches("license/.*") |
          matches("plugins/(git4idea|github|junit|IntelliLang|maven|properties|terminal)/.*") |
          matches("build.txt") |
          matches("LICENSE.txt") |
          matches("NOTICE.txt")
      case Idea.ScalaPlugin =>
        to("data/plugins/")
      case Repository =>
        matches(Scala.Sources.path) & repack("scala-library.zip", 9, from(s"scala-${Versions.Scala}/src/library/")) & to("scala/src/")
      case Idea.Resources =>
        matches("data/.*") |
          from("BundleAgreement.html") & to("README.html")
    }

    private val WindowsSpecific: Descriptor = {
      case Idea.Bundle =>
        from("bin/win/") & to("bin/") |
          matches("bin/.*\\.(bat|dll|exe|ico)") |
          matches("lib/libpty/win/.*")
      case Idea.Windows =>
        matches("bin/idea64.exe")
      case Sdk.Windows =>
        to("jre/")
      case Scala.Windows =>
        from(s"scala-${Versions.Scala}/") & to("scala/")
      case Idea.Resources =>
        matches("IDEA.lnk")
    }

    private val LinuxSpecific: Descriptor = {
      case Idea.Bundle =>
        from("bin/linux/") & to("bin/") |
          matches("bin/.*\\.(py|sh|png)") | matches("bin/fsnotifier") |
          matches("lib/libpty/linux/.*")
      case Sdk.Linux =>
        to("jre/")
      case Scala.Unix =>
        from(s"scala-${Versions.Scala}/") & to("scala/")
      case Idea.Resources =>
        matches("idea.sh") & edit(_.replaceAll("\r", ""))
    }

    private val MacSpecific: Descriptor = {
      case Idea.Bundle =>
        from("bin/mac/") & to("bin/") |
          matches("bin/.*\\.(py|sh|dylib)") - matches("bin/idea\\.sh") - matches("bin/restart\\.py") |
          matches("bin/fsnotifier") |
          matches("bin/restarter") |
          matches("MacOS/.*") |
          matches("Resources/.*") |
          matches("Info\\.plist") |
          matches("lib/libpty/macosx/.*")
      case Sdk.Mac =>
        any
      case Scala.Unix =>
        from(s"scala-${Versions.Scala}/") & to("scala/")
    }

    private def Patches(separator: String): Descriptor = {
      case Idea.Bundle =>
        matches("bin/idea\\.properties") & edit(_ + IdeaPropertiesPatch.replaceAll("\n", separator)) |
          matches("build.txt") & edit(const(BuildTxtPatch.replaceAll("\n", separator))) |
          any
      case _ => any
    }

    private def Repack: Descriptor = {
//      case _ =>
//        matches(".*\\.(jar|zip)") & repack(any) |
//          any
      case _ => any
    }

    private val IdeaPropertiesPatch: String = "\n" +
      "idea.config.path=${idea.home.path}/data/config\n\n" +
      "idea.system.path=${idea.home.path}/data/system\n\n" +
      "idea.plugins.path=${idea.home.path}/data/plugins\n      "

    private def BuildTxtPatch =
      s"IntelliJ Scala Bundle $Version:\n\n" +
        s"* IntelliJ IDEA ${Versions.Idea}\n" +
        s"* Scala Plugin ${Versions.ScalaPlugin}\n" +
        s"* JetBrains SDK ${Versions.Sdk}\n" +
        s"* Scala ${Versions.Scala}\n\n" +
        s"See https://github.com/JetBrains/intellij-scala-bundle for more info."

    private val MacPatches: Descriptor = {
      case Idea.Resources =>
        matches("data/config/options/jdk\\.table\\.xml") &
          edit(_.replaceAll("\\$APPLICATION_HOME_DIR\\$\\/jre", "\\$APPLICATION_HOME_DIR\\$/jdk/Contents/Home")) | any
      case _ => any
    }

    private val Permissions: Descriptor = {
      case _ =>
        (matches("bin/.*\\.(sh|py)") |
          matches("bin/fsnotifier(|-arm|64)") |
          matches("bin/restarter") |
          matches("MacOS/idea") |
          matches("idea.sh")) & setMode(100755) | any
    }

    val Windows: Descriptor = ((Common | WindowsSpecific) & Repack & Patches("\r\n")).andThen(_ & to(s"intellij-scala-bundle-$Version/"))

    val Linux: Descriptor  = ((Common | LinuxSpecific) & Repack & Patches("\n") & Permissions).andThen(_ & to(s"intellij-scala-bundle-$Version/"))

    val Mac: Descriptor = ((Common | MacSpecific) & Repack & Patches("\n") & MacPatches & Permissions).andThen(_ & to(s"intellij-scala-bundle-$Version.app/Contents/"))
  }

  private def build(base: File, components: Seq[Component], descriptor: Descriptor)(output: File) {
    info(s"Building ${output.getName}...")

    using(Destination(output)) { destination =>
      components.foreach { component =>
        descriptor.lift(component).foreach { mapper =>
          using(Source(base / component.path)) { source =>
            source.collect(mapper).foreach(destination(_))
          }
        }
      }
    }
  }

  private def downloadComponent(base: File, component: Component): Unit = {
    val destination = base / component.path

    if (!destination.exists) {
      info(s"Downloading ${component.path}...")
      download(new URL(component.location), destination)
      if (!destination.exists) {
        error(s"Error downloading ${component.location}")
        sys.exit(-1)
      }
    }
  }
}
