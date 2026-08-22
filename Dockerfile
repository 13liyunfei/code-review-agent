# 多 Agent 代码审查服务 —— 生产镜像
# 构建流程：先 `./mvnw -o clean package` 生成 target/code-review-agent-*.jar，
# 再 `docker build -t code-review-agent .`
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制构建产物（由 mvn package 生成）
COPY target/code-review-agent-*.jar app.jar

# 运行时数据目录（自定义规则/历史/反馈等落盘，生产建议挂卷持久化）
VOLUME /app/data

EXPOSE 8080

# JVM：容器感知内存上限；OOM 即退出便于编排重启；G1 适合大堆与低延迟
# 生产配置通过环境变量注入（GITEA_*/TOKENHUB_*/PGVECTOR_*/REDIS_*），
# 无需额外 profile；默认即生产配置（webhook-allow-unsigned=false、优雅停机）。
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseG1GC", \
  "-jar", "app.jar"]
