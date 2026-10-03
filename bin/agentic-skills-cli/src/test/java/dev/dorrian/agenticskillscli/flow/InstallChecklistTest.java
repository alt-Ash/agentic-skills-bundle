package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.state.InstallManifest;
import dev.dorrian.agenticskillscli.state.InstallStatus;
import dev.dorrian.agenticskillscli.state.ItemKind;
import dev.dorrian.agenticskillscli.state.ItemState;
import dev.dorrian.agenticskillscli.ui.Ansi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallChecklistTest {

    private static Map<String, ItemState> states(Object... pairs) {
        Map<String, ItemState> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], new ItemState((InstallStatus) pairs[i + 1], false));
        }
        return m;
    }

    @Test
    void updateAvailableWinsAndIsPreselected() {
        var e = InstallChecklist.combine(states("A", InstallStatus.MODIFIED, "B", InstallStatus.UPDATE_AVAILABLE,
            "C", InstallStatus.NOT_INSTALLED), true);
        assertEquals("update available", e.suffix());
        assertTrue(e.preselected());
        assertFalse(e.dimWhole());
    }

    @Test
    void modifiedBeatsNotInstalledAndIsNotPreselected() {
        var e = InstallChecklist.combine(states("A", InstallStatus.MODIFIED, "B", InstallStatus.NOT_INSTALLED), false);
        assertEquals("installed, modified locally", e.suffix());
        assertFalse(e.preselected());
    }

    @Test
    void partiallyInstalledNamesMissingTools() {
        var e = InstallChecklist.combine(states("Claude", InstallStatus.UP_TO_DATE, "OpenCode", InstallStatus.NOT_INSTALLED,
            "Cursor", InstallStatus.NOT_INSTALLED), true);
        assertEquals("not installed for OpenCode, Cursor", e.suffix());
        assertFalse(e.preselected());
        assertFalse(e.dimWhole());
    }

    @Test
    void allMissingIsNewOnlyWhenNew() {
        var m = states("A", InstallStatus.NOT_INSTALLED, "B", InstallStatus.NOT_INSTALLED);
        assertEquals("new", InstallChecklist.combine(m, true).suffix());
        assertEquals("not installed", InstallChecklist.combine(m, false).suffix());
        assertFalse(InstallChecklist.combine(m, true).preselected());
    }

    @Test
    void allUpToDateIsDimmedAndNotPreselected() {
        var e = InstallChecklist.combine(states("A", InstallStatus.UP_TO_DATE, "B", InstallStatus.UP_TO_DATE), true);
        assertEquals("installed, up to date", e.suffix());
        assertFalse(e.preselected());
        assertTrue(e.dimWhole());
    }

    @Test
    void labelsKeepExistingFormat() {
        var plain = new InstallChecklist.Entry("new", false, false);
        assertEquals(Ansi.dim("[java] ") + Ansi.cyan("spring") + Ansi.dim(" — new"),
            InstallChecklist.skillLabel("java", "spring", plain));
        assertEquals(Ansi.cyan("tdd") + Ansi.dim(" — new"), InstallChecklist.agentLabel("tdd", plain));
        var dimmed = new InstallChecklist.Entry("installed, up to date", false, true);
        assertEquals(Ansi.dim("tdd — installed, up to date"), InstallChecklist.agentLabel("tdd", dimmed));
    }

    @Test
    void isNewNeedsNonEmptyBaselineWithoutTheItem(@TempDir Path dir) {
        InstallManifest empty = InstallManifest.load(dir.resolve("none.json"));
        assertFalse(InstallChecklist.isNew(empty, ItemKind.SKILL, "x"));

        InstallManifest m = InstallManifest.load(dir.resolve("m.json"));
        m.recordBundle("1.0", List.of("SKILL:old"));
        assertFalse(InstallChecklist.isNew(m, ItemKind.SKILL, "old"));
        assertTrue(InstallChecklist.isNew(m, ItemKind.SKILL, "fresh"));
        assertTrue(InstallChecklist.isNew(m, ItemKind.AGENT, "old"));
    }

    @Test
    void anyInstalledDetectsAnyNonMissingState() {
        var none = states("A", InstallStatus.NOT_INSTALLED);
        var some = states("A", InstallStatus.NOT_INSTALLED, "B", InstallStatus.MODIFIED);
        assertFalse(InstallChecklist.anyInstalled(List.of(none)));
        assertTrue(InstallChecklist.anyInstalled(List.of(none, some)));
        assertFalse(InstallChecklist.anyInstalled(List.of()));
    }

    @Test
    void everythingSelectionExcludesLocallyModifiedItems() {
        Map<String, Map<String, ItemState>> items = new LinkedHashMap<>();
        items.put("clean", states("A", InstallStatus.UP_TO_DATE));
        items.put("edited", states("A", InstallStatus.MODIFIED));
        items.put("mixed", states("A", InstallStatus.MODIFIED, "B", InstallStatus.UPDATE_AVAILABLE));
        items.put("fresh", states("A", InstallStatus.NOT_INSTALLED));

        assertEquals(List.of("clean", "mixed", "fresh"), InstallChecklist.everythingSelection(items));
        assertEquals(1, InstallChecklist.modifiedCount(items.values()));
        assertEquals("1 locally modified item was not included; pick it in the checklist to overwrite.",
            InstallChecklist.modifiedNote(1));
        assertTrue(InstallChecklist.modifiedNote(3).startsWith("3 locally modified items were not included"));
    }

    @Test
    void installedEntriesListsItemsInstalledForAnyTool() {
        Map<String, Map<String, ItemState>> items = new LinkedHashMap<>();
        items.put("a", states("T1", InstallStatus.NOT_INSTALLED, "T2", InstallStatus.UP_TO_DATE));
        items.put("b", states("T1", InstallStatus.NOT_INSTALLED));
        assertEquals(List.of("SKILL:a"), InstallChecklist.installedEntries(ItemKind.SKILL, items, n -> n));
    }
}
