package io.jenkins.plugins.ghacloud;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Set;

import hudson.model.AbstractDescribableImpl;

public class GitHubActionsAgentTemplate extends AbstractDescribableImpl<GitHubActionsAgentTemplate> {

    private final String labelString;
    private final String remoteFs;
    private final int numExecutors;
    private final String gitRef;
    private final int idleMinutes;
    private final String workflowFileName;

    @DataBoundConstructor
    public GitHubActionsAgentTemplate(String labelString, String remoteFs,
                                      int numExecutors, String gitRef, int idleMinutes,
                                      String workflowFileName) {
        this.labelString = labelString;
        this.remoteFs = (remoteFs != null && !remoteFs.isEmpty()) ? remoteFs : "/home/runner/agent";
        this.numExecutors = numExecutors > 0 ? numExecutors : 1;
        this.gitRef = (gitRef != null && !gitRef.isEmpty()) ? gitRef : "main";
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : 5;
        this.workflowFileName = workflowFileName;
    }

    public String getLabelString() {
        return labelString;
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public String getGitRef() {
        return gitRef;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public String getWorkflowFileName() {
        return workflowFileName;
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

    @Extension
    public static class DescriptorImpl extends Descriptor<GitHubActionsAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "GitHub Actions Agent Template";
        }

        public FormValidation doCheckLabelString(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.warning("No labels set — this template will match any label request");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String value) {
            try {
                int n = Integer.parseInt(value);
                if (n < 1) {
                    return FormValidation.error("Must be at least 1");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Must be a number");
            }
            return FormValidation.ok();
        }
    }
}
