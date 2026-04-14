package io.jenkins.plugins.ghacloud;

import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class GitHubActionsCloudTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void cloudAppearsInConfiguration() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate(
                "gha-linux", "/home/runner/agent", 1, "main", 5, null);

        GitHubActionsCloud cloud = new GitHubActionsCloud(
                "test-cloud",
                "https://api.github.com",
                "myorg/myrepo",
                "github-token-id",
                "jenkins-agent.yml",
                List.of(template));

        j.jenkins.clouds.add(cloud);

        assertNotNull(j.jenkins.getCloud("test-cloud"));
    }

    @Test
    public void canProvisionMatchingLabel() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate(
                "gha-linux", "/home/runner/agent", 1, "main", 5, null);

        GitHubActionsCloud cloud = new GitHubActionsCloud(
                "test-cloud",
                "https://api.github.com",
                "myorg/myrepo",
                "github-token-id",
                "jenkins-agent.yml",
                List.of(template));

        j.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("gha-linux"), 0)));
    }

    @Test
    public void cannotProvisionNonMatchingLabel() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate(
                "gha-linux", "/home/runner/agent", 1, "main", 5, null);

        GitHubActionsCloud cloud = new GitHubActionsCloud(
                "test-cloud",
                "https://api.github.com",
                "myorg/myrepo",
                "github-token-id",
                "jenkins-agent.yml",
                List.of(template));

        j.jenkins.clouds.add(cloud);

        assertFalse(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("windows"), 0)));
    }

    @Test
    public void templateMatchesLabel() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate(
                "gha-linux docker", "/home/runner/agent", 1, "main", 5, null);

        assertTrue(template.matches(Label.get("gha-linux")));
        assertTrue(template.matches(Label.get("docker")));
        assertFalse(template.matches(Label.get("windows")));
    }

    @Test
    public void templateDefaults() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate(
                "test", null, 0, null, 0, null);

        assertEquals("/home/runner/agent", template.getRemoteFs());
        assertEquals(1, template.getNumExecutors());
        assertEquals("main", template.getGitRef());
        assertEquals(5, template.getIdleMinutes());
    }
}
