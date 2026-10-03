package dev.dorrian.agenticskillscli.state;

import java.io.IOException;
import java.util.List;

/** Records installed item hashes into an {@link InstallManifest} for one scope and tool. */
public final class ManifestRecorder implements InstallRecorder {

    private final InstallManifest manifest;
    private final String scope;
    private final String tool;
    private final String version;

    public ManifestRecorder(InstallManifest manifest, String scope, String tool, String version) {
        this.manifest = manifest;
        this.scope = scope;
        this.tool = tool;
        this.version = version;
    }

    @Override
    public void record(ItemKind kind, String name, String hash, List<String> files) {
        manifest.record(InstallManifest.key(scope, tool, kind, name), hash, version, files);
    }

    public void flush() throws IOException {
        manifest.save();
    }
}
