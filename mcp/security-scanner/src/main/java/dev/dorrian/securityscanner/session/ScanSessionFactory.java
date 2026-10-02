package dev.dorrian.securityscanner.session;

import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Port of {@code createScanSession(opts)}. */
@Component
public class ScanSessionFactory {

    public ScanSession create(ScanSessionOptions options) {
        return new DefaultScanSession(options);
    }

    public ScanSession create(String target, Predicate<String> hostAllowed) {
        return create(ScanSessionOptions.forTarget(target, hostAllowed));
    }

    public ScanSession create(String target) {
        return create(ScanSessionOptions.forTarget(target));
    }
}
