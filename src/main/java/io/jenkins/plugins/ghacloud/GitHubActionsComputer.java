package io.jenkins.plugins.ghacloud;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.slaves.AbstractCloudComputer;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GitHubActionsComputer extends AbstractCloudComputer<GitHubActionsAgent> implements TrackedItem {

    public GitHubActionsComputer(GitHubActionsAgent agent) {
        super(agent);
    }

    @Override
    public ProvisioningActivity.Id getId() {
        GitHubActionsAgent node = getNode();
        return node != null ? node.getId() : null;
    }

    @Extension
    public static class GitHubActionsRunActionFactory extends TransientActionFactory<Computer> {

        @Override
        public Class<Computer> type() {
            return Computer.class;
        }

        @Override
        public Collection<? extends Action> createFor(Computer target) {
            if (target instanceof GitHubActionsComputer) {
                GitHubActionsAgent agent = ((GitHubActionsComputer) target).getNode();
                if (agent != null && agent.getWorkflowRunUrl() != null) {
                    return List.of(new GitHubActionsRunAction(
                            agent.getWorkflowRunId(), agent.getWorkflowRunUrl()));
                }
            }
            return Collections.emptyList();
        }
    }
}
