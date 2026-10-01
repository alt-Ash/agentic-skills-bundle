package dev.dorrian.securityscanner.session;

import java.util.Map;

/**
 * Per-request overrides. Port of the {@code init} parameter probes pass to {@code
 * session.request(pathOrUrl, init?)} — {@code followRedirects=false} mirrors {@code {redirect:
 * 'manual'}}, used by the open-redirect probe to inspect a raw 3xx response instead of having
 * the HTTP client auto-follow it.
 */
public record RequestOptions(Map<String, String> headers, boolean followRedirects) {

    private static final RequestOptions DEFAULTS = new RequestOptions(Map.of(), true);

    public static RequestOptions defaults() {
        return DEFAULTS;
    }

    public static RequestOptions manualRedirect() {
        return new RequestOptions(Map.of(), false);
    }

    public static RequestOptions withHeaders(Map<String, String> headers) {
        return new RequestOptions(headers, true);
    }
}
