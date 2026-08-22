#!/usr/bin/env bash
# ============================================================
# ============================================================
# ⚠️ 本脚本仅用于【本地开发调试】：拉起 PostgreSQL/Redis/Colima/Gitea/引擎全家桶。
#    生产环境请勿使用 —— agent 是独立服务，直接运行 java -jar（外部依赖由运维提供）。
# ============================================================
# 一键启动整套多 Agent 代码审查环境（全组件）
#
# 依次拉起（已运行的组件自动跳过）：
#   1. PostgreSQL 17 + pgvector   （向量记忆存储，端口 5432）
#   2. Redis                      （消息队列，端口 6379）
#   3. Colima（Docker 运行时）     （Gitea 容器的宿主）
#   4. Gitea 容器                  （代码托管，端口 3000，SSH 2222）
#   5. 多 Agent 审查服务           （Web 服务，端口 8080）
#
# 用法：
#   ./scripts/start-all.sh dev       # 启动全部，使用本地 dev 配置
#   ./scripts/start-all.sh default   # 启动全部，使用默认配置
#   ./scripts/status.sh              # 查看各组件状态
#   ./scripts/stop-all.sh            # 停止全部
# ============================================================
set -e

# 本地密钥从仓库根目录的 .env 读取（.env 已被 .gitignore 忽略，不入库）；缺失时由环境变量提供
if [ -f "$(dirname "$0")/../.env" ]; then
  set -a; . "$(dirname "$0")/../.env"; set +a
elif [ -f "$(dirname "$0")/.env" ]; then
  set -a; . "$(dirname "$0")/.env"; set +a
fi

# 用法：./scripts/start-all.sh dev
SPRING_PROFILE="${1:-${SPRING_PROFILE:-dev}}"
if [ "$SPRING_PROFILE" = "-h" ] || [ "$SPRING_PROFILE" = "--help" ]; then
  echo "用法：$0 [dev|default]"
  echo "示例：$0 dev"
  exit 0
fi
case "$SPRING_PROFILE" in
  dev|default) ;;
  *) echo "ERROR: profile 只支持 dev 或 default，当前值：$SPRING_PROFILE" >&2; exit 1 ;;
esac

# ---------- 可按需修改的配置 ----------
GITEA_URL="http://localhost:3000"
# Colima/Docker 下 Gitea 容器回调宿主机的网关地址（Mac Colima 通常需填宿主机 LAN IP，
# 其他环境可用 host.docker.internal）。仅用于本地脚本，不入库、不硬编码。
HOST_IP="${HOST_IP:-host.docker.internal}"
# 仅用于脚本回显提示，绝不硬编码真实口令；真实口令请在 .env 或环境变量中提供。
GITEA_ADMIN_PASSWORD="${GITEA_ADMIN_PASSWORD:-}"
# 凭证来源优先级：环境变量 > 仓库根 .env（gitignore，不入库）> 无（运行时将因缺少凭证失败，属预期）
GITEA_TOKEN="${GITEA_API_TOKEN:-${GITEA_TOKEN:-}}"
GITEA_WEBHOOK_SECRET="${GITEA_WEBHOOK_SECRET:-}"
TOKENHUB_API_KEY="${TOKENHUB_API_KEY:-}"
REVIEW_API_TOKEN="${REVIEW_API_TOKEN:-}"
if [ -z "$GITEA_TOKEN" ] || [ -z "$TOKENHUB_API_KEY" ]; then
  echo "WARN: 缺少 GITEA_API_TOKEN / TOKENHUB_API_KEY，请在 .env 或环境变量中提供（详见 .env.example）" >&2
fi
if [ -z "$REVIEW_API_TOKEN" ]; then
  echo "WARN: 未设置 REVIEW_API_TOKEN，/api 接口将零鉴权放行（仅限本地 dev）" >&2
fi
PG_DATA_DIR="/opt/homebrew/var/postgresql@17"
PG_BIN="/opt/homebrew/opt/postgresql@17/bin"
PG_DB="codereview"
REDIS_BIN="/opt/homebrew/opt/redis/bin/redis-server"
APP_PORT=8080
APP_LOG="/tmp/review-app.log"
GITEA_DATA="$HOME/gitea-local/data"
# --------------------------------------

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
step() { echo; echo "==> $1"; }

# 本机代理会拦截 localhost 请求，统一绕过
export NO_PROXY="localhost,127.0.0.1,${HOST_IP}"
export no_proxy="$NO_PROXY"
# TokenHub 使用 JDK HttpClient；本机代理环境变量会导致 CONNECT 超时，开发脚本默认直连。
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy

# ---------- 1. PostgreSQL 17 + pgvector ----------
step "1/5 检查 PostgreSQL 17（pgvector）"
if pgrep -q -f "postgres -D $PG_DATA_DIR"; then
  echo "    已在运行，跳过"
else
  if [ ! -f "$PG_DATA_DIR/PG_VERSION" ]; then
    echo "    首次使用，初始化数据目录..."
    "$PG_BIN/initdb" -D "$PG_DATA_DIR" --locale=en_US.UTF-8 -E UTF8 --auth=trust
  fi
  "$PG_BIN/pg_ctl" -D "$PG_DATA_DIR" -l /tmp/pg.log start
  echo "    PostgreSQL 已启动（日志 /tmp/pg.log）"
fi
# 确保业务库与 vector 扩展存在（幂等；注意必须用 PG17 自带 psql，PATH 里可能是 PG16 客户端）
if ! "$PG_BIN/psql" -lqt 2>/dev/null | grep -q "$PG_DB"; then
  "$PG_BIN/createdb" "$PG_DB"
  echo "    已创建数据库 $PG_DB"
fi
"$PG_BIN/psql" -d "$PG_DB" -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null
echo "    数据库就绪：$PG_DB（vector 扩展已启用）"

# ---------- 2. Redis ----------
step "2/5 检查 Redis"
if pgrep -q -f "redis-server.*:6379"; then
  echo "    已在运行，跳过"
else
  "$REDIS_BIN" --daemonize yes --port 6379
  echo "    Redis 已启动（端口 6379）"
fi

# ---------- 3. Colima（Docker 运行时） ----------
step "3/5 检查 Colima（Docker 运行时）"
if docker info >/dev/null 2>&1; then
  echo "    已在运行，跳过"
else
  echo "    启动 Colima 虚拟机（约 1 分钟）..."
  colima start --cpu 6 --memory 12 --disk 60
fi

# ---------- 4. Gitea ----------
step "4/5 检查 Gitea 容器"
if docker ps --format '{{.Names}}' | grep -q '^gitea$'; then
  echo "    已在运行，跳过"
else
  if docker ps -a --format '{{.Names}}' | grep -q '^gitea$'; then
    docker start gitea >/dev/null
  else
    echo "    首次使用，创建 Gitea 容器..."
    mkdir -p "$GITEA_DATA" "$HOME/gitea-local/logs"
    docker run -d --name gitea \
      -p 3000:3000 -p 2222:22 \
      -v "$GITEA_DATA":/data \
      -v "$HOME/gitea-local/logs":/var/log/gitea \
      -e USER_UID=1000 -e USER_GID=1000 \
      -e GITEA__security__INSTALL_LOCK=true \
      -e GITEA__server__ROOT_URL=http://localhost:3000/ \
      -e GITEA__server__DOMAIN=localhost \
      -e GITEA__service__DISABLE_REGISTRATION=true \
      -e GITEA__webhook__ALLOWED_HOST_LIST=private,loopback \
      --restart unless-stopped \
      gitea/gitea:latest >/dev/null
  fi
  # 等待 Gitea HTTP 就绪
  for i in $(seq 1 30); do
    if curl -s --noproxy '*' -o /dev/null --max-time 2 "$GITEA_URL"; then break; fi
    sleep 2
  done
fi
echo "    Gitea 就绪：$GITEA_URL（账号 reviewer，密码见你的 Gitea 安装配置）"

# ---------- 5. 审查服务 ----------
step "5/5 启动多 Agent 审查服务（端口 $APP_PORT）"
if pgrep -q -f "com.codereview.agent.CodeReviewAgentApplication"; then
  echo "    审查服务已在运行，先停掉旧实例..."
  pkill -f "com.codereview.agent.CodeReviewAgentApplication" || true
  pkill -f "spring-boot:run" || true
  sleep 3
fi

cd "$PROJECT_DIR"
unset SERVER__PORT SERVER__HOST   # 防止环境变量覆盖端口配置
export GITEA_API_TOKEN="$GITEA_TOKEN"
export GITEA_WEBHOOK_SECRET="$GITEA_WEBHOOK_SECRET"
export TOKENHUB_API_KEY="$TOKENHUB_API_KEY"
export REVIEW_API_TOKEN="$REVIEW_API_TOKEN"
nohup ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles="$SPRING_PROFILE" \
  -Dspring-boot.run.arguments="--server.port=$APP_PORT --gitea.base-url=$GITEA_URL" \
  > "$APP_LOG" 2>&1 &

for i in $(seq 1 45); do
  if grep -q "Started CodeReviewAgentApplication" "$APP_LOG" 2>/dev/null; then
    echo "    审查服务就绪：http://localhost:$APP_PORT（日志 $APP_LOG）"
    echo
    echo "============================================================"
    echo " 全部组件已就绪！"
    echo "   Gitea        : $GITEA_URL  （reviewer，密码见你的 Gitea 安装配置）"
    echo "   审查服务      : http://localhost:$APP_PORT/webhook/gitea"
    echo "   演示 PR       : $GITEA_URL/reviewer/demo-project/pulls"
    echo " 提 PR 即触发自动审查，报告回写到 PR 评论。"
    echo "============================================================"
    exit 0
  fi
  sleep 2
done
echo "启动超时，请查看日志：$APP_LOG"
exit 1
