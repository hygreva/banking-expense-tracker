#!/usr/bin/env bash
set -euo pipefail
: "${AWS_REGION:?Set AWS_REGION}"
: "${TAG:?Set an unused immutable TAG such as a git commit SHA}"
REGISTRY_STACK="${REGISTRY_STACK:-banking-registry}"
BACKEND=$(aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$REGISTRY_STACK" --query "Stacks[0].Outputs[?OutputKey=='BackendRepository'].OutputValue | [0]" --output text)
FRONTEND=$(aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$REGISTRY_STACK" --query "Stacks[0].Outputs[?OutputKey=='FrontendRepository'].OutputValue | [0]" --output text)
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${BACKEND%%/*}"
docker build --platform linux/amd64 -t "$BACKEND:$TAG" backend
docker build --platform linux/amd64 --build-arg NGINX_CONFIG=nginx.aws.conf -t "$FRONTEND:$TAG" frontend
docker push "$BACKEND:$TAG"
docker push "$FRONTEND:$TAG"
printf 'BackendImage=%s\nFrontendImage=%s\n' "$BACKEND:$TAG" "$FRONTEND:$TAG"
