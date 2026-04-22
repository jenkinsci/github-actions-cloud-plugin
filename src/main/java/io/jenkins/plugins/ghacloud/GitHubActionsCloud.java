package io.jenkins.plugins.ghacloud;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GitHubActionsCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(GitHubActionsCloud.class.getName());

    private String gitHubApiUrl = "https://api.github.com";
    private final String repository;
    private final String credentialsId;
    private int maxAgents;
    private final List<GitHubActionsAgentTemplate> templates;

    @DataBoundConstructor
    public GitHubActionsCloud(String name, String repository,
                              String credentialsId,
                              List<GitHubActionsAgentTemplate> templates) {
        super(name);
        this.repository = repository;
        this.credentialsId = credentialsId;
        this.templates = templates != null ? new ArrayList<>(templates) : new ArrayList<>();
    }

    public String getGitHubApiUrl() {
        return gitHubApiUrl;
    }

    @DataBoundSetter
    public void setGitHubApiUrl(String gitHubApiUrl) {
        this.gitHubApiUrl = (gitHubApiUrl != null && !gitHubApiUrl.isEmpty())
                ? gitHubApiUrl : "https://api.github.com";
    }

    public String getRepository() {
        return repository;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public int getMaxAgents() {
        return maxAgents;
    }

    @DataBoundSetter
    public void setMaxAgents(int maxAgents) {
        this.maxAgents = maxAgents;
    }

    public List<GitHubActionsAgentTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @Override
    public boolean canProvision(CloudState state) {
        Label label = state.getLabel();
        return templates.stream().anyMatch(t -> t.matches(label));
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        // Count agents from this cloud
        int pendingCount = 0;
        int totalCount = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof GitHubActionsAgent) {
                GitHubActionsAgent agent = (GitHubActionsAgent) node;
                if (name.equals(agent.getCloudName())) {
                    totalCount++;
                    hudson.model.Computer computer = agent.toComputer();
                    if (computer == null || computer.isOffline()) {
                        pendingCount++;
                    }
                }
            }
        }

        if (maxAgents > 0 && totalCount >= maxAgents) {
            LOGGER.log(Level.FINE,
                    "Max agents ({0}) reached for cloud {1}, skipping provisioning",
                    new Object[]{maxAgents, name});
            return plannedNodes;
        }

        int toProvision = Math.max(0, excessWorkload - pendingCount);
        if (toProvision == 0) {
            LOGGER.log(Level.FINE,
                    "Skipping provisioning: {0} agents already pending for cloud {1}",
                    new Object[]{pendingCount, name});
            return plannedNodes;
        }

        // Only provision one agent at a time to avoid stampede
        toProvision = 1;

        for (int i = 0; i < toProvision; i++) {
            GitHubActionsAgentTemplate template = templates.stream()
                    .filter(t -> t.matches(label))
                    .findFirst()
                    .orElse(null);

            if (template == null) {
                break;
            }

            // Check per-template agent cap
            if (template.getMaxAgents() > 0) {
                long templateAgentCount = Jenkins.get().getNodes().stream()
                        .filter(n -> n instanceof GitHubActionsAgent)
                        .map(n -> (GitHubActionsAgent) n)
                        .filter(a -> name.equals(a.getCloudName()) && template.matches(a))
                        .count();
                if (templateAgentCount >= template.getMaxAgents()) {
                    LOGGER.log(Level.FINE,
                            "Template max agents ({0}) reached for template ''{1}'', skipping",
                            new Object[]{template.getMaxAgents(), template.getTemplateName()});
                    break;
                }
            }

            String agentName = template.getTemplateName() + "-" + UUID.randomUUID().toString().substring(0, 8);

            ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(
                    this.name, template.getTemplateName(), agentName);

            CompletableFuture<Node> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return provisionAgent(agentName, template, provisioningId);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to provision GitHub Actions agent: " + agentName, e);
                }
            });

            plannedNodes.add(new TrackedPlannedNode(provisioningId, template.getNumExecutors(), future));
        }

        return plannedNodes;
    }

    private GitHubActionsAgent provisionAgent(String agentName, GitHubActionsAgentTemplate template,
                                                ProvisioningActivity.Id provisioningId)
            throws Descriptor.FormException, IOException {
        LOGGER.log(Level.FINE, "Provisioning GitHub Actions agent: {0}", agentName);

        int idleMinutes = template.isOneShot() ? 1 : template.getIdleMinutes();

        GitHubActionsAgent agent = new GitHubActionsAgent(
                agentName,
                template.getRemoteFs(),
                template.getLabelString(),
                template.getNumExecutors(),
                idleMinutes,
                name,
                template.isOneShot()
        );
        agent.setProvisioningId(provisioningId);

        Jenkins jenkins = Jenkins.get();
        jenkins.addNode(agent);

        // Retrieve the agent secret for inbound connection
        hudson.model.Computer computer = jenkins.getComputer(agentName);
        String secret = "";
        if (computer instanceof hudson.slaves.SlaveComputer) {
            secret = ((hudson.slaves.SlaveComputer) computer).getJnlpMac();
        }

        String jenkinsUrl = jenkins.getRootUrl();
        if (jenkinsUrl == null || jenkinsUrl.isEmpty()) {
            throw new IOException("Jenkins root URL is not configured. "
                    + "Set it in Manage Jenkins > System > Jenkins URL.");
        }

        // Trigger the GitHub Actions workflow
        String token = resolveGitHubToken();
        GitHubClient client = new GitHubClient(gitHubApiUrl, token);

        Map<String, String> inputs = new HashMap<>();
        inputs.put("jenkins_url", jenkinsUrl);
        inputs.put("agent_name", agentName);
        inputs.put("agent_secret", secret);

        GitHubClient.DispatchResult dispatch = client.triggerWorkflow(
                repository, template.getWorkflowFileName(), template.getGitRef(), inputs);
        agent.setWorkflowRunId(dispatch.getRunId());
        agent.setWorkflowRunUrl(dispatch.getHtmlUrl());
        agent.setNodeDescription(dispatch.getHtmlUrl() != null
                ? "GitHub Actions run: " + dispatch.getHtmlUrl()
                : "GitHub Actions agent");
        LOGGER.log(Level.FINE, "Triggered GitHub Actions workflow {0} for agent {1} (run ID: {2})",
                new Object[]{template.getWorkflowFileName(), agentName, dispatch.getRunId()});

        return agent;
    }

    String resolveGitHubToken() {
        StringCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId)
        );
        if (creds == null) {
            throw new IllegalStateException("GitHub credentials not found for ID: " + credentialsId);
        }
        return creds.getSecret().getPlainText();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "GitHub Actions";
        }

        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Cloud name is required");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckRepository(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Repository is required (format: owner/repo)");
            }
            if (!value.contains("/") || value.split("/").length != 2) {
                return FormValidation.error("Repository must be in format: owner/repo");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillGitHubApiUrlItems(@QueryParameter String gitHubApiUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel model = new ListBoxModel();
            model.add("GitHub (https://api.github.com)", "https://api.github.com");
            if (Jenkins.get().getPlugin("github") != null) {
                GitHubEnterpriseHelper.addServers(model);
            }
            return model;
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            jenkins,
                            StringCredentials.class,
                            URIRequirementBuilder.fromUri("https://api.github.com").build(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(credentialsId);
        }
    }
}
