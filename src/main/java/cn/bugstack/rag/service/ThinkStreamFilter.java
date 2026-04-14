package cn.bugstack.rag.service;

import lombok.extern.slf4j.Slf4j;

/**
 * 思考内容过滤器 - 用于移除AI思考过程
 */
@Slf4j
public class ThinkStreamFilter {

    /**
     * 过滤流式输出中的思考内容
     * 处理包含 <think> 和 </think> 标签的内容
     */
    public static String filterThinkContent(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        boolean inThink = false;
        int i = 0;

        while (i < s.length()) {
            if (!inThink) {
                int open = s.indexOf("<think>", i);

                if (open < 0) {
                    out.append(s.substring(i));
                    break;
                }

                out.append(s, i, open);
                i = open + 6;
                inThink = true;
            } else {
                int close = s.indexOf("</think>", i);
                if (close < 0) {
                    break;
                }
                i = close + 6;
                inThink = false;
            }
        }

        String result = out.toString();
        return result.replace("<think>", "").replace("</think>", "").trim();
    }

    /**
     * 移除思考标签
     */
    public static String stripThink(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // 先移除标准思考标签
        String result = input.replace("<think>", "").replace("</think>", "");

        // 如果移除后是纯JSON，直接返回
        result = result.trim();
        if (result.startsWith("{")) {
            return result;
        }

        // 如果不是纯JSON，尝试提取JSON部分
        // 查找 {"cases": 或 {"caseList": 模式
        int jsonStart = -1;
        if (result.contains("\"cases\":")) {
            jsonStart = result.indexOf("\"cases\":");
        } else if (result.contains("\"caseList\":")) {
            jsonStart = result.indexOf("\"caseList\":");
        } else if (result.contains("\"data\":")) {
            jsonStart = result.indexOf("\"data\":");
        }

        if (jsonStart > 0) {
            // 从 {" 往前找
            int braceStart = result.lastIndexOf("{", jsonStart);
            if (braceStart >= 0) {
                String jsonPart = result.substring(braceStart);
                // 尝试解析确认是有效JSON
                try {
                    // 简单验证：检查是否以 { 开头
                    jsonPart = jsonPart.trim();
                    if (jsonPart.startsWith("{")) {
                        return jsonPart;
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
        }

        // 尝试查找 markdown 代码块 ```json ... ```
        int codeStart = result.indexOf("```json");
        if (codeStart >= 0) {
            int contentStart = codeStart + 7;
            int codeEnd = result.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String jsonPart = result.substring(contentStart, codeEnd).trim();
                try {
                    if (jsonPart.startsWith("{")) {
                        return jsonPart;
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
        }

        return result;
    }

    /**
     * 实例方法，用于方法引用
     */
    public String apply(String input) {
        return filterThinkContent(input);
    }

}
