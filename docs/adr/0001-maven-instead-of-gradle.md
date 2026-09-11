# ADR 0001 — Maven instead of Gradle

**Status:** accepted (M0)

Plan §3 proposed Gradle ("either works"). The build environment has Maven 3.9.10
installed and no Gradle; bootstrapping a Gradle distribution adds risk for zero
functional difference. The native-image plugin (`org.graalvm.buildtools`) is
first-class on both.

**Consequence:** `pom.xml` multi-module layout; native builds via `-Pnative`.
