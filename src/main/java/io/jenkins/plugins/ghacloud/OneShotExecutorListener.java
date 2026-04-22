package io.jenkins.plugins.ghacloud;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for executor task completion and immediately terminates one-shot
 * agents after their build finishes, rather than waiting for the retention
 * strategy's periodic check() poll.
 */
@Extension
public class OneShotExecutorListener implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(OneShotExecutorListener.class.getName());

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // no-op
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        maybeTerminate(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        maybeTerminate(executor);
    }

    private void maybeTerminate(Executor executor) {
        if (!(executor.getOwner() instanceof AbstractCloudComputer)) {
            return;
        }
        AbstractCloudComputer<?> computer = (AbstractCloudComputer<?>) executor.getOwner();
        hudson.model.Node node = computer.getNode();
        if (!(node instanceof GitHubActionsAgent)) {
            return;
        }
        GitHubActionsAgent agent = (GitHubActionsAgent) node;
        if (!agent.isOneShot()) {
            return;
        }

        LOGGER.log(Level.INFO, "One-shot agent {0} build finished, taking offline and scheduling termination",
                computer.getName());

        // Immediately take offline so Jenkins won't assign another build
        computer.setAcceptingTasks(false);

        // Terminate after a short delay to let Jenkins finish executor housekeeping
        jenkins.util.Timer.get().schedule(() -> {
            GitHubActionsRetentionStrategy rs =
                    (GitHubActionsRetentionStrategy) agent.getRetentionStrategy();
            rs.terminateAgent(computer);
        }, 3, TimeUnit.SECONDS);
    }
}
