# Spring Boot v2 → v3 Migration Guide

Source: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide

## Upgrading the parent/plugin version

Maven — bump the parent POM:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.0</version>
</parent>
```

Gradle — bump the plugin version:

```groovy
plugins {
    id 'org.springframework.boot' version '3.2.0'
}
```

Or apply the official OpenRewrite recipe instead of a manual bump, which also rewrites much of the breaking-change surface below automatically:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0
```

## Breaking changes

### Jakarta EE namespace migration (`javax.*` → `jakarta.*`)

This is the defining Spring Boot 3 breaking change. Spring Boot 3 moved from Java EE (`javax.*` packages) to Jakarta EE 9+ (`jakarta.*` packages) across the entire dependency tree — servlet API, JPA, validation, and more.

**Before:**
```java
import javax.servlet.http.HttpServletRequest;
import javax.persistence.Entity;
import javax.validation.constraints.NotNull;
```

**After:**
```java
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
```

This affects every direct and transitive dependency that exposes `javax.*` types at its API boundary — a library that hasn't itself migrated to Jakarta EE 9+ will not work under Spring Boot 3, not just your own code. Audit third-party dependencies for Jakarta-compatible releases before attempting the framework bump.

Grep for remaining `javax.*` imports after running the OpenRewrite recipe or a manual find-and-replace:

```bash
grep -rn "^import javax\." --include="*.java" src/
```

### Minimum JDK version raised to 17

Spring Boot 3 requires JDK 17 or newer. If the project is still on JDK 8 or 11, the JDK upgrade must happen first (see the parent `java-version-migrator` skill's JDK-hop guides) — this Spring Boot migration assumes JDK 17+ is already in place.

### Spring Security: lambda DSL replaces `WebSecurityConfigurerAdapter`

`WebSecurityConfigurerAdapter` subclassing (already deprecated in Spring Security 5.7) is fully removed in the Spring Security 6.x line that ships with Spring Boot 3. Security configuration must use the lambda-based `SecurityFilterChain` bean style.

**Before:**
```java
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeRequests()
            .antMatchers("/public/**").permitAll()
            .anyRequest().authenticated();
    }
}
```

**After:**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/public/**").permitAll()
            .anyRequest().authenticated());
        return http.build();
    }
}
```

Note `antMatchers` is also replaced by `requestMatchers` in this same version line.

### Actuator changes

Review actuator endpoint property names and exposed-by-default endpoint sets against the official migration guide — these have shifted in past major versions and are easy to silently misconfigure (e.g. an endpoint assumed-exposed no longer being exposed by default, or vice versa).

## Verification

```bash
mvn clean test
mvn package
```

## References

- [Spring Boot 3.0 Migration Guide (official wiki)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)
- [OpenRewrite Spring Boot 3 migration recipe](https://docs.openrewrite.org/recipes/java/spring/boot3/upgradespringboot_3_0)
