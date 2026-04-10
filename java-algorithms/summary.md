# JVM Build Tools Benchmark — java-algorithms

## Compile

| Tool | Mean | Stddev | Min | Max |
|------|-----:|-------:|----:|----:|
| mill-incremental | 189.9 ms | ± 26.2 ms | 159.9 ms | 227.5 ms |
| deder-incremental | 271.6 ms | ± 47.1 ms | 216.6 ms | 335.3 ms |
| deder-clean | 4438.6 ms | ± 505.5 ms | 3981.1 ms | 5696.7 ms |
| mill-clean | 5916.8 ms | ± 258.9 ms | 5549.4 ms | 6352.3 ms |
| maven-incremental | 6581.3 ms | ± 168.9 ms | 6270.2 ms | 6764.9 ms |
| maven-clean | 6661.7 ms | ± 158.2 ms | 6334.7 ms | 6835.1 ms |
