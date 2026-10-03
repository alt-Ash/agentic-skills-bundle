package dev.dorrian.localcodegen.backend;

/** Environment-variable lookup, injectable so tests need not mutate the process environment. */
@FunctionalInterface
public interface Env {
    String get(String name);
}
