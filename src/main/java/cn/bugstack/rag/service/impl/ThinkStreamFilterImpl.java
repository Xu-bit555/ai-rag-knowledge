package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.ThinkStreamFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 思考内容过滤器实现 - 用于移除AI思考过程
 */
@Slf4j
@Service
public class ThinkStreamFilterImpl implements ThinkStreamFilter {

    @Override
    public String filterThinkContent(String s) {
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

    @Override
    public String stripThink(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input.replace("<think>", "").replace("</think>", "");

        result = result.trim();
        if (result.startsWith("{")) {
            return result;
        }

        int jsonStart = -1;
        if (result.contains("\"cases\":")) {
            jsonStart = result.indexOf("\"cases\":");
        } else if (result.contains("\"caseList\":")) {
            jsonStart = result.indexOf("\"caseList\":");
        } else if (result.contains("\"data\":")) {
            jsonStart = result.indexOf("\"data\":");
        }

        if (jsonStart > 0) {
            int braceStart = result.lastIndexOf("{", jsonStart);
            if (braceStart >= 0) {
                String jsonPart = result.substring(braceStart);
                try {
                    jsonPart = jsonPart.trim();
                    if (jsonPart.startsWith("{")) {
                        return jsonPart;
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
        }

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

    @Override
    public String apply(String input) {
        return filterThinkContent(input);
    }
}