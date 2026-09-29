package dev.dorrian.securityscanner.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dorrian.securityscanner.checks.Finding;
import dev.dorrian.securityscanner.checks.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    private final ReportWriter writer = new ReportWriter();

    @Test
    void scanIdSanitizesHostAndStripsNonDigitsFromTimestamp() {
        String scanId = ReportWriter.scanIdFor("localhost:3000", "2026-09-29T12:34:56.789Z");

        assertThat(scanId).isEqualTo("localhost_3000-20260929123456789");
    }

    @Test
    void writeReportCreatesBothJsonAndMarkdownFilesUnderCwdRelativeSecurityScans(@TempDir Path tmpDir)
        throws IOException {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmpDir.toString());
        try {
            var report = new ScanReport(
                ScanReport.SCHEMA, "localhost:3000", "local", ScanMode.PASSIVE,
                "2026-09-29T00:00:00.000Z", "2026-09-29T00:00:01.000Z", ScanStatus.COMPLETED,
                null, 3, List.of()
            );

            var result = writer.writeReport(report);

            assertThat(Files.exists(result.jsonPath())).isTrue();
            assertThat(Files.exists(result.mdPath())).isTrue();
            assertThat(Files.readString(result.jsonPath())).contains("\"status\" : \"completed\"");
            assertThat(Files.readString(result.mdPath())).contains("No findings.");
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void getScanReportReturnsNullWhenFileDoesNotExist(@TempDir Path tmpDir) {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmpDir.toString());
        try {
            assertThat(writer.getScanReport("never-written")).isNull();
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void writeThenGetScanReportRoundTrips(@TempDir Path tmpDir) {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmpDir.toString());
        try {
            var finding = new Finding("PF-01", "A05", Severity.MODERATE, "desc", "evidence", "fix it");
            var report = new ScanReport(
                ScanReport.SCHEMA, "example.com", "dev", ScanMode.ACTIVE,
                "2026-09-29T00:00:00.000Z", "2026-09-29T00:00:01.000Z", ScanStatus.ABORTED_INSTABILITY,
                "TICKET-123", 42, List.of(finding)
            );

            var written = writer.writeReport(report);
            var roundTripped = writer.getScanReport(written.scanId());

            assertThat(roundTripped).isEqualTo(report);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void renderMarkdownSortsFindingsBySeverityCriticalFirst() {
        var low = new Finding("PF-01", "A05", Severity.LOW, "low desc", "e", "r");
        var critical = new Finding("PF-02", "A05", Severity.CRITICAL, "critical desc", "e", "r");
        var report = new ScanReport(
            ScanReport.SCHEMA, "example.com", "local", ScanMode.PASSIVE,
            "2026-09-29T00:00:00.000Z", "2026-09-29T00:00:01.000Z", ScanStatus.COMPLETED,
            null, 1, List.of(low, critical)
        );

        String markdown = ReportWriter.renderMarkdown(report);

        assertThat(markdown.indexOf("critical desc")).isLessThan(markdown.indexOf("low desc"));
    }
}
