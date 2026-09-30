# Deployment layout

The source repository contains the Java service, the Python API adapter, and
the algorithm package under `demo/agent`. Model weights and runtime data stay
outside Git.

## Server directories

```text
/srv/cullpilot/
  app/              # Git checkout of this repository
  venv/             # Python virtual environment
  data/             # SQLite database
  storage/          # uploaded images and exports
/srv/cullpilot-models/  # model files, provisioned separately
```

Set `ANALYSIS_MODEL_ROOT=/srv/cullpilot-models` in the server environment.
The Java service does not load model files. It calls the Python API at
`PYTHON_API_BASE_URL`; the Python adapter loads algorithms from `demo/agent`
and model weights from `ANALYSIS_MODEL_ROOT`.

The prepared offline model set enables DeepFace and DINOv2 in the example
environment. The API still falls back to feature-only analysis if an optional
package or weight is unavailable.

## First server setup

1. Install Java 17, Maven 3.9+, Python 3.12, Git, and systemd.
2. Create the `cullpilot` user and clone this repository to
   `/srv/cullpilot/app`.
3. Create `/srv/cullpilot-models` and place the model files there using the
   layout documented in `demo/README.md`.
4. Create `/etc/cullpilot/cullpilot.env` from `deploy/server.env.example`.
5. Create the Python environment and install the requirements once:

   ```bash
   python3.12 -m venv /srv/cullpilot/venv
   /srv/cullpilot/venv/bin/pip install -r /srv/cullpilot/app/CullPilot/python-api/requirements.txt
   /srv/cullpilot/venv/bin/pip install -r /srv/cullpilot/app/CullPilot/python-api/requirements-ai.txt
   ```

6. Copy both files from `deploy/systemd/` to `/etc/systemd/system/`, then run:

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now cullpilot-python.service cullpilot-java.service
   ```

The SSH deployment user must be allowed to run `systemctl daemon-reload` and
restart these two units without an interactive sudo password. A narrow sudoers
rule for those commands is sufficient.

## GitHub Actions

The workflow runs Java and Python tests on pushes to `main`. It then connects
to the server and runs `deploy/update-server.sh`, which fetches the new commit,
updates Python dependencies, packages the Java service, and restarts both
systemd units. It never changes `/srv/cullpilot-models`.

Configure these GitHub repository secrets:

```text
DEPLOY_HOST
DEPLOY_PORT        # optional, defaults to 22
DEPLOY_USER
DEPLOY_SSH_KEY
DEPLOY_APP_DIR     # optional, defaults to /srv/cullpilot/app
```

For a private repository, the server checkout needs a read-only GitHub deploy
key so `git fetch origin main` can run without an interactive login.
