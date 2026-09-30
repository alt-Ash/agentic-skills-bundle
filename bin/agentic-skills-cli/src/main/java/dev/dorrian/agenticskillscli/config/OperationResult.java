package dev.dorrian.agenticskillscli.config;

/**
 * Java equivalent of the {@code { name, success, skipped, configFile, error }}
 * plain object literals returned throughout {@code bin/install.js}'s
 * install/uninstall/registration functions.
 */
public record OperationResult(String name, boolean success, boolean skipped, String configFile, String error) {

    public static OperationResult ok(String name, boolean skipped, String configFile) {
        return new OperationResult(name, true, skipped, configFile, null);
    }

    public static OperationResult failed(String name, String configFile, String error) {
        return new OperationResult(name, false, false, configFile, error);
    }
}
