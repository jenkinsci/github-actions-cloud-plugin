package io.jenkins.plugins.ghacloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal GitHub REST API client for triggering workflow_dispatch events.
 */
public class GitHubClient {

    private static final Logger LOGGER = Logger.getLogger(GitHubClient.class.getName());

    private final String apiUrl;
    private final String token;

    public GitHubClient(String apiUrl, String token) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
    }

    /**
     * Triggers a workflow_dispatch event on the specified repository and workflow
     * file.
     *
     * @param repository   owner/repo
     * @param workflowFile the workflow filename (e.g. jenkins-agent.yml)
     * @param ref          git ref to run against (e.g. main)
     * @param inputs       key-value inputs forwarded to the workflow
     */
    public void triggerWorkflow(String repository, String workflowFile, String ref,
            Map<String, String> inputs) throws IOException {
        String url = apiUrl + "/repos/" + repository + "/actions/workflows/" + workflowFile + "/dispatches";
        String body = buildJson(ref, inputs);

        IOException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                doPost(url, body);
                return;
            } catch (javax.net.ssl.SSLException e) {
                lastException = e;
                LOGGER.log(Level.WARNING, "Attempt {0}/3 failed with SSL error, retrying: {1}",
                        new Object[]{attempt, e.getMessage()});
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", ie);
                }
            }
        }
        throw lastException;
    }

    private void doPost(String url, String body) throws IOException {
        LOGGER.log(Level.INFO, "Dispatching workflow: POST {0}", url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream errStream = conn.getErrorStream();
                String error = errStream != null
                        ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8)
                        : "(no response body)";
                throw new IOException("GitHub API returned HTTP " + status
                        + " for workflow dispatch: " + error);
            }
            LOGGER.log(Level.INFO, "Workflow dispatch successful (HTTP {0})", status);
        } finally {
            conn.disconnect();
        }
    }

    private static String buildJson(String ref, Map<String, String> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ref\":\"").append(escapeJson(ref)).append("\"");
        if (inputs != null && !inputs.isEmpty()) {
            sb.append(",\"inputs\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : inputs.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                        .append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
