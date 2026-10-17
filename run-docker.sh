#!/bin/bash

set -euo pipefail

docker run --rm -p 8077:8077 \
  --add-host=host.docker.internal:host-gateway \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=taskdb \
  -e DB_USER=postgres \
  -e DB_PASSWORD=postgres \
  -e JWT_PUBLIC_KEY_PATH=/run/secrets/jwt_public_key \
  -v "$HOME/.taskflow/keys/public.pem:/run/secrets/jwt_public_key:ro" \
  --user "$(id -u):$(id -g)" \
  task-service:test