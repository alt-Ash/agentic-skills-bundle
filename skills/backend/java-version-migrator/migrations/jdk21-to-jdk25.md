# JDK 21 → 25 Migration Guide

> Reference: https://docs.oracle.com/en/java/javase/25/migrate/
> Release notes: https://openjdk.org/projects/jdk/25/

## Contents

- Breaking changes
- OpenRewrite recipes
- Deprecated APIs to migrate
- Infrastructure and tooling updates
- Verification
- Notable additions across JDK 22-25
- References

---

## Breaking changes

**Confidence note, read before applying this guide:** JDK 25 is a recent LTS release (September 2025) spanning four feature releases (22, 23, 24, 25) since JDK 21. Several of the changes below are stated *directionally* (a change the JDK has been moving toward across multiple releases) rather than pinned to one exact JEP number, because the precise release a given change landed/finalized in is easy to misstate from memory. **Verify every item below against the official JDK 22-25 release notes linked above before relying on it** — do not treat this table as a substitute for that check.

| Area | Change | Action |
|---|---|---|
| Security Manager | The Security Manager (deprecated for removal since JDK 17) has been fully disabled/removed within this release range — attempting to set one now fails | Remove all `SecurityManager`/`System.setSecurityManager` usage before upgrading; there is no replacement API, this requires a design change if still relied upon |
| JNI (Java Native Interface) | The JDK has been progressively restricting unrestricted native access; native-library integrations via JNI may require explicit module-level opt-in that wasn't previously necessary | If the project or a dependency uses JNI or the Foreign Function & Memory API, re-test native-library integration explicitly on the target JDK and check for new required `--enable-native-access` style flags |
| Continued strong encapsulation of internal APIs | Ongoing tightening since JDK 9, continuing through this range | Re-verify any `--add-opens`/`--add-exports` flags still in use are necessary and sufficient |

---

## OpenRewrite recipes

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.migrate.UpgradeToJava21
```

At the time of writing, OpenRewrite's Java migration recipe catalog is versioned incrementally — check [the current recipe catalog](https://docs.openrewrite.org/recipes/java/migrate) for the exact recipe id targeting 21→25 (it may be a newer recipe name than the one above, which targets the 17→21 hop specifically). Don't assume the recipe id without checking — OpenRewrite's catalog evolves alongside JDK releases.

Grep for any remaining Security Manager references after running recipes:

```bash
grep -rn "SecurityManager\|System\.getSecurityManager\|checkPermission" --include="*.java" .
```

---

## Deprecated APIs to migrate

### Security Manager (removed)

```java
// No longer functional on the target JDK — must be removed entirely
System.setSecurityManager(new SecurityManager());
```

As with the 17→21 hop, there is no drop-in replacement. If this hasn't already been addressed during an earlier hop, it is now a hard blocker, not a deprecation warning — confirm via a real build attempt rather than assuming.

### Native-library / JNI integrations

If the project has any JNI bindings or uses the Foreign Function & Memory API, re-run the full native-integration test suite explicitly — don't assume prior-version behavior carries forward unchanged.

---

## Infrastructure and tooling updates

### `pom.xml` (Maven)

```xml
<!-- Before -->
<properties>
  <java.version>21</java.version>
  <maven.compiler.release>21</maven.compiler.release>
</properties>

<!-- After -->
<properties>
  <java.version>25</java.version>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
```

### `build.gradle` (Gradle)

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

### `.sdkmanrc`

```
java=25-tem
```

### Dockerfile

```dockerfile
# Before
FROM eclipse-temurin:21-jre

# After
FROM eclipse-temurin:25-jre
```

### CI/CD (GitHub Actions example)

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: 'temurin'
    java-version: '25'
```

---

## Verification

```bash
mvn clean install
mvn test
```

(Or the Gradle equivalents: `./gradlew clean build`, `./gradlew test`.)

---

## Notable additions across JDK 22-25 (no action required, but worth adopting — verify exact landing version before citing)

- **Project Loom features maturing** — virtual threads (finalized in 21) have continued refinement; structured concurrency and scoped values have progressed through preview status across these releases toward finalization. Check the exact stable/preview status for the specific JDK version in use before depending on either in production code.
- **Continued pattern-matching and data-oriented programming refinements** building on the record patterns/switch pattern matching finalized in 21.
- **Generational ZGC** (introduced as an option in 21) has seen continued default-behavior refinement — re-check current GC defaults if the project doesn't explicitly pin a collector.

These are intentionally described at a lower level of version-specific precision than the JDK 17→21 guide's "Notable additions" section — confirm exact JEP numbers and finalization versions against the release notes link at the top of this file before citing them to a user as settled fact.

---

## References

- [Oracle JDK 25 Migration Guide](https://docs.oracle.com/en/java/javase/25/migrate/)
- [OpenJDK 25 project page](https://openjdk.org/projects/jdk/25/)
- [OpenRewrite migration recipes](https://docs.openrewrite.org/recipes/java/migrate)
