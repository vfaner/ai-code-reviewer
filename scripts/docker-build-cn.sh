#!/usr/bin/env bash
# 网络受限环境（中国大陆）构建：通过 DaoCloud 镜像站拉取基础镜像，
# Maven / apt 使用国内镜像源
set -euo pipefail

IMAGE_TAG="${1:-2.0.0}"
MIRROR="${DOCKER_MIRROR:-docker.m.daocloud.io}"

cd "$(dirname "$0")/.."

echo ">>> 使用镜像站 ${MIRROR} 构建 jargus:${IMAGE_TAG} ..."
docker build \
  --build-arg MAVEN_IMAGE="${MIRROR}/library/maven:3.9-eclipse-temurin-17" \
  --build-arg MAVEN_MIRROR_URL="https://maven.aliyun.com/repository/public" \
  --build-arg JRE_IMAGE="${MIRROR}/library/eclipse-temurin:17-jre-jammy" \
  --build-arg APT_MIRROR="mirrors.aliyun.com" \
  -t "jargus:${IMAGE_TAG}" \
  -t "jargus:latest" \
  .

echo ">>> 构建完成"
docker images jargus --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
