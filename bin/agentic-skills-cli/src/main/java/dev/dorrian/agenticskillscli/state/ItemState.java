package dev.dorrian.agenticskillscli.state;

/**
 * @param status            the comparison result
 * @param comparedByContent true when no manifest entry existed, so the answer rests on content comparison alone
 */
public record ItemState(InstallStatus status, boolean comparedByContent) {
}
