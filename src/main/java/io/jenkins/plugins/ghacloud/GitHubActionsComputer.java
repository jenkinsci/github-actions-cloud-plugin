package io.jenkins.plugins.ghacloud;

import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

public class GitHubActionsComputer extends AbstractCloudComputer<GitHubActionsAgent> implements TrackedItem {

    public GitHubActionsComputer(GitHubActionsAgent agent) {
        super(agent);
    }

    @Override
    public ProvisioningActivity.Id getId() {
        GitHubActionsAgent node = getNode();
        return node != null ? node.getId() : null;
    }
}
