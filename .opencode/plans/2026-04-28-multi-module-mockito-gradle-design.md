# Multi-Module Benchmarks: Mockito + Gradle

**Date:** 2026-04-28
**Status:** Approved
**Source:** https://mill-build.org/blog/1-java-compile.html

## Motivation

The current benchmark suite only covers single-module repos (java-algorithms, scala-algorithms). Real JVM projects are overwhelmingly multi-module. The blog post "How Fast Does Java Compile?" by Li Haoyi benchmarks Mockito Core and Netty Common — single-module compiles within multi-module projects — showing that build tools add 3-17x overhead over raw `javac`. This spec adds multi-module benchmarks matching that methodology, plus full-project compiles for a more complete picture.

## Scope

### Additions

| Item | Detail |
|------|--------|
| **New build tool: Gradle** | `build_tools.gradle` with install/shutdown. Uses repo's own `gradlew` wrapper. |
| **New repo: Mockito** | Replaces SLF4J. Pinned commit SHA (TBD). Java, ~100K LOC, ~15 modules. Gradle-native. |
| **New benchmark types** | `compile-single-module` and `compile-all-modules` |
| **5 tool configs for Mockito** | Gradle (native, no overlay), Maven, Mill, sbt 1.x, Deder |
| **Build-file overlays** | `build-files/mockito/{maven,mill,sbt,deder}/` |
| **ToolColors entry** | `gradle -> "#02303A"` in bench.scala |

### Removals

| Item | Detail |
|------|--------|
| **SLF4J repo** | Remove from `repos:` — does not compile cleanly |

### Deferred (not in this round)

- Gradle overlays for java-algorithms and scala-algorithms (opt-in later)
- sbt2 configuration for Mockito (add later if desired)
- Bleep (still commented out due to global caching issues)
- Test benchmarks for Mockito (compile-only focus)
- Netty, JCTools, Eclipse Collections, or other multi-module repos

## Design

### New benchmark scenario types

```yaml
hyperfine:
  benchmark_types:
    compile-single-module:   # clean-compile one module within a multi-module project
      warmup: 2
      runs: 10
    compile-all-modules:     # clean-compile all modules in a multi-module project
      warmup: 2
      runs: 10
```

These appear only in Mockito tool entries. Single-module repos keep using `compile-clean`. In the aggregated output, each (repo, scenario) combo gets its own chart.

### Gradle build_tool definition

```yaml
gradle:
  install: "chmod +x gradlew || true"
  shutdown: "./gradlew --stop || pkill -f GradleDaemon || true"
```

`gradlew` auto-downloads the correct Gradle version. The `chmod` is a safety net for CI where git clone may not preserve the executable bit.

### Mockito repo entry

```yaml
repos:
  - name: mockito
    url: https://github.com/mockito/mockito
    ref: <pinned-commit-sha>  # TBD during implementation
```

### Mockito tool entries (conceptual)

```yaml
tools:
  # ── mockito: Gradle (native) ────────────────────────
  - build_tool_name: gradle
    repo: mockito
    setup: "./gradlew tasks --no-build-cache -q || true"
    benchmarks:
      compile-single-module:
        command: "./gradlew clean :compileJava --no-build-cache -q"
      compile-all-modules:
        command: "./gradlew clean compileJava --no-build-cache -q"

  # ── mockito: Maven ─────────────────────────────────
  - build_tool_name: maven
    repo: mockito
    setup: "mvn dependency:resolve -q"
    benchmarks:
      compile-single-module:
        command: "mvn clean compile -pl . -q"
      compile-all-modules:
        command: "mvn clean compile -q"

  # ── mockito: Mill ──────────────────────────────────
  - build_tool_name: mill
    repo: mockito
    setup: "mill __.compile"
    benchmarks:
      compile-single-module:
        command: "mill clean && mill mockitocore.compile"
      compile-all-modules:
        command: "mill clean && mill __.compile"

  # ── mockito: sbt ───────────────────────────────────
  - build_tool_name: sbt
    repo: mockito
    setup: "sbt --client compile"
    benchmarks:
      compile-single-module:
        command: "sbt --client 'clean; mockitoCore/compile'"
      compile-all-modules:
        command: "sbt --client 'clean; compile'"

  # ── mockito: Deder ─────────────────────────────────
  - build_tool_name: deder
    repo: mockito
    setup: "deder exec -t compile"
    benchmarks:
      compile-single-module:
        command: "deder clean && deder exec -t compile -m mockito-core"
      compile-all-modules:
        command: "deder clean && deder exec -t compile"
```

Exact module names, flags, and Deder CLI syntax TBD during implementation.

### Build-file overlays to create

```
build-files/mockito/
├── maven/
│   └── pom.xml            (multi-module parent POM + module declarations)
├── mill/
│   └── build.mill
├── sbt/
│   ├── build.sbt
│   └── project/
│       ├── build.properties   (sbt.version=1.12.9)
│       └── plugins.sbt
└── deder/
    └── deder.pkl
```

No overlay needed for `gradle/` — Mockito is Gradle-native.

### ToolColors

In `bench.scala` `ToolColors.colors`, add:
```scala
"gradle" -> "#02303A"  // Gradle brand color
```

## Files changed

| File | Change |
|------|--------|
| `benchmarks.yaml` | +`gradle` build_tool, +`compile-single-module`/`compile-all-modules` types, +`mockito` repo, +5 tool entries, -`slf4j` repo |
| `bench.scala` | +`gradle` color in `ToolColors.colors` |
| `build-files/mockito/maven/pom.xml` | New |
| `build-files/mockito/mill/build.mill` | New |
| `build-files/mockito/sbt/build.sbt` + `project/` | New |
| `build-files/mockito/deder/deder.pkl` | New |

No changes to `run_bench.scala`, `aggregate.scala`, CI workflows, or `build-files/{java,scala}-algorithms/`. The CI matrix is dynamically generated — new entries appear automatically.

## Non-goals

- Adding Gradle overlay for java-algorithms/scala-algorithms in this round
- Test benchmarks for Mockito
- Incremental compilation benchmarks for Mockito
- Adding Netty or other additional repos
- sbt2 configuration for Mockito
- Re-enabling Bleep
