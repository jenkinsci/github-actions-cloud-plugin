package io.jenkins.plugins.ghacloud;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.remoting.Channel;
import hudson.remoting.LocalChannel;
import hudson.slaves.AbstractCloudComputer;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.InboundAgentExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@Timeout(90)
class GitHubActionsAgentTest {

    @RegisterExtension
    final InboundAgentExtension inbound = new InboundAgentExtension();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runningPipelineRecoversAndOneShotAgentExits(boolean webSocket, JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, true);
        inbound.start(
                j,
                InboundAgentExtension.Options.newBuilder()
                        .name(agent.getNodeName())
                        .webSocket(webSocket)
                        .build());
        AbstractCloudComputer<?> computer = (AbstractCloudComputer<?>) agent.toComputer();
        assertNotNull(computer);
        Channel originalChannel = computer.getChannel();
        assertNotNull(originalChannel);

        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "node('" + agent.getNodeName() + "') {"
                        + " sh 'echo READY_FOR_DISCONNECT; sleep 20; echo RECOVERED'"
                        + " }",
                false));
        QueueTaskFuture<WorkflowRun> future = job.scheduleBuild2(0);
        assertNotNull(future);
        WorkflowRun build = future.waitForStart();
        j.waitForMessage("READY_FOR_DISCONNECT", build);
        assertTrue(build.isBuilding());

        originalChannel.close(new IOException("Simulated transient connection loss"));
        originalChannel.join();
        j.waitOnline(agent);

        assertNotSame(originalChannel, computer.getChannel());
        assertTrue(inbound.isAlive(agent.getNodeName()));
        j.assertBuildStatus(Result.SUCCESS, future.get(60, TimeUnit.SECONDS));
        j.assertLogContains("RECOVERED", build);
        waitForAgentExit(agent);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
    }

    @Test
    void terminatingReusableAgentStopsReconnect(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, false);
        inbound.start(j, agent.getNodeName());

        agent.terminate();

        waitForAgentExit(agent);
        assertNull(j.jenkins.getNode(agent.getNodeName()));
    }

    @Test
    void terminatingOfflineAgentRemovesNode(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, false);

        agent.terminate();

        assertNull(j.jenkins.getNode(agent.getNodeName()));
    }

    @Test
    void disablingReconnectTimesOutOnUnresponsiveChannel(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                new CountDownLatch(1).await();
                return null;
            });
            LocalChannel channel = new LocalChannel(executor);

            long start = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> agent.disableReconnect(channel));
            assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(5));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disablingReconnectHandlesRemoteFailure(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // The controller has no inbound Remoting engine, so the remote call fails.
            assertDoesNotThrow(() -> agent.disableReconnect(new LocalChannel(executor)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disablingReconnectPreservesInterruption(JenkinsRule j) throws Exception {
        GitHubActionsAgent agent = createAgent(j, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                new CountDownLatch(1).await();
                return null;
            });
            Thread.currentThread().interrupt();

            assertThrows(InterruptedException.class, () -> agent.disableReconnect(new LocalChannel(executor)));
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }

    private GitHubActionsAgent createAgent(JenkinsRule j, boolean oneShot) throws Exception {
        GitHubActionsAgent agent = new GitHubActionsAgent(
                "reconnecting-agent", j.createTmpDir().getAbsolutePath(), "gha-test", 1, 60, "test-cloud", oneShot);
        j.jenkins.addNode(agent);
        return agent;
    }

    private void waitForAgentExit(GitHubActionsAgent agent) {
        assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> {
                    while (inbound.isAlive(agent.getNodeName())) {
                        Thread.sleep(100);
                    }
                },
                "The agent must exit after intentional termination, not keep reconnecting");
    }
}
