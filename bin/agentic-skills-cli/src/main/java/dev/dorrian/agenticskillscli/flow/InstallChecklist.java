package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.state.BundleCatalog;
import dev.dorrian.agenticskillscli.state.InstallManifest;
import dev.dorrian.agenticskillscli.state.InstallStatus;
import dev.dorrian.agenticskillscli.state.ItemKind;
import dev.dorrian.agenticskillscli.state.ItemState;
import dev.dorrian.agenticskillscli.ui.Ansi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure rules for annotating the Install flow's skill and agent checklists with what is already installed. */
public final class InstallChecklist {

    /**
     * @param suffix       plain-text status note (no ANSI)
     * @param preselected  whether the checklist ticks the item by default
     * @param dimWhole     whether the whole label is dimmed (nothing to do for this item)
     */
    public record Entry(String suffix, boolean preselected, boolean dimWhole) {
    }

    private InstallChecklist() {
    }

    /**
     * Combines per-tool states (keyed by tool display name, in selection order) for one item.
     *
     * @param isNew true when the bundle ships the item but the manifest's baseline does not list it
     */
    public static Entry combine(Map<String, ItemState> statesByTool, boolean isNew) {
        List<InstallStatus> all = statesByTool.values().stream().map(ItemState::status).toList();
        if (all.contains(InstallStatus.UPDATE_AVAILABLE)) {
            return new Entry("update available", true, false);
        }
        if (all.contains(InstallStatus.MODIFIED)) {
            return new Entry("installed, modified locally", false, false);
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, ItemState> e : statesByTool.entrySet()) {
            if (e.getValue().status() == InstallStatus.NOT_INSTALLED) missing.add(e.getKey());
        }
        if (missing.size() == statesByTool.size()) {
            return new Entry(isNew ? "new" : "not installed", false, false);
        }
        if (!missing.isEmpty()) {
            return new Entry("not installed for " + String.join(", ", missing), false, false);
        }
        return new Entry("installed, up to date", false, true);
    }

    /** True when the combined status is "installed, modified locally" (the same precedence as {@link #combine}). */
    public static boolean isModified(Map<String, ItemState> statesByTool) {
        List<InstallStatus> all = statesByTool.values().stream().map(ItemState::status).toList();
        return !all.contains(InstallStatus.UPDATE_AVAILABLE) && all.contains(InstallStatus.MODIFIED);
    }

    /** What "Install everything" selects: every item except those modified locally (they stay unselected). */
    public static <T> List<T> everythingSelection(Map<T, Map<String, ItemState>> states) {
        List<T> out = new ArrayList<>();
        for (Map.Entry<T, Map<String, ItemState>> e : states.entrySet()) {
            if (!isModified(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** How many items "Install everything" leaves out because they are modified locally. */
    public static int modifiedCount(Iterable<Map<String, ItemState>> perItem) {
        int n = 0;
        for (Map<String, ItemState> states : perItem) {
            if (isModified(states)) n++;
        }
        return n;
    }

    /** The dim note printed after "Install everything" skipped locally modified items. */
    public static String modifiedNote(int count) {
        return count + (count == 1 ? " locally modified item was" : " locally modified items were")
            + " not included; pick " + (count == 1 ? "it" : "them") + " in the checklist to overwrite.";
    }

    /** Catalog entries of items with anything installed for any tool (detected before this run). */
    public static <T> List<String> installedEntries(ItemKind kind, Map<T, Map<String, ItemState>> states,
                                                    java.util.function.Function<T, String> nameOf) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<T, Map<String, ItemState>> e : states.entrySet()) {
            boolean any = e.getValue().values().stream().anyMatch(st -> st.status() != InstallStatus.NOT_INSTALLED);
            if (any) out.add(BundleCatalog.entry(kind, nameOf.apply(e.getKey())));
        }
        return out;
    }

    /** True when the manifest has a non-empty baseline that does not list this bundled item. */
    public static boolean isNew(InstallManifest manifest, ItemKind kind, String name) {
        var baseline = manifest.bundleItems();
        return !baseline.isEmpty() && !baseline.contains(BundleCatalog.entry(kind, name));
    }

    /** True when any of the states shows something installed. */
    public static boolean anyInstalled(Iterable<Map<String, ItemState>> perItem) {
        for (Map<String, ItemState> states : perItem) {
            for (ItemState s : states.values()) {
                if (s.status() != InstallStatus.NOT_INSTALLED) return true;
            }
        }
        return false;
    }

    public static String skillLabel(String category, String name, Entry entry) {
        return label("[" + category + "] ", name, entry);
    }

    public static String agentLabel(String name, Entry entry) {
        return label("", name, entry);
    }

    /** A label whose leading dim prefix and cyan name are followed by the dim status suffix. */
    public static String label(String dimPrefix, String name, Entry entry) {
        String suffix = " — " + entry.suffix();
        if (entry.dimWhole()) {
            return Ansi.dim(dimPrefix + name + suffix);
        }
        return (dimPrefix.isEmpty() ? "" : Ansi.dim(dimPrefix)) + Ansi.cyan(name) + Ansi.dim(suffix);
    }
}
