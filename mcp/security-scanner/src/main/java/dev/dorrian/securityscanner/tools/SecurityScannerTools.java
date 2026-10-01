package dev.dorrian.securityscanner.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.securityscanner.allowlist.AllowlistLoader;
import dev.dorrian.securityscanner.allowlist.TargetCheckResult;
import dev.dorrian.securityscanner.checks.ActiveCategory;
import dev.dorrian.securityscanner.checks.ActiveCheckOptions;
import dev.dorrian.securityscanner.checks.ActiveChecks;
import dev.dorrian.securityscanner.checks.Finding;
import dev.dorrian.securityscanner.checks.PassiveChecks;
import dev.dorrian.securityscanner.report.ReportWriter;
import dev.dorrian.securityscanner.report.ScanMode;
import dev.dorrian.securityscanner.report.ScanReport;
import dev.dorrian.securityscanner.report.ScanStatus;
import dev.dorrian.securityscanner.session.ScanSession;
import dev.dorrian.securityscanner.session.ScanSessionFactory;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The 3 MCP tools this server exposes. Port of index.ts's {@code registerTool} calls — same
 * names, same descriptions, same schemas, same response shapes (including the documented
 * passive/active response-status asymmetry, preserved deliberately — see {@link #scanPassive}).
 */
@Service
public class SecurityScannerTools {

    private final AllowlistLoader allowlistLoader;
    private final ScanSessionFactory sessionFactory;
    private final PassiveChecks passiveChecks;
    private final ActiveChecks activeChecks;
    private final ReportWriter reportWriter;
    private final ObjectMapper mapper = new ObjectMapper();

    public SecurityScannerTools(
        AllowlistLoader allowlistLoader,
        ScanSessionFactory sessionFactory,
        PassiveChecks passiveChecks,
        ActiveChecks activeChecks,
        ReportWriter reportWriter
    ) {
        this.allowlistLoader = allowlistLoader;
        this.sessionFactory = sessionFactory;
        this.passiveChecks = passiveChecks;
        this.activeChecks = activeChecks;
        this.reportWriter = reportWriter;
    }

    @Tool(name = "scan_passive", description = "Runs safe, read-only vulnerability fingerprinting "
        + "against an allowlisted target: missing security headers, cookie flags, CORS "
        + "misconfiguration, exposed sensitive paths (.env, .git, /admin), and server/framework "
        + "fingerprinting. Refuses any target not present in .security-scanner/allowlist.json.")
    public String scanPassive(
        @ToolParam(description = "Host (and port if non-default), e.g. \"localhost:3000\" or \"staging.example.com\".")
        String target
    ) {
        TargetCheckResult auth = allowlistLoader.authorizeTarget(target);
        if (auth.configError() != null) {
            return refused(auth.configError());
        }
        if (!auth.allowed()) {
            return refused(auth.reason());
        }

        String startedAt = Instant.now().toString();
        ScanSession session = sessionFactory.create(target);
        List<Finding> findings = passiveChecks.run(session);
        var stats = session.getStats();
        String finishedAt = Instant.now().toString();

        ScanReport report = new ScanReport(
            ScanReport.SCHEMA, target, auth.environment().json(), ScanMode.PASSIVE,
            startedAt, finishedAt, stats.aborted() ? ScanStatus.ABORTED_INSTABILITY : ScanStatus.COMPLETED,
            null, stats.requestsIssued(), findings
        );
        var written = reportWriter.writeReport(report);

        // Deliberate asymmetry preserved from the source: this response's "status" always says
        // "completed" regardless of whether the session actually aborted — only the persisted
        // report file (and scan_active's response, below) correctly reflects aborted_instability.
        ObjectNode response = mapper.createObjectNode();
        response.put("status", "completed");
        response.put("scanId", written.scanId());
        response.put("reportPath", written.mdPath().toString());
        response.put("findingCount", findings.size());
        response.set("findings", mapper.valueToTree(findings));
        return toJson(response);
    }

    @Tool(name = "scan_active", description = "Runs real-payload vulnerability checks (XSS, "
        + "SQL/NoSQL injection, open redirect, path traversal, JWT/IDOR auth-bypass probes, SSRF "
        + "signal) against an allowlisted target. Requires confirm: true and a non-empty "
        + "authorization string. Refuses any target not present in .security-scanner/allowlist.json. "
        + "A shared circuit breaker aborts the scan if the target starts erroring or slowing down "
        + "significantly.")
    public String scanActive(
        @ToolParam(description = "Host (and port if non-default), e.g. \"localhost:3000\" or \"staging.example.com\".")
        String target,
        @ToolParam(required = false, description = "Subset of check categories to run. Omit to run all.")
        List<ActiveCategory> categories,
        @ToolParam(description = "Must be true — deliberate friction so this never fires by accident.")
        boolean confirm,
        @ToolParam(description = "Free-text authorization record (e.g. ticket ref), stamped into the report as an audit trail.")
        String authorization
    ) {
        TargetCheckResult auth = allowlistLoader.authorizeTarget(target);
        if (auth.configError() != null) {
            return refused(auth.configError());
        }
        if (!auth.allowed()) {
            return refused(auth.reason());
        }
        if (!confirm) {
            return refused("confirm must be explicitly set to true to run active exploitation checks.");
        }
        if (authorization == null || authorization.trim().isEmpty()) {
            return refused("authorization must be a non-empty string (e.g. a ticket reference) — "
                + "recorded in the report as an audit trail.");
        }

        String startedAt = Instant.now().toString();
        ScanSession session = sessionFactory.create(target);
        List<Finding> findings = activeChecks.run(session,
            categories != null && !categories.isEmpty() ? ActiveCheckOptions.of(categories) : ActiveCheckOptions.all());
        var stats = session.getStats();
        String finishedAt = Instant.now().toString();

        ScanStatus status = stats.aborted() ? ScanStatus.ABORTED_INSTABILITY : ScanStatus.COMPLETED;
        ScanReport report = new ScanReport(
            ScanReport.SCHEMA, target, auth.environment().json(), ScanMode.ACTIVE,
            startedAt, finishedAt, status, authorization, stats.requestsIssued(), findings
        );
        var written = reportWriter.writeReport(report);

        ObjectNode response = mapper.createObjectNode();
        response.put("status", status.json());
        response.put("scanId", written.scanId());
        response.put("reportPath", written.mdPath().toString());
        response.put("requestsIssued", stats.requestsIssued());
        response.put("findingCount", findings.size());
        response.set("findings", mapper.valueToTree(findings));
        return toJson(response);
    }

    @Tool(name = "get_scan_report", description = "Retrieves a previously written scan report by its scanId.")
    public String getScanReport(
        @ToolParam(description = "The scanId returned by scan_passive or scan_active.")
        String scanId
    ) {
        ScanReport report = reportWriter.getScanReport(scanId);
        if (report == null) {
            throw new IllegalStateException("No report found for scanId '" + scanId + "'.");
        }
        return toJson(report);
    }

    private String refused(String reason) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "refused");
        node.put("reason", reason);
        return toJson(node);
    }

    private String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool response", e);
        }
    }
}
