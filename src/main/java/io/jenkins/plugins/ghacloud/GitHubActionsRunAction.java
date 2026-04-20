package io.jenkins.plugins.ghacloud;

import hudson.model.Action;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;

/**
 * Action that adds a "GitHub Actions Run" link to the agent's sidebar.
 * Clicking it redirects to the workflow run on GitHub.
 */
public class GitHubActionsRunAction implements Action {

    private final long runId;
    private final String url;

    public GitHubActionsRunAction(long runId, String url) {
        this.runId = runId;
        this.url = url;
    }

    public long getRunId() {
        return runId;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String getIconFileName() {
        return "symbol-arrow-right";
    }

    @Override
    public String getDisplayName() {
        return "GitHub Actions Run #" + runId;
    }

    @Override
    public String getUrlName() {
        return "github-actions-run";
    }

    // lgtm[jenkins/no-permission-check] lgtm[jenkins/csrf] - read-only redirect to public GitHub URL, no side effects
    public HttpResponse doIndex() {
        return HttpResponses.redirectTo(url);
    }
}
