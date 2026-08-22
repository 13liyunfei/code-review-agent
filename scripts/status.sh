#!/usr/bin/env bash
# ============================================================
# 查看整套环境各组件运行状态
# ============================================================
ok()   { echo "  [运行中] $1"; }
down() { echo "  [未运行] $1"; }

echo "===== 多 Agent 代码审查环境状态 ====="

# PostgreSQL
if pgrep -q -f "postgres -D /opt/homebrew/var/postgresql@17"; then
  ok "PostgreSQL 17 + pgvector（端口 5432，库 codereview）"
else
  down "PostgreSQL 17"
fi

# Redis
if pgrep -q -f "redis-server.*:6379"; then
  ok "Redis（端口 6379）"
else
  down "Redis"
fi

# Colima / Docker
if docker info >/dev/null 2>&1; then
  ok "Colima / Docker"
  if docker ps --format '{{.Names}}' | grep -q '^gitea$'; then
    ok "Gitea 容器（http://localhost:3000，账号 reviewer，密码见你的 Gitea 安装配置）"
  else
    down "Gitea 容器（Colima 在运行但 gitea 未启动）"
  fi
else
  down "Colima / Docker"
  down "Gitea 容器"
fi

# 审查服务
if pgrep -q -f "com.codereview.agent.CodeReviewAgentApplication"; then
  ok "审查服务（http://localhost:8080，日志 /tmp/review-app.log）"
else
  down "审查服务"
fi

echo
echo "提示：./scripts/start-all.sh 一键启动全部组件"
