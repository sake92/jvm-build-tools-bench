# JVM Build Tools Benchmark — java-algorithms

## Compile

| Tool | Mean | Stddev | Min | Max |
|------|-----:|-------:|----:|----:|
| deder-incremental | 65.6 ms | ± 6.0 ms | 61.0 ms | 78.1 ms |
| mill-incremental | 188.6 ms | ± 29.5 ms | 132.8 ms | 220.6 ms |
| deder-clean | 4264.7 ms | ± 342.2 ms | 3931.1 ms | 4839.2 ms |
| mill-clean | 5729.1 ms | ± 262.6 ms | 5452.1 ms | 6222.1 ms |
| maven-incremental | 6531.2 ms | ± 193.1 ms | 6262.6 ms | 6862.2 ms |
| maven-clean | 6596.8 ms | ± 142.8 ms | 6326.4 ms | 6800.5 ms |
