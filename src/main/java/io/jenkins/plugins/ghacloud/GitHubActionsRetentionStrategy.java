package io.jenkins.plugins.ghacloud;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GitHubActionsRetentionStrategy extends RetentionStrategy<AbstractCloudComputer<?>> {

    private static final Logger LOGGER = Logger.getLogger(GitHubActionsRetentionStrategy.class.getName());

    private final int idleMinutes;

    @DataBoundConstructor
    public GitHubActionsRetentionStrategy(int idleMinutes) {
        this.idleMinutes = Math.max(idleMinutes, 1);
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @Override
    public long check(AbstractCloudComputer<?> c) {
        // Don't terminate agents that haven't connected yet — they're still starting up
        if (c.isOffline() && c.getConnectTime() == 0) {
            // Check if the GitHub Actions workflow has failed
            if (checkWorkflowFailed(c)) {
                return 1;
            }

            // Check if we've been waiting too long for connection (10 min max)
            long createdMs = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (createdMs > 10L * 60 * 1000) {
                LOGGER.log(Level.INFO, "Agent {0} never connected after 10 minutes, terminating",
                        c.getName());
                terminateAgent(c);
            }
            return 1;
        }

        if (c.isIdle() && c.isOnline()) {
            long idleMs = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMs > (long) idleMinutes * 60 * 1000) {
                LOGGER.log(Level.INFO, "Agent {0} has been idle for {1} minutes, terminating",
                        new Object[]{c.getName(), idleMinutes});
                terminateAgent(c);
            }
        }
        return 1; // re-check every minute
    }

    private void terminateAgent(AbstractCloudComputer<?> c) {
        try {
            AbstractCloudSlave node = (AbstractCloudSlave) c.getNode();
            if (node != null) {
                node.terminate();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while terminating agent " + c.getName(), e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate agent " + c.getName(), e);
        }
    }

    private boolean checkWorkflowFailed(AbstractCloudComputer<?> c) {
        AbstractCloudSlave node = (AbstractCloudSlave) c.getNode();
        if (!(node instanceof GitHubActionsAgent)) {
            return false;
        }
        GitHubActionsAgent agent = (GitHubActionsAgent) node;
        if (agent.getWorkflowRunId() <= 0) {
            return false;
        }
        Cloud cloud = Jenkins.get().getCloud(agent.getCloudName());
        if (!(cloud instanceof GitHubActionsCloud)) {
            return false;
        }
        GitHubActionsCloud ghaCloud = (GitHubActionsCloud) cloud;
        try {
            GitHubClient client = new GitHubClient(
                    ghaCloud.getGitHubApiUrl(), ghaCloud.resolveGitHubToken());
            GitHubClient.WorkflowRunStatus status = client.getWorkflowRunStatus(
                    ghaCloud.getRepository(), agent.getWorkflowRunId());
            if (status.isFailure()) {
                LOGGER.log(Level.WARNING,
                        "GitHub Actions workflow run {0} for agent {1} concluded with: {2}",
                        new Object[]{agent.getWorkflowRunId(), c.getName(), status.getConclusion()});
                terminateAgent(c);
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE,
                    "Failed to check workflow run status for agent " + c.getName(), e);
        }
        return false;
    }

    @Override
    public void start(AbstractCloudComputer<?> c) {
        c.connect(false);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return "GitHub Actions Cloud Retention Strategy";
        }
    }
}
