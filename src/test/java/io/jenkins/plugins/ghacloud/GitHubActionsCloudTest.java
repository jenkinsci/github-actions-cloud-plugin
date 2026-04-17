package io.jenkins.plugins.ghacloud;

import hudson.model.Label;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.*;

public class GitHubActionsCloudTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private GitHubActionsAgentTemplate createTemplate(String labels, String workflowFile) {
        GitHubActionsAgentTemplate t = new GitHubActionsAgentTemplate(labels);
        t.setWorkflowFileName(workflowFile);
        return t;
    }

    private GitHubActionsCloud createCloud(String name, String repo, GitHubActionsAgentTemplate... templates) {
        return new GitHubActionsCloud(name, repo, "github-token-id", List.of(templates));
    }

    @Test
    public void cloudAppearsInConfiguration() {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);

        j.jenkins.clouds.add(cloud);

        assertNotNull(j.jenkins.getCloud("test-cloud"));
    }

    @Test
    public void canProvisionMatchingLabel() {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);

        j.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("gha-linux"), 0)));
    }

    @Test
    public void cannotProvisionNonMatchingLabel() {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);

        j.jenkins.clouds.add(cloud);

        assertFalse(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("windows"), 0)));
    }

    @Test
    public void templateMatchesLabel() {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux docker", "jenkins-agent.yml");

        assertTrue(template.matches(Label.get("gha-linux")));
        assertTrue(template.matches(Label.get("docker")));
        assertFalse(template.matches(Label.get("windows")));
    }

    @Test
    public void templateDefaults() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("test");

        assertEquals("/home/runner/agent", template.getRemoteFs());
        assertEquals(1, template.getNumExecutors());
        assertEquals("main", template.getGitRef());
        assertEquals(5, template.getIdleMinutes());
        assertEquals(0, template.getMaxAgents());
    }
}
