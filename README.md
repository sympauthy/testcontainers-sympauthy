# testcontainers-sympauthy
Testcontainers module for SympAuthy, an OAuth 2.0 / OpenID Connect authorization server

## Requirements

| Requirement       | Minimum version                                                                        |
| ----------------- | -------------------------------------------------------------------------------------- |
| Java (JDK)        | 17 or newer                                                                            |
| Testcontainers    | 2.0.0 or newer (2.x line)                                                              |
| JUnit             | 5 (Jupiter) — optional; the container can also be driven with a manual lifecycle       |
| Container runtime | Docker, or a Testcontainers-supported alternative (Podman, Colima, Rancher Desktop, …) |

- **Java 17** is the module's compilation target — the lowest JVM supported by the Testcontainers
  2.x line, chosen for the widest compatibility on that line.
- **Testcontainers 2.x** is required: the 2.0 release relocated packages
  (`org.testcontainers.containers.*` → `org.testcontainers.<module>.*`) and dropped JUnit 4, so the
  module is not compatible with the 1.x line.
- A running **Docker** (or compatible) engine must be available on the machine executing the tests.
