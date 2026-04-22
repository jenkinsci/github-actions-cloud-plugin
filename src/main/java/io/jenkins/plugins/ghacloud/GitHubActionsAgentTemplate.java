package io.jenkins.plugins.ghacloud;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.Set;

import hudson.model.Describable;

public class GitHubActionsAgentTemplate implements Describable<GitHubActionsAgentTemplate> {

    private String templateName;
    private String labelString;
    /** Retained for migration only — populated when deserializing old XML that had namePrefix. */
    @SuppressWarnings("unused")
    private String namePrefix;
    private String remoteFs = "/home/runner/agent";
    private int numExecutors = 1;
    private String gitRef = "main";
    private int idleMinutes = 5;
    private String workflowFileName;
    private int maxAgents;
    private boolean oneShot;

    @DataBoundConstructor
    public GitHubActionsAgentTemplate(String templateName, String labelString) {
        if (templateName == null || templateName.trim().isEmpty()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        this.templateName = templateName.trim();
        this.labelString = labelString;
    }

    /**
     * Migration: when loading old XML that has no {@code templateName}, fall back to
     * {@code namePrefix} (the old field) first, then to the label string.
     */
    private Object readResolve() {
        if (templateName == null || templateName.trim().isEmpty()) {
            if (namePrefix != null && !namePrefix.trim().isEmpty()) {
                templateName = namePrefix.trim();
            } else if (labelString != null && !labelString.trim().isEmpty()) {
                templateName = labelString.trim();
            } else {
                templateName = "template";
            }
        }
        return this;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getLabelString() {
        return labelString;
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    @DataBoundSetter
    public void setRemoteFs(String remoteFs) {
        this.remoteFs = (remoteFs != null && !remoteFs.isEmpty()) ? remoteFs : "/home/runner/agent";
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    @DataBoundSetter
    public void setNumExecutors(int numExecutors) {
        this.numExecutors = numExecutors > 0 ? numExecutors : 1;
    }

    public String getGitRef() {
        return gitRef;
    }

    @DataBoundSetter
    public void setGitRef(String gitRef) {
        this.gitRef = (gitRef != null && !gitRef.isEmpty()) ? gitRef : "main";
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : 5;
    }

    public String getWorkflowFileName() {
        return workflowFileName;
    }

    @DataBoundSetter
    public void setWorkflowFileName(String workflowFileName) {
        this.workflowFileName = workflowFileName;
    }

    public int getMaxAgents() {
        return maxAgents;
    }

    @DataBoundSetter
    public void setMaxAgents(int maxAgents) {
        this.maxAgents = maxAgents;
    }

    public boolean isOneShot() {
        return oneShot;
    }

    @DataBoundSetter
    public void setOneShot(boolean oneShot) {
        this.oneShot = oneShot;
    }

    public boolean matches(Label label) {
        if (label == null) {
            return true;
        }
        if (labelString == null || labelString.isEmpty()) {
            return false;
        }
        Set<LabelAtom> labelAtoms = Label.parse(labelString);
        return label.matches(labelAtoms);
    }

    public boolean matches(GitHubActionsAgent agent) {
        if (labelString == null || labelString.isEmpty()) {
            return true;
        }
        Set<LabelAtom> templateLabels = Label.parse(labelString);
        Set<LabelAtom> agentLabels = agent.getAssignedLabels().stream()
                .filter(l -> l instanceof LabelAtom)
                .map(l -> (LabelAtom) l)
                .collect(java.util.stream.Collectors.toSet());
        return agentLabels.containsAll(templateLabels);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<GitHubActionsAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "GitHub Actions Agent Template";
        }

        @RequirePOST
        public FormValidation doCheckTemplateName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Template name is required");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckLabelString(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.warning("No labels set — this template will match any label request");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckWorkflowFileName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Workflow file name is required (e.g. jenkins-agent.yml)");
            }
            return FormValidation.ok();
        }
    }
}
