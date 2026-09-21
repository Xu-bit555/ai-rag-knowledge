package cn.bugstack.rag.evaluation.dataset;

import cn.bugstack.rag.evaluation.pipeline.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 smoke 阶段的 evaluation/datasets/v1.jsonl。
 *
 * 10 条 query 覆盖 9 种 query_type（含重复 chinese / mixed_zh_en）：
 *  1. direct_match       (Redis Cluster 数据分片)
 *  2. direct_match       (用户登录流程)
 *  3. direct_match       (M4 Pro vs M2 性能)
 *  4. similar_irrelevant (Redis Cluster 认证 — 候选里只有 tangential)
 *  5. keyword_different_intent (密码长度)
 *  6. ambiguous          (Apple 性能 — 多产品混在一起)
 *  7. no_answer          (iOS 开发 — Frozen Top-30 内全无关 → retrieval_failure)
 *  8. mixed_zh_en        (SpringBoot datasource)
 *  9. confusing_similar  (Redis 数据分片 vs Sentinel 高可用)
 * 10. long_context       (分布式任务调度系统)
 *
 * 每个 query 配 8-12 个 candidate，候选文本独立 hash 出 candidate_id。
 * ground truth 由人工定义（per plan §二十六 6：禁止 LLM 自动批量标）。
 *
 * 用法：运行 SmokeDatasetBuilder.write(Path) 一次性生成 v1.jsonl；之后由 DatasetLoader 读。
 */
public final class SmokeDatasetBuilder {

    private SmokeDatasetBuilder() {}

    public static void write(Path jsonlPath) throws IOException {
        Files.createDirectories(jsonlPath.getParent());
        List<Map<String, Object>> queries = build();
        ObjectMapper mapper = new ObjectMapper();
        try (var w = Files.newBufferedWriter(jsonlPath)) {
            for (Map<String, Object> q : queries) {
                w.write(mapper.writeValueAsString(q));
                w.newLine();
            }
        }
    }

    /**
     * Build queries in-memory. candidate_id 自动 = SHA256(text).
     * ground_truth label 的 candidate_id 用同样算法；调用方传入原始 text 列表，由 buildOne 内部 hash。
     */
    static List<Map<String, Object>> build() {
        List<Map<String, Object>> out = new ArrayList<>();

        // Q1: Redis Cluster 数据分片（direct_match）
        out.add(buildOne(
                "q001",
                "Redis Cluster 如何实现数据分片？",
                "default",
                "direct_match",
                List.of("chinese", "single_hop"),
                List.of(
                        new C("Redis Cluster 使用 hash slots 将 key 空间划分为 16384 个槽位，并将这些槽位分配给不同节点。", 3),
                        new C("当客户端访问某个 key 时，Cluster 使用 CRC16 算法计算 key 的 hash 值，然后对 16384 取模得到目标 slot。", 3),
                        new C("Redis 主从复制通过异步方式将主节点的数据同步到从节点，用于提高读性能。", 0),
                        new C("Redis Sentinel 提供高可用方案，监控主从节点并在故障时自动切换。", 0),
                        new C("Memcached 使用一致性 hash 算法在多个节点间分布数据。", 0),
                        new C("MySQL 的 InnoDB 引擎使用 B+ 树作为索引数据结构。", 0),
                        new C("Kafka 的 partition leader 选举由 ZooKeeper 协调。", 0),
                        new C("etcd 使用 Raft 一致性算法保证集群数据一致性。", 0)),
                List.of(0, 1)));

        // Q2: 用户登录流程（direct_match，复用 RerankModelComparisonTest 第一条 query）
        out.add(buildOne(
                "q002",
                "用户登录流程是什么",
                "default",
                "direct_match",
                List.of("chinese", "single_hop"),
                List.of(
                        new C("用户输入用户名和密码后，系统首先查询数据库验证凭证有效性。", 3),
                        new C("登录成功后系统会创建 Session，并将用户信息存入会话上下文。", 3),
                        new C("用户协议规定了隐私保护条款，请仔细阅读。", 0),
                        new C("客服响应时间企业用户 2 小时，个人用户 24 小时。", 0),
                        new C("密码找回需要验证注册邮箱。", 0),
                        new C("系统通知功能会推送营销活动信息。", 0),
                        new C("用户设置页面可以修改头像和昵称。", 0),
                        new C("产品退换货政策为 7 天无理由。", 0)),
                List.of(0, 1)));

        // Q3: M4 Pro vs M2 性能（direct_match + 多实体）
        out.add(buildOne(
                "q003",
                "M4 Pro 芯片相比 M2 在 GPU 上的提升是多少？",
                "default",
                "direct_match",
                List.of("multi_entity", "single_hop"),
                List.of(
                        new C("M4 Pro 的 CPU 性能相比 M2 提升 50%，GPU 性能提升 40%。", 3),
                        new C("Apple M4 Pro 芯片采用第二代 3 纳米工艺制造，集成 12 核 CPU。", 2),
                        new C("M2 芯片发布于 2022 年，采用 5 纳米工艺。", 1),
                        new C("Apple Watch Series 9 支持手势操作和血氧检测。", 0),
                        new C("iPhone 15 Pro Max 独占 5 倍长焦镜头。", 0),
                        new C("AirPods Pro 2 配备 H2 芯片，主动降噪能力翻倍。", 0),
                        new C("MacBook Pro 14 英寸版配备 M3 Pro 芯片，电池续航可达 18 小时。", 0)),
                List.of(0, 1, 2)));

        // Q4: Redis Cluster 认证（similar_irrelevant：候选只有 tangential）
        out.add(buildOne(
                "q004",
                "Redis Cluster 如何启用 ACL 认证？",
                "default",
                "similar_irrelevant",
                List.of("chinese"),
                List.of(
                        new C("Redis Cluster 使用 hash slots 将 key 空间划分为 16384 个槽位。", 1),
                        new C("当客户端访问某个 key 时，Cluster 使用 CRC16 算法计算 key 的 hash 值。", 1),
                        new C("Redis Sentinel 提供高可用方案。", 0),
                        new C("Redis 主从复制通过异步方式同步数据。", 0),
                        new C("MySQL 用户权限由 GRANT/REVOKE 管理。", 0),
                        new C("OAuth 2.0 协议定义了四种授权模式。", 0),
                        new C("JWT token 由 header / payload / signature 三部分组成。", 0),
                        new C("Kerberos 是一种基于票据的认证协议。", 0)),
                List.of(0, 1)));

        // Q5: 密码长度（keyword_different_intent）
        out.add(buildOne(
                "q005",
                "密码至少需要几位",
                "default",
                "keyword_different_intent",
                List.of("chinese"),
                List.of(
                        new C("密码长度至少为 8 位，必须包含大小写字母和数字。", 3),
                        new C("密码字段在数据库中以 bcrypt 哈希存储。", 2),
                        new C("用户注册时需要额外填写邮箱进行二次验证。", 0),
                        new C("连续登录失败超过 5 次会临时锁定账户 30 分钟。", 0),
                        new C("会话超时时间为 30 分钟。", 0),
                        new C("个人信息包含姓名、手机号、地址。", 0),
                        new C("用户协议规定了隐私保护条款。", 0)),
                List.of(0, 1)));

        // Q6: Apple 性能（ambiguous：query 太短，多 product 候选混在一起）
        out.add(buildOne(
                "q006",
                "Apple 性能",
                "default",
                "ambiguous",
                List.of("chinese", "single_word"),
                List.of(
                        new C("M4 Pro 的 CPU 性能相比 M2 提升 50%，GPU 性能提升 40%。", 2),
                        new C("iPhone 15 Pro 配备 A17 Pro 芯片，采用台积电 3nm 工艺。", 2),
                        new C("iPad Pro 搭载 M2 芯片，支持 ProMotion 120Hz 自适应刷新率。", 2),
                        new C("MacBook Pro 14 英寸版配备 M3 Pro 芯片，电池续航可达 18 小时。", 2),
                        new C("Apple Vision Pro 使用 M2 芯片和专用 R1 芯片处理实时传感器数据。", 2),
                        new C("客服工单系统支持 SLA 自动升级和满意度评价。", 0),
                        new C("知识库系统使用全文检索 + 向量检索混合方式。", 0),
                        new C("实时聊天工具支持多人协作会话。", 0)),
                List.of(0, 1, 2, 3, 4)));

        // Q7: iOS 开发（no_answer → retrieval_failure；候选里全无关）
        out.add(buildOne(
                "q007",
                "iOS App 如何接入 OAuth 2.0？",
                "default",
                "no_answer",
                List.of("chinese", "single_hop"),
                List.of(
                        new C("Redis Cluster 使用 hash slots 将 key 空间划分为 16384 个槽位。", 0),
                        new C("Spring Boot 内嵌 Tomcat，无需单独部署 Servlet 容器。", 0),
                        new C("退款流程：用户在订单详情页点击申请退款按钮，填写退款原因。", 0),
                        new C("客服成功团队 SLA：企业用户享有优先客服通道。", 0),
                        new C("密码长度至少为 8 位，必须包含大小写字母和数字。", 0),
                        new C("Apple Watch Series 9 支持手势操作和血氧检测。", 0),
                        new C("iPhone 15 Pro 配备 A17 Pro 芯片。", 0)),
                List.of()));  // GT 为空 → retrieval_failure

        // Q8: SpringBoot datasource（mixed_zh_en）
        out.add(buildOne(
                "q008",
                "SpringBoot 如何配置 datasource？",
                "default",
                "direct_match",
                List.of("mixed_zh_en"),
                List.of(
                        new C("Spring Boot 应用启动时默认会扫描 @SpringBootApplication 注解所在包及其子包下的组件。", 1),
                        new C("可以通过 spring.datasource.url / username / password 配置数据库连接。", 3),
                        new C("HikariCP 是 Spring Boot 2.x 默认的 JDBC connection pool。", 3),
                        new C("JPA 实体类需要使用 @Entity 注解标注。", 1),
                        new C("MyBatis 使用 XML 或注解方式定义 SQL 映射。", 1),
                        new C("Spring Cloud Config 提供分布式配置中心。", 0),
                        new C("Kafka producer 通过 acks 参数控制消息持久化级别。", 0),
                        new C("Redis Sentinel 提供高可用方案。", 0)),
                List.of(1, 2)));

        // Q9: Redis 数据分片 vs Sentinel（confusing_similar：双主题候选混一起）
        out.add(buildOne(
                "q009",
                "Redis 数据分片和 Sentinel 高可用的区别是什么？",
                "default",
                "confusing_similar",
                List.of("chinese", "multi_topic"),
                List.of(
                        new C("Redis Cluster 使用 hash slots 将 key 空间划分为 16384 个槽位，实现数据分片。", 3),
                        new C("Redis Sentinel 是独立的高可用方案，不负责数据分片。", 3),
                        new C("Redis Sentinel 通过监控主从节点并在故障时自动切换实现高可用。", 2),
                        new C("Redis Cluster 节点间使用 Gossip 协议通信。", 2),
                        new C("Redis 主从复制通过异步方式将主节点的数据同步到从节点。", 1),
                        new C("Kafka 的 partition leader 选举由 ZooKeeper 协调。", 0),
                        new C("MySQL Group Replication 是 MySQL 自带的同步复制方案。", 0),
                        new C("etcd 使用 Raft 一致性算法保证集群数据一致性。", 0)),
                List.of(0, 1, 2, 3)));

        // Q10: 分布式任务调度系统（long_context）
        out.add(buildOne(
                "q010",
                "需要实现一个分布式任务调度系统，支持定时任务、失败重试、并发控制。如何设计？",
                "default",
                "long_context",
                List.of("chinese", "multi_hop"),
                List.of(
                        new C("Quartz Scheduler 提供 Cron 表达式定时任务，支持任务持久化到数据库。", 2),
                        new C("XXL-JOB 是国内开源的分布式任务调度平台，支持分片广播、失败重试。", 3),
                        new C("Elastic-Job 配合 Zookeeper 实现分布式协调和分片任务。", 2),
                        new C("Spring Task 提供 @Scheduled 注解但仅限单机。", 1),
                        new C("消息队列（如 Kafka）可作为任务队列实现异步分发。", 1),
                        new C("Redis Cluster 使用 hash slots 将 key 空间划分为 16384 个槽位。", 0),
                        new C("Spring Boot 内嵌 Tomcat。", 0),
                        new C("客服响应时间企业用户 2 小时。", 0)),
                List.of(1, 2, 3)));

        return out;
    }

    private static Map<String, Object> buildOne(String qid, String query, String ragTag,
                                                String queryType, List<String> queryTypeTags,
                                                List<C> candidates, List<Integer> correctIndices) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("query_id", qid);
        row.put("query", query);
        row.put("rag_tag", ragTag);
        row.put("query_type", queryType);
        row.put("query_type_tags", queryTypeTags);

        // candidates：包含 text + retrieval_score + rag_rank（按给定顺序）
        List<Map<String, Object>> candList = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            C c = candidates.get(i);
            String cid = RetrievalCandidate.sha256(c.text());
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("candidate_id", cid);
            cm.put("source_chunk_id", null);
            cm.put("document_id", null);
            cm.put("text", c.text);
            cm.put("retrieval_score", c.retrievalScore);
            cm.put("rag_rank", i + 1);
            candList.add(cm);
        }
        row.put("candidates", candList);

        // ground_truth：只对 correctIndices 标 relevance；其他 candidate 也可选择性标
        List<Map<String, Object>> gt = new ArrayList<>();
        String date = "2026-09-21";
        for (int idx : correctIndices) {
            C c = candidates.get(idx);
            String cid = RetrievalCandidate.sha256(c.text());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("candidate_id", cid);
            g.put("source_chunk_id", null);
            g.put("document_id", null);
            g.put("relevance", c.relevance);
            g.put("annotation_source", "human");
            g.put("annotation_version", "v1");
            g.put("annotation_date", date);
            g.put("annotation_notes", null);
            gt.add(g);
        }
        // 把其他 candidate 也补全为 0（label 0 = irrelevant，方便 metrics 全量覆盖）
        for (int i = 0; i < candidates.size(); i++) {
            if (correctIndices.contains(i)) continue;
            C c = candidates.get(i);
            String cid = RetrievalCandidate.sha256(c.text());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("candidate_id", cid);
            g.put("source_chunk_id", null);
            g.put("document_id", null);
            g.put("relevance", c.relevance);  // 直接采用构造时给定的 label
            g.put("annotation_source", "human");
            g.put("annotation_version", "v1");
            g.put("annotation_date", date);
            g.put("annotation_notes", null);
            gt.add(g);
        }
        row.put("ground_truth", gt);
        return row;
    }

    /** Internal helper for builder */
    private static final class C {
        final String text;
        final int relevance;
        final double retrievalScore;
        C(String text, int relevance) {
            this.text = text;
            this.relevance = relevance;
            this.retrievalScore = 1.0 - relevance * 0.1;  // 简化的 retrieval score（不参与 metrics）
        }
        C(String text, int relevance, double retrievalScore) {
            this.text = text;
            this.relevance = relevance;
            this.retrievalScore = retrievalScore;
        }
        String text() { return text; }
    }
}