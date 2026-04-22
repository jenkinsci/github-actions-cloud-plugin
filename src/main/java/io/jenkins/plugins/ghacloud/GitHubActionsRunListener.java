package io.jenkins.plugins.ghacloud;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the Jenkins job name to the GitHub Actions step summary
 * when a build starts on a GHA agent.
 */
@Extension
public class GitHubActionsRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(GitHubActionsRunListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        hudson.model.Executor executor = run.getExecutor();
        if (executor == null) {
            return;
        }
        String builtOn = executor.getOwner().getName();
        if (builtOn == null || builtOn.isEmpty()) {
            return;
        }

        hudson.model.Node node = Jenkins.get().getNode(builtOn);
        if (!(node instanceof GitHubActionsAgent)) {
            return;
        }

        GitHubActionsAgent agent = (GitHubActionsAgent) node;
        Computer computer = agent.toComputer();
        if (computer == null) {
            return;
        }

        String jobName = run.getFullDisplayName();
        String jenkinsUrl = Jenkins.get().getRootUrl();
        String buildUrl = jenkinsUrl != null ? jenkinsUrl + run.getUrl() : "";

        try {
            // Get GITHUB_STEP_SUMMARY path from the agent's environment
            EnvVars env = computer.getEnvironment();
            String summaryPath = env.get("GITHUB_STEP_SUMMARY");
            if (summaryPath != null && !summaryPath.isEmpty()) {
                hudson.remoting.VirtualChannel channel = computer.getChannel();
                if (channel == null) {
                    LOGGER.log(Level.WARNING, "Agent {0} channel is null, cannot write step summary", builtOn);
                    return;
                }
                FilePath summaryFile = new FilePath(channel, summaryPath);
                String markdown = "### Jenkins Build\\n"
                        + "**Job:** " + jobName + "\\n";
                if (!buildUrl.isEmpty()) {
                    markdown += "**URL:** " + buildUrl + "\\n";
                }
                // Append to the summary file
                summaryFile.act(new AppendToFile(markdown));
                listener.getLogger().println("GitHub Actions step summary updated: " + jobName);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to write GitHub Actions step summary for " + builtOn, e);
        }
    }

    /** Callable that appends text to a file on the remote agent. */
    private static class AppendToFile extends jenkins.MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final String text;

        AppendToFile(String text) {
            this.text = text;
        }

        @Override
        public Void invoke(java.io.File f, hudson.remoting.VirtualChannel channel) throws java.io.IOException {
            java.nio.file.Files.writeString(f.toPath(), text.replace("\\n", "\n"),
                    java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.CREATE);
            return null;
        }
    }
}
