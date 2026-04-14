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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

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

    private final String githubApiUrl;
    private final String repository;
    private final String credentialsId;
    private final String workflowFileName;
    private final List<GitHubActionsAgentTemplate> templates;

    @DataBoundConstructor
    public GitHubActionsCloud(String name, String githubApiUrl, String repository,
                              String credentialsId, String workflowFileName,
                              List<GitHubActionsAgentTemplate> templates) {
        super(name);
        this.githubApiUrl = (githubApiUrl != null && !githubApiUrl.isEmpty())
                ? githubApiUrl : "https://api.github.com";
        this.repository = repository;
        this.credentialsId = credentialsId;
        this.workflowFileName = workflowFileName;
        this.templates = templates != null ? new ArrayList<>(templates) : new ArrayList<>();
    }

    public String getGithubApiUrl() {
        return githubApiUrl;
    }

    public String getRepository() {
        return repository;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getWorkflowFileName() {
        return workflowFileName;
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

        // Count already-provisioned agents from this cloud that haven't connected yet
        int pendingCount = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof GitHubActionsSlave) {
                GitHubActionsSlave slave = (GitHubActionsSlave) node;
                if (name.equals(slave.getCloudName())) {
                    hudson.model.Computer computer = slave.toComputer();
                    if (computer == null || computer.isOffline()) {
                        pendingCount++;
                    }
                }
            }
        }

        int toProvision = Math.max(0, excessWorkload - pendingCount);
        if (toProvision == 0) {
            LOGGER.log(Level.INFO,
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

            String agentName = name + "-" + UUID.randomUUID().toString().substring(0, 8);

            CompletableFuture<Node> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return provisionAgent(agentName, template);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to provision GitHub Actions agent: " + agentName, e);
                }
            });

            plannedNodes.add(new NodeProvisioner.PlannedNode(agentName, future, template.getNumExecutors()));
        }

        return plannedNodes;
    }

    private GitHubActionsSlave provisionAgent(String agentName, GitHubActionsAgentTemplate template)
            throws Descriptor.FormException, IOException {
        LOGGER.log(Level.INFO, "Provisioning GitHub Actions agent: {0}", agentName);

        GitHubActionsSlave slave = new GitHubActionsSlave(
                agentName,
                template.getRemoteFs(),
                template.getLabelString(),
                template.getNumExecutors(),
                template.getIdleMinutes(),
                name
        );

        Jenkins jenkins = Jenkins.get();
        jenkins.addNode(slave);

        // Retrieve the JNLP secret for inbound connection
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
        GitHubClient client = new GitHubClient(githubApiUrl, token);

        Map<String, String> inputs = new HashMap<>();
        inputs.put("jenkins_url", jenkinsUrl);
        inputs.put("agent_name", agentName);
        inputs.put("agent_secret", secret);

        // Use template-level workflow file if set, otherwise fall back to cloud-level
        String workflow = (template.getWorkflowFileName() != null && !template.getWorkflowFileName().isEmpty())
                ? template.getWorkflowFileName() : workflowFileName;

        client.triggerWorkflow(repository, workflow, template.getGitRef(), inputs);
        LOGGER.log(Level.INFO, "Triggered GitHub Actions workflow {0} for agent: {1}",
                new Object[]{workflow, agentName});

        return slave;
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
            return "GitHub Actions Cloud";
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Cloud name is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRepository(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Repository is required (format: owner/repo)");
            }
            if (!value.contains("/") || value.split("/").length != 2) {
                return FormValidation.error("Repository must be in format: owner/repo");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckWorkflowFileName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Workflow file name is required (e.g. jenkins-agent.yml)");
            }
            return FormValidation.ok();
        }

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
