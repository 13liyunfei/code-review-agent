# 并发与线程池审查要点

- `Executors.newFixedThreadPool` / `newCachedThreadPool`：无界队列或无界线程，要求显式 `ThreadPoolExecutor`。
- 线程池未命名：出问题时线程栈里认不出是谁，要求设 `ThreadFactory` 前缀。
- 拒绝策略：默认 `AbortPolicy` 抛异常是否被上层感知；用 `DiscardPolicy` 时任务会静默消失。
- 队列容量与线程数关系：队列过大等于隐藏了「处理不过来」这个事实。
- 共享可变状态：无同步的静态可变字段 / 非线程安全集合在多线程下改。
- `CompletableFuture` 未处理异常：`exceptionally` / `handle` 缺失时异常被吞。
- 阻塞调用放进计算型线程池：会把 CPU 池占满，要求 IO 与 CPU 池分离。
