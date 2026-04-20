lazy val root = (project in file("."))
  .settings(
    organization := "com.thealgorithms",
    version      := "0.0.1-SNAPSHOT",
    // Pure Java project — no Scala library on the classpath
    crossPaths         := false,
    autoScalaLibrary   := false,
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-lang3"        % "3.20.0",
      "org.apache.commons" % "commons-collections4" % "4.5.0",
      // JUnit 5 adapter for sbt — registers the test framework automatically
      "com.github.sbt.junit" % "jupiter-interface" % "0.16.0" % Test,
      "org.junit.jupiter"    % "junit-jupiter"     % "5.14.0" % Test,
      "org.assertj"          % "assertj-core"      % "3.27.6" % Test,
      "org.mockito"          % "mockito-core"      % "5.20.0" % Test,
    ),
    // Fork the JVM for tests — required for JUnit 5 class-loader isolation
    Test / fork := true,
  )
