package dev.dorrian.agenticskillscli.state;

import java.util.List;

/** Receives the hash of each item an installer wrote. Implementations must not throw into the install. */
public interface InstallRecorder {

    InstallRecorder NOOP = (kind, name, hash, files) -> { };

    /** @param files the bundle-relative file set the hash covers (empty for single-file items) */
    void record(ItemKind kind, String name, String hash, List<String> files);

    default void record(ItemKind kind, String name, String hash) {
        record(kind, name, hash, List.of());
    }
}
