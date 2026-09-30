# Deployment layout

The source repository contains the Java service, the Python API adapter, and
the algorithm package under `demo/agent`. Model weights and runtime data stay
outside Git.

## Server directories

```text
/root/workspace/aic/
  .git .agents .codex       # server-managed; never touched by deployment
  current -> releases/<sha> # active source release
  releases/<sha>/           # code and tested Java JAR
  runtime/venv/             # Python virtual environment
  demo/models/              # existing model files; never uploaded
  demo/data/                # existing data and semantic cache
  CullPilot/data/           # existing SQLite database
  CullPilot/storage/        # existing uploaded files and exports
```

Set `ANALYSIS_MODEL_ROOT=/root/workspace/aic/demo/models` in the server
environment. GitHub Actions uploads source code only; it never replaces the
model, data, database, or storage directories.

## First server setup

1. Install Java 17, Python 3.10+, curl, tar, and systemd. Maven and Node.js
   are not needed on the server when the tested Java JAR is uploaded by CI.
2. Keep the existing directories under `/root/workspace/aic` in place.
3. Create `/etc/cullpilot/cullpilot.env` from `deploy/server.env.example` and
   review every value before starting services.
4. Configure the GitHub repository secrets listed below.
5. Run the workflow manually once. It creates a release, a Python virtual
   environment, installs Python dependencies, installs systemd units, and
   starts both services.

The workflow starts the long-running installation with `systemd-run` and
polls its status over short SSH connections. A dropped SSH connection during
dependency installation does not stop the server-side deployment. Progress
logs are written under `/root/workspace/aic/runtime/deploy-logs/` and exit
codes under `/root/workspace/aic/runtime/deploy-status/`. Do not start a new
deployment after a polling timeout until the server-side status is checked.

The current server uses root-owned paths and root-owned orphan processes, so
the initial migration uses the root SSH account and root systemd services.
Move the application to `/opt/cullpilot` and use a dedicated service account
after the deployment is stable.

## GitHub Actions

The workflow is manual-only for the first deployment. Once the migration has
been verified, a `push` trigger can be added for automatic deployments. It
uploads the tested Java JAR and runtime source to a new release directory, then
activates it through the `current` symlink. It does not run Git on the server
and does not modify the server `.git`, `.agents`, `.codex`, models, data,
database, or storage.

Configure these GitHub repository secrets:

```text
DEPLOY_HOST
DEPLOY_PORT        # optional, defaults to 22
DEPLOY_USER        # root for the initial migration
DEPLOY_SSH_KEY
DEPLOY_KNOWN_HOSTS # required; the server's verified SSH host key line(s)
```

The workflow uses the verified `DEPLOY_KNOWN_HOSTS` value instead of accepting
an unverified host key. Every deployment creates a database, storage, and
demo-data backup under `/root/workspace/aic/backups/` before restarting the
services.
