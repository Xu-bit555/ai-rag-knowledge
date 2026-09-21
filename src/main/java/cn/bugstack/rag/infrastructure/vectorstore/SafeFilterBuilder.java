package cn.bugstack.rag.infrastructure.vectorstore;

import java.util.Map;

/**
 * Spring AI filterExpression 安全的字符串过滤器
 *
 * P0-11 修复: filterExpression 内部生成 SQL WHERE 条件,ragTag / caseId 等用户可控字符串
 *   含单引号 ' 可破坏 SQL (注入). 本工具对字符串字面量做 SQL 标准转义 (单引号 → 两个单引号).
 *
 * 用法:
 *   SafeFilterBuilder.byKnowledge(ragTag)
 *   SafeFilterBuilder.byKnowledgeAndType(ragTag, "test_case")
 *   SafeFilterBuilder.build(Map.of("knowledge", ragTag, "type", "test_case", "adoptionStatus", "ADOPTED"))
 */
public final class SafeFilterBuilder {

    private SafeFilterBuilder() {}

    /**
     * 转义 SQL 字符串字面量内的单引号 (Spring AI filterExpression 使用 ' 作字符串边界)
     * 规则: ' → '' (SQL 标准, 与 PostgreSQL 一致)
     */
    public static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("'", "''");
    }

    /**
     * 构造安全的 filter expression
     *
     * @param conditions 字段-值对, 值会被 escape
     * @return filterExpression 字符串, 如 "knowledge == 'test' AND type == 'knowledge'"
     */
    public static String build(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            if (!first) sb.append(" AND ");
            sb.append(entry.getKey())
              .append(" == '")
              .append(escape(entry.getValue()))
              .append("'");
            first = false;
        }
        return sb.toString();
    }

    /** 单字段快捷: knowledge == 'ragTag' */
    public static String byKnowledge(String ragTag) {
        return "knowledge == '" + escape(ragTag) + "'";
    }

    /** 双字段: knowledge == 'ragTag' AND type == 'type' */
    public static String byKnowledgeAndType(String ragTag, String type) {
        return build(Map.of("knowledge", ragTag, "type", type));
    }

    /** 三字段 (用例历史召回): knowledge + type + adoptionStatus */
    public static String byKnowledgeTypeAdoptionStatus(String ragTag, String type, String adoptionStatus) {
        return build(Map.of(
                "knowledge", ragTag,
                "type", type,
                "adoptionStatus", adoptionStatus));
    }
}
