# Maven 依赖与构建配置审查要点

- 依赖版本硬编码：应走 `<properties>` 或 dependencyManagement，避免同库多版本。
- 新增依赖检查：scope 是否正确（`test` 的别写成默认 compile）；是否引入重复能力的库。
- 传递依赖冲突：新增依赖是否覆盖了既有版本（尤其是日志门面、JSON、HTTP 客户端）。
- 快照版本（`-SNAPSHOT`）：进主干前必须换成正式版本，否则构建不可复现。
- 许可：新增依赖的开源协议是否与项目许可兼容（AGPL 类要拦）。
- 插件配置：`maven-compiler-plugin` 的 source/target 是否与项目 JDK 基线一致。
