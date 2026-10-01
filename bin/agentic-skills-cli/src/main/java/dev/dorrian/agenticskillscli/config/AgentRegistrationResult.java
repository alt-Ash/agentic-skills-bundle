package dev.dorrian.agenticskillscli.config;

/**
 * Result of {@link JsonConfigStore#registerAgentInConfig} — has an extra
 * {@code repaired} field beyond {@link OperationResult}, matching the
 * original's {@code { name, success, skipped, repaired, configFile }}.
 *
 * <p>{@code error} is only ever non-null for the failure shape the original
 * pushes from its call sites' {@code catch} blocks (a bare {@code {name,
 * success:false, error, configFile}} object — {@code registerAgentInConfig}
 * itself never returns a failure, only null-or-a-result; the surrounding
 * caller synthesizes the failure entry when the call throws).
 */
public record AgentRegistrationResult(
    String name, boolean success, boolean skipped, boolean repaired, String configFile, String error
) {

    public AgentRegistrationResult(String name, boolean success, boolean skipped, boolean repaired, String configFile) {
        this(name, success, skipped, repaired, configFile, null);
    }

    public static AgentRegistrationResult failed(String name, String configFile, String error) {
        return new AgentRegistrationResult(name, false, false, false, configFile, error);
    }
}
