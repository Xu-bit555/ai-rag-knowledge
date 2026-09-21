package cn.bugstack.rag.adapter.playwright;

import cn.bugstack.rag.core.domain.dsl.v1.TargetLocator;
import cn.bugstack.rag.core.domain.dsl.v1.enums.LocatorStrategyKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TargetResolver - DSL Target → Playwright Snapshot ref
 *
 * 输入: DSL TargetLocator + 最近一次 A11y Snapshot
 * 输出: Playwright MCP 可识别的 ref 字符串 (如 "e17")
 *
 * 策略匹配顺序:
 *   TESTID → LABEL → ROLE → TEXT → CSS → XPATH
 *
 * Snapshot 结构(Playwright MCP):
 * {
 *   "role": "WebArea", "name": "page",
 *   "children": [
 *     {"role": "button", "name": "登录", "ref": "e17"},
 *     {"role": "textbox", "name": "用户名", "ref": "e15", "attributes": {"data-testid": "username-input"}}
 *   ]
 * }
 */
@Slf4j
@Component
public class TargetResolver {

    /**
     * 在 snapshot 中找匹配 ref
     *
     * @return ref 字符串,无匹配返回 null
     */
    @SuppressWarnings("unchecked")
    public String resolve(TargetLocator target, Map<String, Object> snapshot) {
        if (target == null || snapshot == null || snapshot.isEmpty()) return null;
        LocatorStrategyKind strategy = target.getStrategy();
        if (strategy == null) return null;

        // 展平 snapshot
        List<Map<String, Object>> allNodes = new ArrayList<>();
        flatten(snapshot, allNodes);

        // 策略匹配(按优先级)
        return switch (strategy) {
            case TESTID -> matchTestId(allNodes, target.getTestId());
            case LABEL -> matchLabel(allNodes, target);
            case ROLE -> matchRole(allNodes, target);
            case TEXT -> matchText(allNodes, target);
            case CSS -> null;       // CSS locator 直接传入 Playwright,不需要 ref
            case XPATH -> null;      // 同上
        };
    }

    private String matchTestId(List<Map<String, Object>> nodes, String testId) {
        if (testId == null) return null;
        for (Map<String, Object> n : nodes) {
            Object attrs = n.get("attributes");
            if (attrs instanceof Map<?, ?> a) {
                Object v = ((Map<String, Object>) a).get("data-testid");
                if (testId.equals(v)) {
                    return (String) n.get("ref");
                }
            }
        }
        return null;
    }

    private String matchLabel(List<Map<String, Object>> nodes, TargetLocator target) {
        String label = target.getLabel() != null ? target.getLabel() : target.getText();
        if (label == null) return null;
        for (Map<String, Object> n : nodes) {
            if (label.equalsIgnoreCase((String) n.get("name"))
                    && n.get("role") != null) {
                String role = (String) n.get("role");
                if (role.equals("textbox") || role.equals("combobox")
                        || role.equals("searchbox") || role.equals("spinbutton")) {
                    return (String) n.get("ref");
                }
            }
        }
        return null;
    }

    private String matchRole(List<Map<String, Object>> nodes, TargetLocator target) {
        String role = target.getRole();
        String name = target.getName();
        if (role == null) return null;
        Boolean exact = target.getExact() != null ? target.getExact() : false;
        for (Map<String, Object> n : nodes) {
            if (!role.equals(n.get("role"))) continue;
            if (name == null) return (String) n.get("ref");
            String nodeName = (String) n.get("name");
            if (exact) {
                if (name.equals(nodeName)) return (String) n.get("ref");
            } else {
                if (nodeName != null && nodeName.toLowerCase().contains(name.toLowerCase())) {
                    return (String) n.get("ref");
                }
            }
        }
        return null;
    }

    private String matchText(List<Map<String, Object>> nodes, TargetLocator target) {
        String text = target.getText();
        if (text == null) return null;
        Boolean exact = target.getExact() != null ? target.getExact() : false;
        for (Map<String, Object> n : nodes) {
            String nodeName = (String) n.get("name");
            if (nodeName == null) continue;
            if (exact ? text.equals(nodeName) : nodeName.toLowerCase().contains(text.toLowerCase())) {
                return (String) n.get("ref");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void flatten(Map<String, Object> node, List<Map<String, Object>> out) {
        if (node == null) return;
        out.add(node);
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> m) flatten((Map<String, Object>) m, out);
            }
        }
    }
}