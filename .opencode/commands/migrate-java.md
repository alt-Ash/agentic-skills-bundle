---
description: "Migrate Java from one major version to another. Usage: /migrate-java <from> <to> (e.g. /migrate-java 17 21)"
subtask: true
---

Use the `java-version-migrator` skill to migrate this project from Java $1 to Java $2.

Follow the full skill workflow, including: reading the relevant migration guide(s), auditing breaking changes, running OpenRewrite recipes, auditing Maven/Gradle dependency compatibility, updating `pom.xml`/`build.gradle` Java version and tooling configs (`.sdkmanrc`, Dockerfiles, CI/CD pipelines), verifying tests and build, and checking for license violations.

If the version gap requires multiple hops (e.g. 17→21→25), chain the guides in order and complete all steps for each hop before starting the next.
