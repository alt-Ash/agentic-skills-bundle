package dev.dorrian.localcodegen.backend;

/** An OpenAI-compatible endpoint (llama.cpp {@code llama serve}, Ollama). A trailing '/' on the URL is dropped. */
public record Backend(String name, String baseUrl, String model) {
    public Backend {
        if (baseUrl != null) {
            baseUrl = baseUrl.replaceAll("/+$", "");
        }
    }
}
