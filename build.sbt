// import Project.Initialize
import Util._
import Dependencies._
// import StringUtilities.normalize

def internalPath   = file("internal")

// ThisBuild settings take lower precedence,
// but can be shared across the multi projects.
def buildLevelSettings: Seq[Setting[_]] = Seq(
  organization in ThisBuild := "org.scala-sbt",
  version in ThisBuild := "0.1.0-SNAPSHOT"
  // bintrayOrganization in ThisBuild := Some("sbt"),
  // // bintrayRepository in ThisBuild := s"ivy-${(publishStatus in ThisBuild).value}",
  // bintrayPackage in ThisBuild := "sbt",
  // bintrayReleaseOnPublish in ThisBuild := false
)

def commonSettings: Seq[Setting[_]] = Seq(
  scalaVersion := "2.10.5",
  // publishArtifact in packageDoc := false,
  resolvers += Resolver.typesafeIvyRepo("releases"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.bintrayRepo("sbt", "maven-releases"),
  resolvers += Resolver.url("bintray-sbt-ivy-snapshots", new URL("https://dl.bintray.com/sbt/ivy-snapshots/"))(Resolver.ivyStylePatterns),
  // concurrentRestrictions in Global += Util.testExclusiveRestriction,
  testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-w", "1"),
  javacOptions in compile ++= Seq("-target", "6", "-source", "6", "-Xlint", "-Xlint:-serial"),
  incOptions := incOptions.value.withNameHashing(true),
  crossScalaVersions := Seq(scala210, scala211),
  scalacOptions ++= Seq(
    "-encoding", "utf8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-Xfuture",
    "-Yinline-warnings",
    "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard")
  // bintrayPackage := (bintrayPackage in ThisBuild).value,
  // bintrayRepository := (bintrayRepository in ThisBuild).value
)

def minimalSettings: Seq[Setting[_]] = commonSettings

// def minimalSettings: Seq[Setting[_]] =
//   commonSettings ++ customCommands ++
//   publishPomSettings ++ Release.javaVersionCheckSettings

def baseSettings: Seq[Setting[_]] =
  minimalSettings
//   minimalSettings ++ baseScalacOptions ++ Licensed.settings ++ Formatting.settings

def testedBaseSettings: Seq[Setting[_]] =
  baseSettings ++ testDependencies

lazy val compileRoot: Project = (project in file(".")).
  // configs(Sxr.sxrConf).
  aggregate(interfaceProj,
    apiProj,
    classpathProj,
    classfileProj,
    compileInterfaceProj,
    compileIncrementalProj,
    compilePersistProj,
    compilerProj,
    compilerIntegrationProj,
    compilerIvyProj).
  settings(
    buildLevelSettings,
    minimalSettings,
    // rootSettings,
    publish := {},
    publishLocal := {}
  )

/* ** subproject declarations ** */

// defines Java structures used across Scala versions, such as the API structures and relationships extracted by
//   the analysis compiler phases and passed back to sbt.  The API structures are defined in a simple
//   format from which Java sources are generated by the sbt-datatype plugin.
lazy val interfaceProj = (project in file("interface")).
  settings(
    minimalSettings,
    // javaOnlySettings,
    name := "Interface",
    exportJars := true,
    watchSources <++= apiDefinitions,
    resourceGenerators in Compile <+= (version, resourceManaged, streams, compile in Compile) map generateVersionFile,
    apiDefinitions <<= baseDirectory map { base => (base / "definition") :: (base / "other") :: (base / "type") :: Nil },
    crossPaths := false
  )

// defines operations on the API of a source, including determining whether it has changed and converting it to a string
//   and discovery of Projclasses and annotations
lazy val apiProj = (project in internalPath / "apiinfo").
  dependsOn(interfaceProj, classfileProj).
  settings(
    testedBaseSettings,
    name := "API"
  )

/* **** Utilities **** */

// Utilities related to reflection, managing Scala versions, and custom class loaders
lazy val classpathProj = (project in internalPath / "classpath").
  dependsOn(interfaceProj).
  settings(
    testedBaseSettings,
    name := "Classpath",
    libraryDependencies ++= Seq(scalaCompiler.value,
      Dependencies.launcherInterface,
      sbtIO)
  )

// class file reader and analyzer
lazy val classfileProj = (project in internalPath / "classfile").
  dependsOn(interfaceProj).
  settings(
    testedBaseSettings,
    libraryDependencies ++= Seq(sbtIO, utilLogging),
    name := "Classfile"
  )

/* **** Intermediate-level Modules **** */

// Compiler-side interface to compiler that is compiled against the compiler being used either in advance or on the fly.
//   Includes API and Analyzer phases that extract source API and relationships.
lazy val compileInterfaceProj = (project in internalPath / "compile-bridge").
  dependsOn(interfaceProj % "compile;test->test", /*launchProj % "test->test",*/ apiProj % "test->test").
  settings(
    baseSettings,
    libraryDependencies += scalaCompiler.value,
    // precompiledSettings,
    name := "Compiler Interface",
    exportJars := true,
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    libraryDependencies ++= Seq(sbtIO, utilLogging),
    scalaSource in Compile := {
      scalaVersion.value match {
        case v if v startsWith "2.11" => baseDirectory.value / "src" / "main" / "scala"
        case _                        => baseDirectory.value / "src-2.10" / "main" / "scala"
      }
    },
    scalacOptions := {
      scalaVersion.value match {
        case v if v startsWith "2.11" => scalacOptions.value
        case _                        => scalacOptions.value filterNot (Set("-Xfatal-warnings", "-deprecation") contains _)
      }
    }
  )

// Implements the core functionality of detecting and propagating changes incrementally.
//   Defines the data structures for representing file fingerprints and relationships and the overall source analysis
lazy val compileIncrementalProj = (project in internalPath / "compile-inc").
  dependsOn (apiProj, classpathProj, compileInterfaceProj % Test).
  settings(
    testedBaseSettings,
    libraryDependencies ++= Seq(sbtIO, utilLogging, utilRelation),
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    name := "Incremental Compiler"
  )

// Persists the incremental data structures using SBinary
lazy val compilePersistProj = (project in internalPath / "compile-persist").
  dependsOn(compileIncrementalProj, apiProj, compileIncrementalProj % "test->test").
  settings(
    testedBaseSettings,
    name := "Persist",
    libraryDependencies += sbinary
  )

// sbt-side interface to compiler.  Calls compiler-side interface reflectively
lazy val compilerProj = (project in file("compile")).
  dependsOn(interfaceProj % "compile;test->test", classpathProj, apiProj, classfileProj).
  settings(
    testedBaseSettings,
    name := "Compile",
    libraryDependencies ++= Seq(scalaCompiler.value % Test, launcherInterface,
      utilLogging, sbtIO, utilLogging % "test" classifier "tests", utilControl),
    unmanagedJars in Test <<= (packageSrc in compileInterfaceProj in Compile).map(x => Seq(x).classpath)
  )

lazy val compilerIntegrationProj = (project in (internalPath / "compile-integration")).
  dependsOn(compileIncrementalProj, compilerProj, compilePersistProj, apiProj, classfileProj).
  settings(
    baseSettings,
    name := "Compiler Integration"
  )

lazy val compilerIvyProj = (project in internalPath / "compile-ivy").
  dependsOn (compilerProj).
  settings(
    baseSettings,
    libraryDependencies += libraryManagement,
    name := "Compiler Ivy Integration"
  )
