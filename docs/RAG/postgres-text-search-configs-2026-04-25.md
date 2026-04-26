# RAG 稀疏检索的 PostgreSQL 文本搜索配置说明

## 背景

本项目的稀疏检索路径并没有使用 pgvector 自带的分词或词干化能力。
它实际依赖的是 PostgreSQL Full Text Search，也就是 `to_tsvector(...)` 和 `websearch_to_tsquery(...)`。

对应实现见 [src/main/java/com/dawn/ai/rag/retrieval/sparse/PostgresBm25Retriever.java](src/main/java/com/dawn/ai/rag/retrieval/sparse/PostgresBm25Retriever.java)。

这个区分很重要：

- pgvector 负责向量相似度检索
- PostgreSQL FTS 负责稀疏词法检索
- `simple`、`english`、`french`、`german` 这些都不是 pgvector 的配置，而是 PostgreSQL 文本搜索配置

## 本次问题的根因

线上现象是：

- query 为 `customer`
- dense retrieval 命中 2 条
- sparse retrieval 命中 0 条

进一步排查后发现，库里的文本实际包含的是 `customers`，不是 `customer`。

在 PostgreSQL `simple` 配置下：

- `customer` 会保留为 `customer`
- `customers` 会保留为 `customers`
- 二者不会归一成同一个 lexeme

在 PostgreSQL `english` 配置下：

- `customer` 会被词干化为 `custom`
- `customers` 也会被词干化为 `custom`
- 因此 sparse 检索可以命中复数形式的文档

数据库侧验证：

```sql
SELECT ts_debug('simple', 'customer customers returned returning');
SELECT ts_debug('english', 'customer customers returned returning');
```

观察结果：

- `simple` 会保留 `customer`、`customers`、`returned`、`returning` 这些不同形式
- `english` 会把它们归一成 `custom`、`custom`、`return`、`return`

再结合当前 `vector_store` 实例验证：

```sql
SELECT COUNT(*)
FROM vector_store
WHERE to_tsvector('simple', content) @@ websearch_to_tsquery('simple', 'customer');
```

结果：`0`

```sql
SELECT COUNT(*)
FROM vector_store
WHERE to_tsvector('english', content) @@ websearch_to_tsquery('english', 'customer');
```

结果：`2`

所以这次 miss 不是并发问题，也不是路由问题，而是 `simple` 不做英文词形归一。

## 当前数据库可用的内建配置

查询方式：

```sql
SELECT cfgname FROM pg_catalog.pg_ts_config ORDER BY cfgname;
```

当前实例可用配置包括：

- `arabic`
- `armenian`
- `basque`
- `catalan`
- `danish`
- `dutch`
- `english`
- `finnish`
- `french`
- `german`
- `greek`
- `hindi`
- `hungarian`
- `indonesian`
- `irish`
- `italian`
- `lithuanian`
- `nepali`
- `norwegian`
- `portuguese`
- `romanian`
- `russian`
- `serbian`
- `simple`
- `spanish`
- `swedish`
- `tamil`
- `turkish`
- `yiddish`

这些配置本质上是 PostgreSQL 预定义好的文本搜索配置对象，由以下几部分组成：

- parser
- dictionary 列表
- 针对不同 token 类型的 mapping 规则

## 除了 `simple` 之外，还有哪些能力

### `simple`

特点：

- 只做小写归一
- 可选停用词过滤
- 不做 stemming
- 不做 lemmatization

优点：

- 行为稳定，接近精确词项匹配
- 适合 SKU、订单号、版本号、术语代码等场景
- 对混合语言和技术文本比较保守

缺点：

- 单复数不合并
- 时态变化不合并
- 对自然语言英文检索召回偏弱

### `english` 及其他语言配置

例如：`english`、`french`、`german`、`spanish`。

特点：

- 带语言相关停用词处理
- 带词干化能力
- 会把派生词归一成相同 lexeme

优点：

- 对自然语言问句召回明显更好
- 英文单复数、时态变化、派生形式更容易命中
- 适合作为 support 文档、FAQ、政策说明这类英文语料的 sparse 检索基础

缺点：

- 可能对专有名词过度词干化
- 对混合语言语料不一定合适
- 某些业务关键词可能被当成停用词或被过度归一

### 自定义配置

PostgreSQL 还支持创建自定义文本搜索配置，而不必只能在 `simple` 和 `english` 之间二选一。

常见能力包括：

- `simple` dictionary：做基础 lower-case 和停用词过滤
- `snowball` dictionary：做 stemming，例如 `english_stem`
- `ispell` dictionary：做更丰富的词形归一
- `synonym` dictionary：把多个术语归一到一个词项
- `thesaurus` dictionary：支持短语级归一与扩展
- `unaccent` 过滤字典：先去除重音，再交给后续词典处理

这意味着我们可以构建更贴近业务的配置，例如：

- 先做 `unaccent`
- 再做业务同义词映射
- 最后再用 `english_stem` 做兜底词干化

## 本项目的选型建议

### 什么时候用 `english`

如果语料和查询主要是英文自然语言，`english` 更合适。比如：

- refund policy
- customer order tracking
- shipping updates
- return eligibility

在本项目里，它的直接收益是：

- `customer` 可以匹配 `customers`
- `returned` 和 `returning` 可以匹配 `return`
- sparse recall 能更好地补充 dense retrieval

### 什么时候继续用 `simple`

如果你更想要“接近关键词精确匹配”的行为，`simple` 仍然更合适。比如：

- SKU
- 工单编号
- 产品型号
- 版本号
- 混合语言或术语很重的文本

### 什么时候应该上自定义配置

如果语料同时具备下面两类特征，建议后续升级到自定义配置：

- 大量英文自然语言内容，需要 stemming 提高召回
- 同时又有大量领域术语，不能接受通用 stemming 误伤

这时单纯切 `simple` 或 `english` 都不够细，应该通过 synonym、thesaurus、unaccent、stemmer 组合出一套定制配置。

## 当前实现建议

基于当前语料特征，这次把 sparse retrieval 从 `simple` 切到 `english` 是合理的短期修复，因为：

- 当前文档内容主要是英文支持文档
- 当前 miss 的直接原因就是英文单复数不归一
- `english` 已经能在现有库上验证修复 `customer -> customers` 的召回问题

如果后续检索场景扩展到多语言或高术语密度语料，再考虑升级到自定义 PostgreSQL 文本搜索配置，而不是简单切回 `simple`。

## 配置化实现说明

当前代码已经把文本搜索配置做成应用配置项：

- 配置路径：`app.ai.rag.sparse.text-search-config`
- 默认值：`english`

对应实现通过 `CAST(? AS regconfig)` 把配置名作为 SQL 参数传入，而不是直接把配置名拼进 SQL 字符串。

这样做有两个好处：

- 避免了 `text block + append` 这类字符串拼接带来的 Java 语法问题
- 保留了配置化能力，后续可以从 `english` 切到 `simple` 或自定义 config，而不需要改代码

## 常用排查 SQL

查看可用配置：

```sql
SELECT cfgname FROM pg_catalog.pg_ts_config ORDER BY cfgname;
```

查看词项归一结果：

```sql
SELECT ts_debug('simple', 'customer customers returned returning');
SELECT ts_debug('english', 'customer customers returned returning');
```

查看查询归一结果：

```sql
SELECT websearch_to_tsquery('simple', 'customer');
SELECT websearch_to_tsquery('english', 'customer');
```

查看命中数量：

```sql
SELECT COUNT(*)
FROM vector_store
WHERE to_tsvector('english', content) @@ websearch_to_tsquery('english', 'customer');
```

## 参考资料

- PostgreSQL Full Text Search introduction
- PostgreSQL Controlling Text Search
- PostgreSQL Dictionaries
- PostgreSQL Configuration Example

本次整理参考的官方文档：

- https://www.postgresql.org/docs/current/textsearch-intro.html
- https://www.postgresql.org/docs/current/textsearch-controls.html
- https://www.postgresql.org/docs/current/textsearch-dictionaries.html
- https://www.postgresql.org/docs/current/textsearch-configuration.html