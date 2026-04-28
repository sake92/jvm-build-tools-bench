// Multi-module sbt build for Mockito v5.23.0
// Only compile benchmarks — no test configuration needed

ThisBuild / organization := "org.mockito"
ThisBuild / version      := "5.23.0"
ThisBuild / scalaVersion := "2.12.20"

// ── mockito-core ──────────────────────────────────────────
lazy val mockitoCore = project
  .in(file("mockito-core"))
  .settings(
    crossPaths       := false,
    autoScalaLibrary := false,
    javacOptions ++= Seq("-source", "11", "-target", "11"),
    libraryDependencies ++= Seq(
      "net.bytebuddy" % "byte-buddy"       % "1.17.7",
      "net.bytebuddy" % "byte-buddy-agent" % "1.17.7",
      "org.objenesis" % "objenesis"        % "3.3",
    ),
  )

// ── mockito-junit-jupiter ────────────────────────────────
lazy val mockitoJunitJupiter = project
  .in(file("mockito-extensions/mockito-junit-jupiter"))
  .dependsOn(mockitoCore)
  .settings(
    crossPaths       := false,
    autoScalaLibrary := false,
    javacOptions ++= Seq("-source", "11", "-target", "11"),
    libraryDependencies ++= Seq(
      "org.junit.jupiter" % "junit-jupiter-api" % "5.13.4",
    ),
  )

// ── mockito-proxy ────────────────────────────────────────
lazy val mockitoProxy = project
  .in(file("mockito-extensions/mockito-proxy"))
  .dependsOn(mockitoCore)
  .settings(
    crossPaths       := false,
    autoScalaLibrary := false,
    javacOptions ++= Seq("-source", "11", "-target", "11"),
  )

// ── mockito-subclass ─────────────────────────────────────
lazy val mockitoSubclass = project
  .in(file("mockito-extensions/mockito-subclass"))
  .dependsOn(mockitoCore)
  .settings(
    crossPaths       := false,
    autoScalaLibrary := false,
    javacOptions ++= Seq("-source", "11", "-target", "11"),
  )

// Aggregate all modules so `compile` at root compiles everything
lazy val root = project
  .in(file("."))
  .aggregate(mockitoCore, mockitoJunitJupiter, mockitoProxy, mockitoSubclass)
  .settings(
    crossPaths       := false,
    autoScalaLibrary := false,
  )
