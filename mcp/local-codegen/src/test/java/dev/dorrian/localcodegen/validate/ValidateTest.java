package dev.dorrian.localcodegen.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dorrian.localcodegen.validate.Validate.OutputFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidateTest {

    @Test
    void normalizeRejectsEscapeAndAbsolute() {
        for (String bad : List.of("../x.java", "/etc/passwd", "a/../../b", "")) {
            assertThatThrownBy(() -> Validate.normalizeAllowed(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Validate.normalizeAllowed(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Validate.normalizeAllowed(List.of("a/b.java"))).containsExactly("a/b.java");
    }

    @Test
    void contextStaysInsideRoot(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(root.resolve("ok.txt"), "hi");
        Files.writeString(tmp.resolve("secret.txt"), "nope");
        Files.createSymbolicLink(root.resolve("link.txt"), tmp.resolve("secret.txt"));

        assertThat(Validate.resolveContext(root.toString(), List.of("ok.txt"))).isEqualTo(Map.of("ok.txt", "hi"));
        for (String bad : List.of("../secret.txt", "link.txt", "missing.txt")) {
            assertThatThrownBy(() -> Validate.resolveContext(root.toString(), List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Validate.resolveContext(root.toString(), List.of("../secret.txt")))
            .hasMessageContaining("escapes project_root");
    }

    @Test
    void contextRootMustBeDirectory(@TempDir Path tmp) {
        assertThatThrownBy(() -> Validate.resolveContext(tmp.resolve("nope").toString(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkOutputRejectsEachFailureMode() {
        List<String> allowed = List.of("A.java", "B.java", "C.java", "D.java");
        List<OutputFile> files = List.of(
            new OutputFile("A.java", "class A {}"),
            new OutputFile("A.java", "class A {}"),
            new OutputFile("Evil.java", "class E {}"),
            new OutputFile("B.java", "#L1\nclass B {}\n#L2\n"),
            new OutputFile("C.java", "   "));
        Validate.Checked r = Validate.checkOutput(files, allowed);

        assertThat(r.accepted()).extracting(OutputFile::path).containsExactly("A.java");
        assertThat(r.rejected().stream().collect(Collectors.toMap(
            Validate.Rejected::path, Validate.Rejected::reason)))
            .containsEntry("A.java", "duplicate path")
            .containsEntry("Evil.java", "path not in files_allowed")
            .containsEntry("B.java", "line-marker artifact (#L<n> lines) in content")
            .containsEntry("C.java", "empty content");
    }

    @Test
    void checkOutputRejectsOversizedFile() {
        String big = "x".repeat(Validate.MAX_FILE_BYTES + 1);
        Validate.Checked r = Validate.checkOutput(List.of(new OutputFile("A.java", big)), List.of("A.java"));
        assertThat(r.rejected()).singleElement().extracting(Validate.Rejected::reason).isEqualTo("file too large");
    }

    @org.junit.jupiter.api.Test
    void normalizeRejectsDotAndDirectoryEntries() {
        for (String bad : new String[] {"./", ".", "src/", "a//"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Validate.normalizeAllowed(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
