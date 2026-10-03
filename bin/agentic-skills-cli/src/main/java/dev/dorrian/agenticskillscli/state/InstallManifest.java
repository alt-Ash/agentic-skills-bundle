package dev.dorrian.agenticskillscli.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillscli.HomeDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the installer last wrote, as content hashes, persisted as JSON. Keys are identifiers only and
 * are never used as paths. A missing, unreadable or corrupt file yields an empty manifest.
 */
public final class InstallManifest {

    private static final int SCHEMA = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final Map<String, Entry> items = new TreeMap<>();
    private String bundleVersion;
    private final Set<String> bundleItems = new LinkedHashSet<>();

    private record Entry(String hash, String version, List<String> files) {
    }

    private InstallManifest(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        return HomeDir.resolve().resolve(".agentic-skills").resolve("state").resolve("installed.json");
    }

    public static String key(String scope, String tool, ItemKind kind, String name) {
        return scope + "|" + tool + "|" + kind + "|" + name;
    }

    public static InstallManifest load(Path file) {
        InstallManifest m = new InstallManifest(file);
        try {
            if (!Files.isRegularFile(file)) {
                return m;
            }
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject() || root.path("schema").asInt(-1) != SCHEMA) {
                return new InstallManifest(file);
            }
            JsonNode version = root.get("bundleVersion");
            if (version != null && version.isTextual()) {
                m.bundleVersion = version.asText();
            }
            JsonNode bi = root.get("bundleItems");
            if (bi != null) {
                if (!bi.isArray()) return new InstallManifest(file);
                for (JsonNode n : bi) {
                    if (!n.isTextual()) return new InstallManifest(file);
                    m.bundleItems.add(n.asText());
                }
            }
            JsonNode its = root.get("items");
            if (its != null) {
                if (!its.isObject()) return new InstallManifest(file);
                for (Map.Entry<String, JsonNode> e : its.properties()) {
                    JsonNode hash = e.getValue().get("hash");
                    JsonNode ver = e.getValue().get("version");
                    if (hash == null || !hash.isTextual()) return new InstallManifest(file);
                    List<String> files = null;
                    JsonNode fs = e.getValue().get("files");
                    if (fs != null && !fs.isNull()) {
                        if (!fs.isArray()) return new InstallManifest(file);
                        files = new ArrayList<>();
                        for (JsonNode f : fs) {
                            if (!f.isTextual()) return new InstallManifest(file);
                            files.add(f.asText());
                        }
                    }
                    m.items.put(e.getKey(), new Entry(hash.asText(),
                        ver != null && ver.isTextual() ? ver.asText() : null, files));
                }
            }
            return m;
        } catch (IOException | RuntimeException e) {
            return new InstallManifest(file);
        }
    }

    public Optional<String> hash(String key) {
        Entry v = items.get(key);
        return v == null ? Optional.empty() : Optional.of(v.hash());
    }

    /** The file set the recorded hash covers, when the entry has one (skills and templates). */
    public Optional<List<String>> files(String key) {
        Entry v = items.get(key);
        return v == null || v.files() == null ? Optional.empty() : Optional.of(v.files());
    }

    public void record(String key, String hash, String version) {
        record(key, hash, version, List.of());
    }

    /** An empty {@code files} list is stored as "no file set". */
    public void record(String key, String hash, String version, List<String> files) {
        items.put(key, new Entry(hash, version, files == null || files.isEmpty() ? null : List.copyOf(files)));
    }

    /**
     * Moves the baseline forward: an empty baseline becomes {@code catalog}; otherwise it grows by
     * {@code installedEntries} only, so an item never installed keeps reading as new. Always sets the version.
     */
    public void advanceBundle(String version, Collection<String> catalog, Collection<String> installedEntries) {
        this.bundleVersion = version;
        if (bundleItems.isEmpty()) {
            bundleItems.addAll(catalog);
        } else {
            bundleItems.addAll(installedEntries);
        }
    }

    public void recordBundle(String version, Collection<String> items) {
        this.bundleVersion = version;
        this.bundleItems.clear();
        this.bundleItems.addAll(items);
    }

    public String bundleVersion() {
        return bundleVersion;
    }

    public Set<String> bundleItems() {
        return Set.copyOf(bundleItems);
    }

    public void save() throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);
        if (bundleVersion == null) {
            root.putNull("bundleVersion");
        } else {
            root.put("bundleVersion", bundleVersion);
        }
        ArrayNode arr = root.putArray("bundleItems");
        new ArrayList<>(bundleItems).forEach(arr::add);
        ObjectNode its = root.putObject("items");
        for (Map.Entry<String, Entry> e : items.entrySet()) {
            ObjectNode o = its.putObject(e.getKey());
            o.put("hash", e.getValue().hash());
            if (e.getValue().version() == null) {
                o.putNull("version");
            } else {
                o.put("version", e.getValue().version());
            }
            if (e.getValue().files() != null) {
                ArrayNode fa = o.putArray("files");
                e.getValue().files().forEach(fa::add);
            }
        }
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".installed-", ".tmp");
        try {
            Files.write(tmp, MAPPER.writeValueAsBytes(root));
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
