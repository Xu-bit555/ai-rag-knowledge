package cn.bugstack.rag.evaluation.judge;

/**
 * Jev prompt 模板（per plan §十 决策 4：沿用 /Users/xc/jev-test/index.mts 已验证的措辞）。
 *
 * 任何 prompt 改动必须 bump VERSION_STRING — cache key 含此字段，改动会触发 cache 全量重算。
 */
public final class JevPrompt {

    /**
     * 当前 prompt 版本。每次措辞改动 bump。
     */
    public static final String VERSION = "v1";

    /**
     * instructions 字段原文，与 /Users/xc/jev-test/index.mts 一字不差。
     */
    public static final String INSTRUCTIONS =
            "判断 passage 对回答 question 的相关程度。";

    private JevPrompt() {}

    /**
     * criteria 字段：4 个选项的 record。v1 用 null 表示"无 description"。
     * 与 /Users/xc/jev-test/index.mts 的 criteria 字段完全一致。
     *
     * 注意：Map.of() 不允许 null value，必须用 HashMap。
     */
    public static final java.util.Map<String, String> CRITERIA;
    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("irrelevant",        null);
        m.put("tangential",        null);
        m.put("useful",            null);
        m.put("directly_answers",  null);
        CRITERIA = java.util.Collections.unmodifiableMap(m);
    }
}