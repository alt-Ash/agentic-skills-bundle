package dev.dorrian.agenticskillscli.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentHashTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void ofStringMatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ContentHash.ofString("abc"));
    }

    @Test
    void ofFileEqualsOfStringForSameContent() throws IOException {
        Path f = tmp.resolve("a.txt");
        write(f, "hello");
        assertEquals(ContentHash.ofString("hello"), ContentHash.ofFile(f));
    }

    @Test
    void ofFileMissingThrows() {
        assertThrows(NoSuchFileException.class, () -> ContentHash.ofFile(tmp.resolve("missing")));
    }

    @Test
    void ofTreeIgnoresCreationOrder() throws IOException {
        Path d1 = tmp.resolve("d1");
        Path d2 = tmp.resolve("d2");
        write(d1.resolve("a.txt"), "A");
        write(d1.resolve("sub/b.txt"), "B");
        write(d2.resolve("sub/b.txt"), "B");
        write(d2.resolve("a.txt"), "A");
        assertEquals(ContentHash.ofTree(d1), ContentHash.ofTree(d2));
    }

    @Test
    void ofTreeChangesWhenContentChanges() throws IOException {
        Path d = tmp.resolve("d");
        write(d.resolve("a.txt"), "A");
        String before = ContentHash.ofTree(d);
        write(d.resolve("a.txt"), "B");
        assertNotEquals(before, ContentHash.ofTree(d));
    }

    @Test
    void ofTreeChangesWhenAFileIsRenamed() throws IOException {
        Path d = tmp.resolve("d");
        write(d.resolve("a.txt"), "A");
        String before = ContentHash.ofTree(d);
        Files.move(d.resolve("a.txt"), d.resolve("b.txt"));
        assertNotEquals(before, ContentHash.ofTree(d));
    }

    @Test
    void ofTreeChangesWhenAFileIsAdded() throws IOException {
        Path d = tmp.resolve("d");
        write(d.resolve("a.txt"), "A");
        String before = ContentHash.ofTree(d);
        write(d.resolve("b.txt"), "B");
        assertNotEquals(before, ContentHash.ofTree(d));
    }

    @Test
    void ofTreeIgnoresEmptyDirectories() throws IOException {
        Path d = tmp.resolve("d");
        write(d.resolve("a.txt"), "A");
        String before = ContentHash.ofTree(d);
        Files.createDirectories(d.resolve("empty/nested"));
        assertEquals(before, ContentHash.ofTree(d));
    }

    @Test
    void ofTreeMissingRootThrows() {
        assertThrows(NoSuchFileException.class, () -> ContentHash.ofTree(tmp.resolve("missing")));
    }

    @Test
    void limitedToEqualsOfTreeOfShapeAndIgnoresExtras() throws IOException {
        Path shape = tmp.resolve("shape");
        Path root = tmp.resolve("root");
        write(shape.resolve("a.txt"), "A");
        write(shape.resolve("sub/b.txt"), "B");
        write(root.resolve("a.txt"), "A");
        write(root.resolve("sub/b.txt"), "B");
        write(root.resolve("extra.txt"), "X");
        write(root.resolve("sub/more.txt"), "Y");
        assertEquals(ContentHash.ofTree(shape), ContentHash.ofTreeLimitedTo(root, shape));
    }

    @Test
    void limitedToDiffersForMissingOrEditedOwnedFile() throws IOException {
        Path shape = tmp.resolve("shape");
        Path root = tmp.resolve("root");
        write(shape.resolve("a.txt"), "A");
        write(root.resolve("a.txt"), "A");
        String same = ContentHash.ofTreeLimitedTo(root, shape);
        write(root.resolve("a.txt"), "edited");
        assertNotEquals(same, ContentHash.ofTreeLimitedTo(root, shape));
        Files.delete(root.resolve("a.txt"));
        assertNotEquals(same, ContentHash.ofTreeLimitedTo(root, shape));
    }

    @Test
    void limitedToMissingRootIsAllMissingAndMissingShapeThrows() throws IOException {
        Path shape = tmp.resolve("shape");
        write(shape.resolve("a.txt"), "A");
        assertNotEquals(ContentHash.ofTree(shape), ContentHash.ofTreeLimitedTo(tmp.resolve("nope"), shape));
        assertThrows(NoSuchFileException.class, () -> ContentHash.ofTreeLimitedTo(tmp, tmp.resolve("missing")));
    }

    @Test
    void ofTreeOfEmptyDirectoryIsHashOfNothing() throws IOException {
        Path d = tmp.resolve("empty");
        Files.createDirectories(d);
        assertEquals(ContentHash.ofString(""), ContentHash.ofTree(d));
    }

    @Test
    void ofFilesMatchesLimitedToAndMarksMissingFiles() throws IOException {
        Path shape = tmp.resolve("shape");
        write(shape.resolve("a.txt"), "A");
        write(shape.resolve("sub/b.txt"), "B");
        Path live = tmp.resolve("live");
        write(live.resolve("a.txt"), "A");
        write(live.resolve("sub/b.txt"), "B");
        java.util.List<String> files = ContentHash.treeFiles(shape);
        assertEquals(java.util.List.of("a.txt", "sub/b.txt"), files);
        assertEquals(ContentHash.ofTreeLimitedTo(live, shape), ContentHash.ofFiles(live, files));
        assertEquals(ContentHash.ofTree(shape), ContentHash.ofFiles(live, java.util.List.of("sub/b.txt", "a.txt")));
        Files.delete(live.resolve("a.txt"));
        assertEquals(ContentHash.ofTreeLimitedTo(live, shape), ContentHash.ofFiles(live, files));
        assertNotEquals(ContentHash.ofTree(shape), ContentHash.ofFiles(live, files));
    }
}
