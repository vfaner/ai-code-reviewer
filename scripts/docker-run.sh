#!/usr/bin/env bash
# 使用 docker compose 启动（后台），数据通过命名卷持久化
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose up -d --build

echo ">>> 服务启动中（约 30-60 秒）..."
echo ">>> 健康检查: curl http://localhost:8080/actuator/health"
echo ">>> 查看日志: docker compose logs -f"
