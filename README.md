# GitHub Actions Cloud Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/github-actions-cloud)](https://plugins.jenkins.io/github-actions-cloud/)
[![Jenkins](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Fgithub-actions-cloud-plugin%2Fmain)](https://ci.jenkins.io/job/Plugins/job/github-actions-cloud-plugin/job/main/)

Dynamically provisions Jenkins agents as GitHub Actions workflow runs.

When Jenkins needs a build agent, this plugin triggers a GitHub Actions `workflow_dispatch` event.
The GitHub Actions runner downloads the Jenkins `agent.jar` and connects back to the controller
via WebSocket. Once the build completes and the agent goes idle, it is terminated automatically,
and the GitHub Actions workflow job finishes.

## How it works

```
Jenkins controller                     GitHub Actions
      │                                      │
      ├─ Job queued (label: gha-linux)       │
      ├─ Cloud.provision() called            │
      ├─ POST workflow_dispatch ────────────►│
      │                                      ├─ Workflow starts
      │                                      ├─ Downloads agent.jar
      │◄──── WebSocket connection ───────────┤
      ├─ Build runs on agent                 │
      ├─ Build completes                     │
      ├─ Idle timeout → terminate()          │
      ├─ Disable reconnect, disconnect ─────►│
      │                                      ├─ agent.jar exits
      │                                      ├─ Workflow job completes
      └──────────────────────────────────────┘
```

### Connection recovery

Use `-noReconnectAfter 5m` instead of `-noReconnect` in the agent command.
This keeps the runner and its workspace available for reconnection after a
transient network failure, so recoverable Pipeline steps can continue.
It does not retry builds or suppress Jenkins build failures.

On intentional termination (one-shot completion or idle timeout), the plugin
disables reconnection in the running agent before removing its node. The
agent therefore exits promptly after normal shutdown. If the agent is already
unreachable and cannot receive that instruction, the reconnect timeout bounds
how long it keeps retrying.

Upgrade the plugin before changing existing workflows: older versions do not
disable reconnection during termination and will leave the runner retrying
until its timeout. A successful Actions workflow is not proof of a successful
Jenkins build; check the Jenkins result as well.

## Requirements

- A GitHub repository with a workflow file that accepts `jenkins_url`, `agent_name`, and `agent_secret` inputs
- A GitHub Personal Access Token (PAT) with `actions:write` scope, stored as a **Secret Text** credential in Jenkins
- Jenkins must be reachable from GitHub Actions runners (public URL, tunnel, or self-hosted runners)

## Setup

### 1. Add the workflow to your GitHub repository

Copy one of the sample workflows into your repository at `.github/workflows/`:

**Linux** (`jenkins-agent.yml`):

```yaml
name: Jenkins Agent
on:
  workflow_dispatch:
    inputs:
      jenkins_url:
        description: 'Jenkins server URL'
        required: true
      agent_name:
        description: 'Jenkins agent name'
        required: true
      agent_secret:
        description: 'Jenkins agent secret'
        required: true

jobs:
  agent:
    runs-on: ubuntu-latest
    timeout-minutes: 360
    steps:
      - name: Mask secret
        run: |
          secret=$(jq -r '.inputs.agent_secret' "$GITHUB_EVENT_PATH")
          echo "::add-mask::$secret"

      - name: Download Jenkins agent JAR
        run: |
          curl -sSfL --retry 3 --retry-delay 5 -o agent.jar \
            "${{ inputs.jenkins_url }}jnlpJars/agent.jar"

      - name: Connect to Jenkins
        env:
          AGENT_SECRET: ${{ inputs.agent_secret }}
        run: |
          "$JAVA_HOME_21_X64/bin/java" -jar agent.jar \
            -url "${{ inputs.jenkins_url }}" \
            -secret "$AGENT_SECRET" \
            -name "${{ inputs.agent_name }}" \
            -workDir "/home/runner/agent" \
            -noReconnectAfter 5m
```

**Windows** (`jenkins-agent-windows.yml`): use `runs-on: windows-latest` and PowerShell syntax — see the [sample workflows](sample-workflows/).

### 2. Add a GitHub PAT credential in Jenkins

1. Go to **Manage Jenkins → Credentials**
2. Add a **Secret Text** credential with your GitHub PAT (`actions:write` scope)
3. Note the credential ID (e.g., `github-pat`)

### 3. Configure the cloud

1. Go to **Manage Jenkins → Clouds → New cloud**
2. Select **GitHub Actions**
3. Fill in:
   - **Cloud Name**: e.g., `github-actions`
   - **Repository**: `owner/repo` (the repo containing the workflow)
   - **Credentials**: select the PAT credential
   - **Max Number of Agents**: limit concurrent agents (0 = unlimited)
4. Add one or more **Agent Templates**:
   - **Template Name**: unique name for this template (e.g., `linux-builder`). Used as the agent name prefix and shown in Cloud Statistics.
   - **Labels**: e.g., `gha-linux`
   - **Remote FS Root**: `/home/runner/agent`
   - **Workflow File Name**: e.g., `jenkins-agent.yml`
   - **Idle Termination Minutes**: how long after a build to keep the agent alive

### 4. Create a job

Create a Jenkins job with the **Restrict where this project can be run** option set to your label (e.g., `gha-linux`). When the job runs, the plugin will provision a GitHub Actions agent automatically.

## Configuration reference

### Cloud settings

| Field | Description |
|-------|-------------|
| Cloud Name | Unique identifier for this cloud |
| GitHub API URL | API endpoint (default: `https://api.github.com`). When the GitHub plugin is installed with enterprise servers configured, a dropdown is shown. |
| Repository | GitHub repository in `owner/repo` format |
| Credentials | Secret Text credential containing a GitHub PAT |
| Max Number of Agents | Maximum concurrent agents from this cloud (0 = unlimited) |

### Agent template settings

| Field | Description |
|-------|-------------|
| Template Name | Required. Unique name for this template (e.g., `linux-builder`). Used as the agent name prefix (e.g., `linux-builder-2de45c6b`) and displayed in Cloud Statistics. |
| Labels | Jenkins labels to match against |
| Remote FS Root | Agent working directory |
| Number of Executors | Executors per agent (default: 1) |
| Git Ref | Branch/tag to run the workflow against (default: `main`) |
| One-Shot Agent | When enabled, the agent is terminated immediately after completing a single build. Prevents workflow reuse across jobs. Overrides Idle Termination Minutes. |
| Idle Termination Minutes | Minutes idle before termination (ignored when One-Shot is enabled) |
| Workflow File Name | Workflow file to trigger (e.g., `jenkins-agent.yml`) |
| Max Number of Agents | Maximum concurrent agents from this template (0 = unlimited) |

## Development

```bash
# Build
mvn verify

# Run locally with Jenkins
mvn hpi:run
```

## License

[MIT License](LICENSE)
