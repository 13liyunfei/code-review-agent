#!/usr/bin/env bash
# ============================================================
# 一键停止整套环境
# 用法：
#   ./scripts/stop-all.sh           # 停止审查服务 + Gitea + Redis + PostgreSQL（保留 Colima）
#   ./scripts/stop-all.sh --all     # 连 Colima 虚拟机一起停止（彻底关停）
# ============================================================
set -e

PG_DATA_DIR="/opt/homebrew/var/postgresql@17"
PG_BIN="/opt/homebrew/opt/postgresql@17/bin"

step() { echo; echo "==> $1"; }

step "1/4 停止审查服务"
if pkill -f "com.codereview.agent.CodeReviewAgentApplication" 2>/dev/null; then
  pkill -f "spring-boot:run" 2>/dev/null || true
  sleep 2
  echo "    已停止"
else
  echo "    未在运行"
fi

step "2/4 停止 Gitea 容器"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^gitea$'; then
  docker stop gitea >/dev/null && echo "    已停止"
else
  echo "    未在运行"
fi

step "3/4 停止 Redis"
if pkill -f "redis-server.*:6379" 2>/dev/null; then
  echo "    已停止"
else
  echo "    未在运行"
fi

step "4/4 停止 PostgreSQL"
if pgrep -q -f "postgres -D $PG_DATA_DIR"; then
  "$PG_BIN/pg_ctl" -D "$PG_DATA_DIR" stop -m fast && echo "    已停止"
else
  echo "    未在运行"
fi

if [ "$1" = "--all" ] && docker info >/dev/null 2>&1; then
  step "额外：停止 Colima 虚拟机"
  colima stop && echo "    已停止"
fi

echo
echo "全部停止完成（数据均保留：PG 数据目录 / Gitea 卷 / Redis 无持久化数据）"
