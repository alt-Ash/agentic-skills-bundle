package dev.dorrian.agenticskillscli.dashboard;

import java.io.IOException;
import java.util.Locale;

/** Best-effort "open this URL in the default browser"; silently does nothing if no opener is available. */
final class BrowserOpener {

    private BrowserOpener() {
    }

    static void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        if (os.contains("mac")) {
            command = new String[] {"open", url};
        } else if (os.contains("win")) {
            command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            command = new String[] {"xdg-open", url};
        }
        try {
            new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException ignored) {
            // The URL is printed; the user can open it by hand.
        }
    }
}
