ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "org.typelevel"
ThisBuild / version      := "0.0.0-BENCH"

val scalaCheckVersion      = "1.19.0"
val disciplineVersion      = "1.7.0"
val disciplineMunitVersion = "2.0.0"
val munitVersion           = "1.3.0"

val commonSettings = Seq(
  Test / fork := true,
  Test / javaOptions := Seq("-Xmx3G"),
  Compile / doc / sources := Nil,
  publish / skip := true,
  scalacOptions ++= Seq("-Ykind-projector", "-source:3.0-migration")
)

val testingDeps = libraryDependencies ++= Seq(
  "org.scalameta" %% "munit"             % munitVersion           % Test,
  "org.typelevel" %% "discipline-munit"  % disciplineMunitVersion % Test
)

val disciplineDeps =
  libraryDependencies += "org.typelevel" %% "discipline-core" % disciplineVersion

// injected globally by sbt-typelevel; added here because we stripped that plugin
ThisBuild / libraryDependencies += "org.typelevel" %% "scalac-compat-annotation" % "0.1.4"

// cats convention: `scala-2.13+` holds sources shared by 2.13 AND Scala 3.
// sbt's default convention only picks `scala` + `scala-<binaryVersion>`, so we
// add this manually for every module (Pure + Full cross layouts).
val pureCrossSrc = Seq(
  Compile / unmanagedSourceDirectories ++= Seq(
    baseDirectory.value / "src" / "main" / "scala-2.13+"
  )
)

val fullCrossSrc = Seq(
  Compile / unmanagedSourceDirectories ++= Seq(
    baseDirectory.value / "shared" / "src" / "main" / "scala",
    baseDirectory.value / "shared" / "src" / "main" / "scala-2.13+",
    baseDirectory.value / "jvm"    / "src" / "main" / "scala",
    baseDirectory.value / "jvm"    / "src" / "main" / "scala-2.13+"
  )
)

lazy val kernel = project.in(file("kernel"))
  .settings(commonSettings, testingDeps, pureCrossSrc)
  .settings(
    moduleName := "cats-kernel",
    Compile / sourceGenerators +=
      (Compile / sourceManaged).map(KernelBoiler.gen).taskValue
  )

lazy val kernelLaws = project.in(file("kernel-laws"))
  .dependsOn(kernel)
  .settings(commonSettings, disciplineDeps, testingDeps, fullCrossSrc)
  .settings(moduleName := "cats-kernel-laws")

lazy val algebra = project.in(file("algebra-core"))
  .dependsOn(kernel)
  .settings(commonSettings, testingDeps)
  .settings(
    moduleName := "algebra",
    libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    Compile / sourceGenerators +=
      (Compile / sourceManaged).map(AlgebraBoilerplate.gen).taskValue
  )

lazy val algebraLaws = project.in(file("algebra-laws"))
  .dependsOn(kernelLaws, algebra)
  .settings(commonSettings, disciplineDeps, testingDeps, fullCrossSrc)
  .settings(moduleName := "algebra-laws")

lazy val core = project.in(file("core"))
  .dependsOn(kernel)
  .settings(commonSettings, testingDeps, pureCrossSrc)
  .settings(
    moduleName := "cats-core",
    libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    Compile / sourceGenerators +=
      (Compile / sourceManaged).map(Boilerplate.gen).taskValue
  )

lazy val laws = project.in(file("laws"))
  .dependsOn(kernel, core, kernelLaws)
  .settings(commonSettings, disciplineDeps, testingDeps, pureCrossSrc)
  .settings(moduleName := "cats-laws")

lazy val free = project.in(file("free"))
  .dependsOn(core)
  .settings(commonSettings, pureCrossSrc)
  .settings(moduleName := "cats-free")

lazy val testkit = project.in(file("testkit"))
  .dependsOn(core, laws)
  .settings(commonSettings, disciplineDeps)
  .settings(moduleName := "cats-testkit")

lazy val alleycatsCore = project.in(file("alleycats-core"))
  .dependsOn(core)
  .settings(commonSettings, pureCrossSrc)
  .settings(moduleName := "alleycats-core")

lazy val alleycatsLaws = project.in(file("alleycats-laws"))
  .dependsOn(alleycatsCore, laws)
  .settings(commonSettings, disciplineDeps, testingDeps, fullCrossSrc)
  .settings(moduleName := "alleycats-laws")

// `tests` module is test-only (no main sources) and depends on sbt-typelevel
// NoPublishPlugin; drop it to keep `compile` at the aggregate clean.

lazy val root = project.in(file("."))
  .aggregate(
    kernel, kernelLaws, algebra, algebraLaws,
    core, laws, free, testkit,
    alleycatsCore, alleycatsLaws
  )
  .settings(commonSettings)
  .settings(name := "cats-root", publish / skip := true)
