# 前端 TypeScript / 组件审查要点

- `any` / `as` 断言：绕过类型检查的写法要求给出理由或收紧类型。
- 请求与副作用：组件卸载后仍 setState（内存泄露警告）、重复请求未取消。
- XSS：`innerHTML` / `v-html` / `dangerouslySetInnerHTML` 且内容来自用户输入。
- 依赖数组：`useEffect` 依赖缺失导致闭包取到旧值（陈旧闭包）。
- 敏感信息进前端：密钥、内部接口地址被打包进产物。
- 列表 key 用索引：重排后状态错位。
- 直接改 props / state：不可变更新被绕过，视图不刷新。
