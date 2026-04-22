package io.jenkins.plugins.ghacloud;

import hudson.model.Label;
import hudson.model.Node;
import hudson.util.FormValidation;
import hudson.util.XStream2;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
public class GitHubActionsCloudTest {

    private GitHubActionsAgentTemplate createTemplate(String labels, String workflowFile) {
        GitHubActionsAgentTemplate t = new GitHubActionsAgentTemplate("test-template", labels);
        t.setWorkflowFileName(workflowFile);
        return t;
    }

    private GitHubActionsCloud createCloud(String name, String repo, GitHubActionsAgentTemplate... templates) {
        return new GitHubActionsCloud(name, repo, "github-token-id", List.of(templates));
    }

    // --- Cloud configuration tests ---

    @Test
    public void cloudAppearsInConfiguration(JenkinsRule j) {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);

        j.jenkins.clouds.add(cloud);

        assertNotNull(j.jenkins.getCloud("test-cloud"));
    }

    @Test
    public void cloudDefaultApiUrl() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        assertEquals("https://api.github.com", cloud.getGitHubApiUrl());
    }

    @Test
    public void cloudCustomApiUrl() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        cloud.setGitHubApiUrl("https://github.example.com/api/v3");
        assertEquals("https://github.example.com/api/v3", cloud.getGitHubApiUrl());
    }

    @Test
    public void cloudBlankApiUrlFallsBackToDefault() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        cloud.setGitHubApiUrl("");
        assertEquals("https://api.github.com", cloud.getGitHubApiUrl());
    }

    @Test
    public void cloudNullApiUrlFallsBackToDefault() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        cloud.setGitHubApiUrl(null);
        assertEquals("https://api.github.com", cloud.getGitHubApiUrl());
    }

    @Test
    public void cloudMaxAgentsDefault() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        assertEquals(0, cloud.getMaxAgents());
    }

    @Test
    public void cloudMaxAgentsSetter() {
        GitHubActionsCloud cloud = createCloud("test", "owner/repo");
        cloud.setMaxAgents(10);
        assertEquals(10, cloud.getMaxAgents());
    }

    @Test
    public void cloudTemplatesAreUnmodifiable() {
        GitHubActionsAgentTemplate template = createTemplate("linux", "agent.yml");
        GitHubActionsCloud cloud = createCloud("test", "owner/repo", template);
        assertThrows(UnsupportedOperationException.class, () -> cloud.getTemplates().add(template));
    }

    @Test
    public void cloudNullTemplatesBecomesEmptyList() {
        GitHubActionsCloud cloud = new GitHubActionsCloud("test", "owner/repo", "cred-id", null);
        assertNotNull(cloud.getTemplates());
        assertTrue(cloud.getTemplates().isEmpty());
    }

    @Test
    public void cloudGetters() {
        GitHubActionsCloud cloud = createCloud("my-cloud", "myorg/myrepo");
        assertEquals("myorg/myrepo", cloud.getRepository());
        assertEquals("github-token-id", cloud.getCredentialsId());
    }

    // --- Provisioning label matching tests ---

    @Test
    public void canProvisionMatchingLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);
        j.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("gha-linux"), 0)));
    }

    @Test
    public void cannotProvisionNonMatchingLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "jenkins-agent.yml");
        GitHubActionsCloud cloud = createCloud("test-cloud", "myorg/myrepo", template);
        j.jenkins.clouds.add(cloud);

        assertFalse(cloud.canProvision(new hudson.slaves.Cloud.CloudState(
                Label.get("windows"), 0)));
    }

    @Test
    public void canProvisionWithMultipleTemplates(JenkinsRule j) {
        GitHubActionsAgentTemplate linux = createTemplate("gha-linux", "linux.yml");
        GitHubActionsAgentTemplate windows = createTemplate("gha-windows", "windows.yml");
        GitHubActionsCloud cloud = createCloud("test", "owner/repo", linux, windows);
        j.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get("gha-linux"), 0)));
        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get("gha-windows"), 0)));
        assertFalse(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get("gha-macos"), 0)));
    }

    @Test
    public void canProvisionNullLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "agent.yml");
        GitHubActionsCloud cloud = createCloud("test", "owner/repo", template);
        j.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(null, 0)));
    }

    // --- Template tests ---

    @Test
    public void templateMatchesLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux docker", "jenkins-agent.yml");

        assertTrue(template.matches(Label.get("gha-linux")));
        assertTrue(template.matches(Label.get("docker")));
        assertFalse(template.matches(Label.get("windows")));
    }

    @Test
    public void templateMatchesNullLabel() {
        GitHubActionsAgentTemplate template = createTemplate("gha-linux", "agent.yml");
        assertTrue(template.matches((Label) null));
    }

    @Test
    public void templateEmptyLabelsDoNotMatchLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "");
        assertFalse(template.matches(Label.get("anything")));
    }

    @Test
    public void templateNullLabelsDoNotMatchLabel(JenkinsRule j) {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", null);
        assertFalse(template.matches(Label.get("anything")));
    }

    @Test
    public void templateDefaults() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "gha-linux");

        assertEquals("my-template", template.getTemplateName());
        assertEquals("/home/runner/agent", template.getRemoteFs());
        assertEquals(1, template.getNumExecutors());
        assertEquals("main", template.getGitRef());
        assertEquals(5, template.getIdleMinutes());
        assertEquals(0, template.getMaxAgents());
        assertNull(template.getWorkflowFileName());
    }

    @Test
    public void templateSetters() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "gha-linux");
        template.setRemoteFs("/custom/path");
        template.setNumExecutors(4);
        template.setGitRef("develop");
        template.setIdleMinutes(10);
        template.setWorkflowFileName("custom.yml");
        template.setMaxAgents(5);

        assertEquals("/custom/path", template.getRemoteFs());
        assertEquals(4, template.getNumExecutors());
        assertEquals("develop", template.getGitRef());
        assertEquals(10, template.getIdleMinutes());
        assertEquals("custom.yml", template.getWorkflowFileName());
        assertEquals(5, template.getMaxAgents());
    }

    @Test
    public void templateSettersHandleInvalidValues() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "gha-linux");

        template.setRemoteFs("");
        assertEquals("/home/runner/agent", template.getRemoteFs());

        template.setRemoteFs(null);
        assertEquals("/home/runner/agent", template.getRemoteFs());

        template.setNumExecutors(0);
        assertEquals(1, template.getNumExecutors());

        template.setNumExecutors(-1);
        assertEquals(1, template.getNumExecutors());

        template.setGitRef("");
        assertEquals("main", template.getGitRef());

        template.setGitRef(null);
        assertEquals("main", template.getGitRef());

        template.setIdleMinutes(0);
        assertEquals(5, template.getIdleMinutes());

        template.setIdleMinutes(-1);
        assertEquals(5, template.getIdleMinutes());
    }

    // --- Agent tests ---

    @Test
    public void agentCreation(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", false);

        assertEquals("test-agent", agent.getNodeName());
        assertEquals("/tmp/agent", agent.getRemoteFS());
        assertEquals("my-cloud", agent.getCloudName());
        assertEquals(Node.Mode.EXCLUSIVE, agent.getMode());
        assertEquals(1, agent.getNumExecutors());
    }

    @Test
    public void agentCreatesComputer(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", false);

        assertNotNull(agent.createComputer());
        assertTrue(agent.createComputer() instanceof GitHubActionsComputer);
    }

    @Test
    public void agentDescriptorNotInstantiable() {
        GitHubActionsAgent.DescriptorImpl descriptor = new GitHubActionsAgent.DescriptorImpl();
        assertFalse(descriptor.isInstantiable());
    }

    // --- Retention strategy tests ---

    @Test
    public void retentionStrategyMinimumIdleMinutes() {
        GitHubActionsRetentionStrategy strategy = new GitHubActionsRetentionStrategy(0, false);
        assertEquals(1, strategy.getIdleMinutes());

        GitHubActionsRetentionStrategy strategy2 = new GitHubActionsRetentionStrategy(-5, false);
        assertEquals(1, strategy2.getIdleMinutes());
    }

    @Test
    public void retentionStrategyIdleMinutes() {
        GitHubActionsRetentionStrategy strategy = new GitHubActionsRetentionStrategy(10, false);
        assertEquals(10, strategy.getIdleMinutes());
    }

    // --- Form validation tests ---

    @Test
    public void cloudDescriptorValidatesEmptyName(JenkinsRule j) {
        GitHubActionsCloud.DescriptorImpl descriptor = new GitHubActionsCloud.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckName("my-cloud").kind);
    }

    @Test
    public void cloudDescriptorValidatesRepository(JenkinsRule j) {
        GitHubActionsCloud.DescriptorImpl descriptor = new GitHubActionsCloud.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRepository("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRepository(null).kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRepository("noslash").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRepository("too/many/parts").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRepository("owner/repo").kind);
    }

    @Test
    public void templateDescriptorValidatesTemplateName(JenkinsRule j) {
        GitHubActionsAgentTemplate.DescriptorImpl descriptor = new GitHubActionsAgentTemplate.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTemplateName("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTemplateName(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckTemplateName("linux-builder").kind);
    }

    @Test
    public void templateDescriptorValidatesLabelString(JenkinsRule j) {
        GitHubActionsAgentTemplate.DescriptorImpl descriptor = new GitHubActionsAgentTemplate.DescriptorImpl();
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckLabelString("").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckLabelString(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckLabelString("gha-linux").kind);
    }

    @Test
    public void templateDescriptorValidatesWorkflowFileName(JenkinsRule j) {
        GitHubActionsAgentTemplate.DescriptorImpl descriptor = new GitHubActionsAgentTemplate.DescriptorImpl();
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckWorkflowFileName("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckWorkflowFileName(null).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckWorkflowFileName("agent.yml").kind);
    }

    // --- Display name tests ---

    @Test
    public void cloudDisplayName() {
        GitHubActionsCloud.DescriptorImpl descriptor = new GitHubActionsCloud.DescriptorImpl();
        assertEquals("GitHub Actions", descriptor.getDisplayName());
    }

    @Test
    public void agentDisplayName() {
        GitHubActionsAgent.DescriptorImpl descriptor = new GitHubActionsAgent.DescriptorImpl();
        assertEquals("GitHub Actions Agent", descriptor.getDisplayName());
    }

    @Test
    public void templateDisplayName() {
        GitHubActionsAgentTemplate.DescriptorImpl descriptor = new GitHubActionsAgentTemplate.DescriptorImpl();
        assertEquals("GitHub Actions Agent Template", descriptor.getDisplayName());
    }

    // --- GitHubClient tests ---

    @Test
    public void gitHubClientStripsTrailingSlash() {
        GitHubClient client = new GitHubClient("https://api.github.com/", "token");
        assertNotNull(client);
    }

    // --- WorkflowRunStatus tests ---

    @Test
    public void workflowRunStatusCompletedSuccess() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("completed", "success");
        assertTrue(status.isCompleted());
        assertFalse(status.isFailure());
        assertEquals("completed", status.getStatus());
        assertEquals("success", status.getConclusion());
    }

    @Test
    public void workflowRunStatusFailure() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("completed", "failure");
        assertTrue(status.isCompleted());
        assertTrue(status.isFailure());
    }

    @Test
    public void workflowRunStatusCancelled() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("completed", "cancelled");
        assertTrue(status.isFailure());
    }

    @Test
    public void workflowRunStatusTimedOut() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("completed", "timed_out");
        assertTrue(status.isFailure());
    }

    @Test
    public void workflowRunStatusInProgress() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("in_progress", null);
        assertFalse(status.isCompleted());
        assertFalse(status.isFailure());
    }

    @Test
    public void workflowRunStatusQueued() {
        GitHubClient.WorkflowRunStatus status = new GitHubClient.WorkflowRunStatus("queued", null);
        assertFalse(status.isCompleted());
        assertFalse(status.isFailure());
    }

    // --- JSON extraction tests ---

    @Test
    public void extractLongFromJson() {
        String json = "{\"workflow_run_id\":12345,\"run_url\":\"https://example.com\"}";
        assertEquals(12345L, GitHubClient.extractLong(json, "workflow_run_id"));
    }

    @Test
    public void extractLongMissingKey() {
        assertEquals(-1L, GitHubClient.extractLong("{\"other\":1}", "workflow_run_id"));
    }

    @Test
    public void extractLongWithWhitespace() {
        String json = "{\"workflow_run_id\": 67890}";
        assertEquals(67890L, GitHubClient.extractLong(json, "workflow_run_id"));
    }

    @Test
    public void extractJsonStringValue() {
        String json = "{\"status\":\"completed\",\"conclusion\":\"failure\"}";
        assertEquals("completed", GitHubClient.extractJsonString(json, "status"));
        assertEquals("failure", GitHubClient.extractJsonString(json, "conclusion"));
    }

    @Test
    public void extractJsonStringNull() {
        String json = "{\"status\":\"in_progress\",\"conclusion\":null}";
        assertEquals("in_progress", GitHubClient.extractJsonString(json, "status"));
        assertNull(GitHubClient.extractJsonString(json, "conclusion"));
    }

    @Test
    public void extractJsonStringMissingKey() {
        assertNull(GitHubClient.extractJsonString("{\"other\":\"value\"}", "status"));
    }

    // --- Agent workflow run ID tests ---

    @Test
    public void agentWorkflowRunId(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", false);
        assertEquals(0L, agent.getWorkflowRunId());
        agent.setWorkflowRunId(12345L);
        assertEquals(12345L, agent.getWorkflowRunId());
    }

    @Test
    public void agentWorkflowRunUrl(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", false);
        assertNull(agent.getWorkflowRunUrl());
        agent.setWorkflowRunUrl("https://github.com/myorg/myrepo/actions/runs/12345");
        assertEquals("https://github.com/myorg/myrepo/actions/runs/12345", agent.getWorkflowRunUrl());
    }

    // --- readResolve migration tests ---

    @Test
    public void migrationFallsBackToNamePrefix() {
        String xml = "<io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>" +
                     "<namePrefix>my-prefix</namePrefix>" +
                     "<labelString>gha-linux</labelString>" +
                     "</io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>";
        XStream2 xs = new XStream2();
        GitHubActionsAgentTemplate t = (GitHubActionsAgentTemplate) xs.fromXML(xml);
        assertEquals("my-prefix", t.getTemplateName());
        assertEquals("gha-linux", t.getLabelString());
    }

    @Test
    public void migrationFallsBackToLabelString() {
        String xml = "<io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>" +
                     "<labelString>gha-linux</labelString>" +
                     "</io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>";
        XStream2 xs = new XStream2();
        GitHubActionsAgentTemplate t = (GitHubActionsAgentTemplate) xs.fromXML(xml);
        assertEquals("gha-linux", t.getTemplateName());
    }

    @Test
    public void migrationFallsBackToDefaultWhenBothEmpty() {
        String xml = "<io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>" +
                     "<labelString></labelString>" +
                     "</io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>";
        XStream2 xs = new XStream2();
        GitHubActionsAgentTemplate t = (GitHubActionsAgentTemplate) xs.fromXML(xml);
        assertEquals("template", t.getTemplateName());
    }

    @Test
    public void migrationPrefersNamePrefixOverLabelString() {
        String xml = "<io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>" +
                     "<namePrefix>explicit-prefix</namePrefix>" +
                     "<labelString>gha-linux docker</labelString>" +
                     "</io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>";
        XStream2 xs = new XStream2();
        GitHubActionsAgentTemplate t = (GitHubActionsAgentTemplate) xs.fromXML(xml);
        assertEquals("explicit-prefix", t.getTemplateName());
    }

    @Test
    public void existingTemplateNamePreservedDuringMigration() {
        String xml = "<io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>" +
                     "<templateName>already-set</templateName>" +
                     "<namePrefix>old-prefix</namePrefix>" +
                     "<labelString>gha-linux</labelString>" +
                     "</io.jenkins.plugins.ghacloud.GitHubActionsAgentTemplate>";
        XStream2 xs = new XStream2();
        GitHubActionsAgentTemplate t = (GitHubActionsAgentTemplate) xs.fromXML(xml);
        assertEquals("already-set", t.getTemplateName());
    }

    @Test
    public void constructorRejectsBlankTemplateName() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubActionsAgentTemplate("", "gha-linux"));
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubActionsAgentTemplate("  ", "gha-linux"));
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubActionsAgentTemplate(null, "gha-linux"));
    }

    // --- DispatchResult tests ---

    @Test
    public void dispatchResultFields() {
        GitHubClient.DispatchResult result = new GitHubClient.DispatchResult(
                42L, "https://github.com/org/repo/actions/runs/42");
        assertEquals(42L, result.getRunId());
        assertEquals("https://github.com/org/repo/actions/runs/42", result.getHtmlUrl());
    }

    // --- One-shot agent tests ---

    @Test
    public void templateOneShotDefaultFalse() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "gha-linux");
        assertFalse(template.isOneShot());
    }

    @Test
    public void templateOneShotSetter() {
        GitHubActionsAgentTemplate template = new GitHubActionsAgentTemplate("my-template", "gha-linux");
        template.setOneShot(true);
        assertTrue(template.isOneShot());
    }

    @Test
    public void agentOneShotFlag(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", true);
        assertTrue(agent.isOneShot());
    }

    @Test
    public void agentNotOneShotByDefault(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "test-agent", "/tmp/agent", "gha-linux", 1, 5, "my-cloud", false);
        assertFalse(agent.isOneShot());
    }

    @Test
    public void retentionStrategyOneShotFlag() {
        GitHubActionsRetentionStrategy strategy = new GitHubActionsRetentionStrategy(5, true);
        assertTrue(strategy.isOneShot());
        assertEquals(5, strategy.getIdleMinutes());
    }

    @Test
    public void retentionStrategyNotOneShotByDefault() {
        GitHubActionsRetentionStrategy strategy = new GitHubActionsRetentionStrategy(5, false);
        assertFalse(strategy.isOneShot());
    }
}
