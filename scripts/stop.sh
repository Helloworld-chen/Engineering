#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Keep database and image volumes; never remove user data automatically.
docker compose down
