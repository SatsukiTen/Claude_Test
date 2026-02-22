#!/bin/bash
set -euo pipefail

# Only run in remote (web) environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

echo "Installing Python dependencies..."
pip install -r requirements.txt --quiet

echo 'export PYTHONPATH="."' >> "$CLAUDE_ENV_FILE"

echo "Session setup complete."
