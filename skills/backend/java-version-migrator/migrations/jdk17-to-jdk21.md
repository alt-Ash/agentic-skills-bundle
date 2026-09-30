# JDK 17 → 21 Migration Guide

> Reference: https://docs.oracle.com/en/java/javase/21/migrate/
> Release notes: https://openjdk.org/projects/jdk/21/

## Contents

- Breaking changes
- OpenRewrite recipes
- Deprecated APIs to migrate
- Infrastructure and tooling updates
- Verification
- Notable JDK 21 additions
- References

---

## Breaking changes

**Honest framing, unlike a Node.js major-version hop:** the JDK's backward-compatibility bar is deliberately much stronger than Node's. A same-vendor 17→21 LTS-to-LTS hop carries very few genuine source-breaking changes for typical application code. Most of what changes in this range is *additive* (new language features, new APIs) rather than *removed*. Do not assume this table is sparse because it's incomplete — it is sparse because the JDK actually is this stable across this hop. Verify against the official release notes above before assuming a specific API in your codebase is affected.

| Area | Change | Action |
|---|---|---|
| Security Manager | Deprecated for removal since JDK 17 ([JEP 411](https://openjdk.org/jeps/411)); still present and functional through 21, not yet removed | If the project still enables a `SecurityManager`, plan its removal — do not add new reliance on it |
| Dependency toolchain: Lombok, bytecode-manipulation libraries (ByteBuddy, older Mockito/CGLIB versions) | Some older releases of these tools lag behind current JDK bytecode versions | Confirm the project's Lombok/Mockito/ByteBuddy versions explicitly support JDK 21 class-file format before upgrading — bump them first if not |
| Reflective access to JDK internals | Continued strong encapsulation of internal APIs (ongoing since JDK 9, tightened incrementally each release) | If the project or a dependency uses `--add-opens`/`--add-exports` flags, re-verify they're still required and correctly scoped on 21 |

---

## OpenRewrite recipes

Use [OpenRewrite](https://docs.openrewrite.org/) for automated, AST-based migration — the Java ecosystem's equivalent of the Node `codemod` tooling referenced elsewhere in this skill family.

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.migrate.UpgradeToJava21
```

Or add the recipe to `pom.xml` under the `rewrite-maven-plugin` configuration for a repeatable, committed migration step rather than a one-off CLI invocation.

Grep for any remaining direct dependence on Security Manager APIs after running the recipe:

```bash
grep -rn "SecurityManager\|System\.getSecurityManager\|checkPermission" --include="*.java" .
```

---

## Deprecated APIs to migrate

### Security Manager usage (deprecated for removal, not yet removed)

```java
// Still works on 21, but plan migration away from it
System.setSecurityManager(new SecurityManager());
```

There is no drop-in replacement — Security Manager's access-control model is being retired without a direct successor. If the project relies on it for sandboxing untrusted code, this needs a design conversation, not a mechanical fix. Flag as a planning item rather than attempting an automated fix.

### Reflective internal-API access

If `--add-opens`/`--add-exports` JVM flags are present in the project's run/test configuration, re-confirm each one is still necessary on 21 — some may have become unnecessary as libraries updated their own internal-API usage.

---

## Infrastructure and tooling updates

### `pom.xml` (Maven)

```xml
<!-- Before -->
<properties>
  <java.version>17</java.version>
  <maven.compiler.release>17</maven.compiler.release>
</properties>

<!-- After -->
<properties>
  <java.version>21</java.version>
  <maven.compiler.release>21</maven.compiler.release>
</properties>
```

### `build.gradle` (Gradle)

```groovy
// Before
java {
    sourceCompatibility = JavaVersion.VERSION_17
}

// After
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

### `.sdkmanrc` (if using SDKMAN for local JDK version pinning)

```
java=21-tem
```

### Dockerfile

```dockerfile
# Before
FROM eclipse-temurin:17-jre

# After
FROM eclipse-temurin:21-jre
```

### CI/CD (GitHub Actions example)

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: 'temurin'
    java-version: '21'
```

---

## Verification

```bash
mvn clean install
mvn test
```

(Or the Gradle equivalents: `./gradlew clean build`, `./gradlew test`.)

---

## Notable JDK 21 additions (no action required, but worth adopting)

- **Virtual threads** ([JEP 444](https://openjdk.org/jeps/444), finalized) — lightweight threads for high-throughput concurrent applications; consider for I/O-bound Spring MVC/WebFlux workloads instead of platform-thread pools, but profile before switching production code.
- **Sequenced Collections** ([JEP 431](https://openjdk.org/jeps/431)) — new `SequencedCollection`/`SequencedSet`/`SequencedMap` interfaces adding consistent `getFirst()`/`getLast()`/`reversed()` methods across ordered collection types.
- **Record Patterns** ([JEP 440](https://openjdk.org/jeps/440), finalized) and **Pattern Matching for `switch`** ([JEP 441](https://openjdk.org/jeps/441), finalized) — enables more expressive, exhaustive `switch` expressions over sealed types and record deconstruction.
- **Generational ZGC** ([JEP 439](https://openjdk.org/jeps/439)) — can be enabled as a garbage collector option; evaluate if the application has latency-sensitive GC pauses.

---

## References

- [Oracle JDK 21 Migration Guide](https://docs.oracle.com/en/java/javase/21/migrate/)
- [OpenJDK 21 project page](https://openjdk.org/projects/jdk/21/)
- [OpenRewrite migration recipes](https://docs.openrewrite.org/recipes/java/migrate)
