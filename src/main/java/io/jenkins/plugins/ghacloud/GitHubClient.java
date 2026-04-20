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
    // Not persisted to disk — this is a transient runtime object, token is resolved from credentials at use time
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String token;

    public GitHubClient(String apiUrl, String token) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
    }

    /**
     * Represents the status of a GitHub Actions workflow run.
     */
    public static class WorkflowRunStatus {
        private final String status;
        private final String conclusion;

        public WorkflowRunStatus(String status, String conclusion) {
            this.status = status;
            this.conclusion = conclusion;
        }

        public String getStatus() {
            return status;
        }

        public String getConclusion() {
            return conclusion;
        }

        /** Returns true if the workflow run has completed (regardless of outcome). */
        public boolean isCompleted() {
            return "completed".equals(status);
        }

        /** Returns true if the workflow run completed with a failure, cancellation, or timeout. */
        public boolean isFailure() {
            return isCompleted() && conclusion != null
                    && ("failure".equals(conclusion)
                        || "cancelled".equals(conclusion)
                        || "timed_out".equals(conclusion));
        }
    }

    /**
     * Result of a workflow dispatch containing the run ID and URLs.
     */
    public static class DispatchResult {
        private final long runId;
        private final String htmlUrl;

        public DispatchResult(long runId, String htmlUrl) {
            this.runId = runId;
            this.htmlUrl = htmlUrl;
        }

        public long getRunId() {
            return runId;
        }

        public String getHtmlUrl() {
            return htmlUrl;
        }
    }

    /**
     * Triggers a workflow_dispatch event on the specified repository and workflow
     * file.
     *
     * @param repository   owner/repo
     * @param workflowFile the workflow filename (e.g. jenkins-agent.yml)
     * @param ref          git ref to run against (e.g. main)
     * @param inputs       key-value inputs forwarded to the workflow
     * @return dispatch result containing the workflow run ID and URLs
     */
    public DispatchResult triggerWorkflow(String repository, String workflowFile, String ref,
            Map<String, String> inputs) throws IOException {
        String url = apiUrl + "/repos/" + repository + "/actions/workflows/" + workflowFile + "/dispatches";
        String body = buildJson(ref, inputs);

        IOException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = doPost(url, body);
                LOGGER.log(Level.FINE, "Dispatch response: {0}", response);
                long runId = extractLong(response, "workflow_run_id");
                String htmlUrl = extractJsonString(response, "html_url");
                return new DispatchResult(runId, htmlUrl);
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

    private String doPost(String url, String body) throws IOException {
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
            if (status == 204) {
                return "";
            }
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Gets the current status of a workflow run.
     */
    public WorkflowRunStatus getWorkflowRunStatus(String repository, long runId) throws IOException {
        String url = apiUrl + "/repos/" + repository + "/actions/runs/" + runId;
        String response = doGet(url);
        String status = extractJsonString(response, "status");
        String conclusion = extractJsonString(response, "conclusion");
        return new WorkflowRunStatus(status, conclusion);
    }

    private String doGet(String url) throws IOException {
        LOGGER.log(Level.FINE, "GET {0}", url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream errStream = conn.getErrorStream();
                String error = errStream != null
                        ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8)
                        : "(no response body)";
                throw new IOException("GitHub API returned HTTP " + status + ": " + error);
            }
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static String buildJson(String ref, Map<String, String> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ref\":\"").append(escapeJson(ref)).append("\"");
        sb.append(",\"return_run_details\":true");
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

    static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return -1;
        }
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) {
            return -1;
        }
        return Long.parseLong(json.substring(start, end));
    }

    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) == 'n') {
            return null;
        }
        if (json.charAt(start) != '"') {
            return null;
        }
        start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"') {
            if (json.charAt(end) == '\\') {
                end++;
            }
            end++;
        }
        return json.substring(start, end);
    }
}
