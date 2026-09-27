# 配置类装配审查要点

- `@ConfigurationProperties` 缺少默认值：属性缺省时字段为 null，运行期才会 NPE。
- `@Value` 与 `@ConfigurationProperties` 风格混用：同一含义两种读法，改配置容易漏改一处。
- `@RefreshScope` / 热更新缺失：注释声称「可热切换」但实现是构造期注入——声明与实现的落差要显式报出。
- Bean 循环依赖：`@Autowired` 字段注入掩盖了循环，要求改构造器注入暴露问题。
- `@ConditionalOnProperty` 的默认分支：开关关闭时是否有可用降级实现，还是直接起不来。
- 单例 Bean 里的可变字段：并发下会被共享，要求无状态或加同步。
