#!/usr/bin/env bash
# 构建 AI Code Reviewer Docker 镜像
set -euo pipefail

IMAGE_NAME="ai-code-reviewer"
IMAGE_TAG="${1:-1.0.0}"

cd "$(dirname "$0")/.."

echo ">>> 构建镜像 ${IMAGE_NAME}:${IMAGE_TAG} ..."
docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" -t "${IMAGE_NAME}:latest" .

echo ">>> 构建完成："
docker images "${IMAGE_NAME}" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
