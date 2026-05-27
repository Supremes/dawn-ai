# Java Code Review Checklist（详细版）

逐项检查时使用。每项给出"需要警觉的信号"+"通常的处理方式"。

## 一、正确性（Correctness）

1. **空指针风险**：返回值可能为 null 却没做检查；从 Map.get / Optional.get 直接拿值。
   - 处理：Optional 链 / Objects.requireNonNull / 保护性 if。

2. **并发安全**：共享可变状态（static 字段、Spring Bean 字段）被多线程读写。
   - 处理：volatile / Atomic* / 锁 / 不可变对象 / ThreadLocal。

3. **资源泄漏**：InputStream / Connection / FileChannel 没有 try-with-resources。
   - 处理：try-with-resources；老 API 用 try-finally + close()。

4. **数值边界**：int 加法溢出、除以零、负索引访问数组。
   - 处理：Math.addExact / 显式校验。

5. **集合迭代修改**：for-each 中调 list.remove()。
   - 处理：Iterator.remove() / 收集后批量删 / 用 removeIf。

6. **equals/hashCode 一致性**：record 自动给；class 手写时容易遗漏 hashCode。
   - 处理：尽量用 record；class 用 IDE 生成。

## 二、可读性与命名（Readability & Naming）

7. **方法过长**：超过 30 行通常意味着多职责。
   - 处理：提取私有方法，每个方法干一件事。

8. **嵌套过深**：超过 3 层 if/for 意味着逻辑复杂或缺少早返回。
   - 处理：guard clause 早返回；提取方法降低层级。

9. **命名模糊**：`data`、`info`、`tmp`、`process()`。
   - 处理：用业务名词命名（`shippingAddress`、`reconcileOrder`）。

10. **magic number**：直接出现的 7、365、3600。
    - 处理：提取为 `static final` 常量并命名。

11. **注释解释 WHAT**：注释说"这里循环遍历列表"。
    - 处理：删掉。只在解释 WHY（非显然的约束/历史决策）时保留注释。

## 三、错误处理（Error Handling）

12. **吞异常**：`catch (Exception e) {}` 或 `catch ... { log.error(...) }` 然后继续。
    - 处理：要么 rethrow / wrap 成业务异常，要么明确说明为何可以忽略。

13. **过度受检异常**：throws 一长串。
    - 处理：在边界层包装成 RuntimeException 或业务异常。

14. **异常信息含糊**：`throw new RuntimeException("error")`。
    - 处理：信息要够调试用：包含输入参数、状态。

15. **NullPointerException 作为业务信号**：catch NPE 来判断"对象不存在"。
    - 处理：用 Optional / 显式 contains 检查。

## 四、API 与抽象（API Design）

16. **可变默认参数**：方法接受 List 但内部 add。
    - 处理：参数视为不可变；需要修改时复制。

17. **过度泛化**：`Object` / `Map<String, Object>` 作为返回值。
    - 处理：定义 record / DTO，让类型表达意图。

18. **构造器副作用**：构造器里发起网络请求 / 读文件。
    - 处理：构造器只赋值；副作用放 init/start 方法。

19. **静态工厂 vs 构造器**：复杂构造逻辑用 `of()` / `from()` / `builder()` 更清晰。

## 五、Spring / Spring AI 特定

20. **字段注入**：`@Autowired private Foo foo;`。
    - 处理：构造器注入 + `final` + Lombok `@RequiredArgsConstructor`。

21. **Bean 名冲突**：多个同类型 Bean 没有 `@Qualifier`。
    - 处理：明确命名 + Qualifier，或拆分到子接口。

22. **Reactive 上下文丢失**：Reactor 流中读 ThreadLocal。
    - 处理：使用 `Context` / Micrometer ContextRegistry 注册 accessor。

23. **Tool 函数副作用**：`Function<Req, Resp>.apply()` 内修改全局状态。
    - 处理：apply 应是幂等查询；副作用应有显式的写工具。

## 六、性能（Performance）

24. **N+1 查询**：循环里调 DAO/Repository。
    - 处理：批量查询 + Map 索引。

25. **字符串拼接在循环里**：`for (...) { s += ...; }`。
    - 处理：StringBuilder 或 String.join。

26. **大对象长期持有**：Map/List 累积无界。
    - 处理：限定容量 / 用缓存（Caffeine）+ TTL。

## 七、测试可达性（Testability）

27. **静态依赖**：`SomeUtil.staticMethod()` 难 mock。
    - 处理：依赖注入接口实现。

28. **时间硬编码**：`new Date()` / `System.currentTimeMillis()` 散落。
    - 处理：注入 `Clock`，测试时替换 `Clock.fixed`。

29. **随机数硬编码**：`new Random()` / `Math.random()`。
    - 处理：注入 `RandomGenerator`，测试时给定种子。

## 八、安全（Security）

30. **SQL 拼接**：`"SELECT ... WHERE id = " + userInput`。
    - 处理：PreparedStatement / NamedParameterJdbcTemplate / 参数化查询。

31. **路径穿越**：用户输入直接拼到文件路径。
    - 处理：`Path.resolve().normalize()` + `startsWith(rootDir)` 校验。

32. **敏感信息日志**：log 出 token、密码、个人信息。
    - 处理：脱敏（掩码 / 哈希）；用 MDC 隔离。
