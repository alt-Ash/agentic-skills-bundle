package dev.dorrian.issuetickets.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.issuetickets.credentials.AzureAccountsResolver;
import dev.dorrian.issuetickets.credentials.GithubAccountsResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Port of detect-source.ts. Checked in this exact order, first signal wins:
 * <ol>
 *   <li>{@code .github/} directory exists → github</li>
 *   <li>{@code azure-pipelines.yml} file or {@code .azure/} directory exists → azure</li>
 *   <li>{@code .git/config} contents match an Azure DevOps or GitHub host pattern</li>
 *   <li>{@code package.json}'s {@code repository} field (string, or {@code .url}) matches the
 *       same patterns</li>
 *   <li>{@code pom.xml}'s project-level {@code <scm>} ({@code url}, then {@code connection}, then
 *       {@code developerConnection} — the {@code scm:git:} prefix needs no stripping since the
 *       host patterns are substring matches), then {@code <issueManagement><url>}, then the
 *       project {@code <url>}, match the same patterns. Ranked after {@code package.json} so a
 *       repo that has both keeps its pre-existing answer; for JVM projects without a
 *       {@code package.json} this is the manifest tier. Only direct children of {@code <project>}
 *       are read ({@code <repositories>}/{@code <distributionManagement>} URLs describe artifact
 *       hosting, not ticket hosting), unresolved {@code ${...}} properties simply fail to match,
 *       and a malformed or DOCTYPE-bearing pom falls through (DTDs/external entities are
 *       rejected outright — XXE-safe). Gradle build files are deliberately not parsed: the
 *       Groovy/Kotlin DSL can't be read reliably without executing it, and a regex over it
 *       would false-positive on e.g. {@code maven.pkg.github.com} repository URLs.</li>
 *   <li>Otherwise: if exactly one of Azure/GitHub has credentials configured, use that one;
 *       both-or-neither is ambiguous (returns empty)</li>
 * </ol>
 */
@Component
public class SourceDetector {

    private static final Pattern AZURE_HOST_PATTERN = Pattern.compile("dev\\.azure\\.com|visualstudio\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_HOST_PATTERN = Pattern.compile("github\\.com", Pattern.CASE_INSENSITIVE);

    private final AzureAccountsResolver azureAccountsResolver;
    private final GithubAccountsResolver githubAccountsResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public SourceDetector(AzureAccountsResolver azureAccountsResolver, GithubAccountsResolver githubAccountsResolver) {
        this.azureAccountsResolver = azureAccountsResolver;
        this.githubAccountsResolver = githubAccountsResolver;
    }

    public Optional<Source> detect(Path projectRoot) {
        if (Files.isDirectory(projectRoot.resolve(".github"))) {
            return Optional.of(Source.github);
        }
        if (Files.exists(projectRoot.resolve("azure-pipelines.yml")) || Files.isDirectory(projectRoot.resolve(".azure"))) {
            return Optional.of(Source.azure);
        }

        Optional<Source> fromGitConfig = detectFromGitConfig(projectRoot);
        if (fromGitConfig.isPresent()) {
            return fromGitConfig;
        }

        Optional<Source> fromPackageJson = detectFromPackageJson(projectRoot);
        if (fromPackageJson.isPresent()) {
            return fromPackageJson;
        }

        Optional<Source> fromPom = detectFromPom(projectRoot);
        if (fromPom.isPresent()) {
            return fromPom;
        }

        boolean hasAzure = azureAccountsResolver.hasCredentials();
        boolean hasGithub = githubAccountsResolver.hasCredentials();
        if (hasAzure && !hasGithub) {
            return Optional.of(Source.azure);
        }
        if (hasGithub && !hasAzure) {
            return Optional.of(Source.github);
        }
        return Optional.empty();
    }

    public boolean isAmbiguous(Path projectRoot) {
        return detect(projectRoot).isEmpty()
            && azureAccountsResolver.hasCredentials()
            && githubAccountsResolver.hasCredentials();
    }

    private Optional<Source> detectFromGitConfig(Path projectRoot) {
        Path gitConfig = projectRoot.resolve(".git").resolve("config");
        if (!Files.exists(gitConfig)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(gitConfig);
            return fromHostPatterns(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Source> detectFromPackageJson(Path projectRoot) {
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(packageJson.toFile());
            JsonNode repository = root.get("repository");
            if (repository == null) {
                return Optional.empty();
            }
            String repoString = repository.isTextual() ? repository.asText() : repository.path("url").asText(null);
            if (repoString == null) {
                return Optional.empty();
            }
            return fromHostPatterns(repoString);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Project-level pom elements consulted, in order. Each path is relative to {@code <project>}. */
    private static final List<List<String>> POM_URL_PATHS = List.of(
        List.of("scm", "url"),
        List.of("scm", "connection"),
        List.of("scm", "developerConnection"),
        List.of("issueManagement", "url"),
        List.of("url"));

    private Optional<Source> detectFromPom(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return Optional.empty();
        }
        try {
            Document document = newSecureDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            if (project == null || !"project".equals(localName(project))) {
                return Optional.empty();
            }
            for (List<String> path : POM_URL_PATHS) {
                Optional<Source> found = childText(project, path).flatMap(this::fromHostPatterns);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        } catch (IOException | SAXException | ParserConfigurationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // A pom never legitimately needs a DTD; rejecting DOCTYPE outright is the strongest XXE
        // defence. The remaining settings are belt-and-braces in case a parser ignores the first.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // The default handler prints "[Fatal Error] ..." to stderr before throwing; a broken pom
        // is an expected fall-through here, not something to log.
        builder.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException e) { }
            @Override public void error(SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return builder;
    }

    private static Optional<String> childText(Element parent, List<String> path) {
        Element current = parent;
        for (String name : path) {
            current = directChild(current, name);
            if (current == null) {
                return Optional.empty();
            }
        }
        String text = current.getTextContent();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }

    private static Element directChild(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
    }

    private Optional<Source> fromHostPatterns(String content) {
        if (AZURE_HOST_PATTERN.matcher(content).find()) {
            return Optional.of(Source.azure);
        }
        if (GITHUB_HOST_PATTERN.matcher(content).find()) {
            return Optional.of(Source.github);
        }
        return Optional.empty();
    }
}
