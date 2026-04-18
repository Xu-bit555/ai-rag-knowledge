package cn.bugstack.rag.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * 测试用例生成服务实现
 */
@Slf4j
public class TestCaseGenerateServiceImpl implements cn.bugstack.rag.service.TestCaseGenerateService {

    @Value("${spring.ai.rag.max-cases:10}")
    private int maxCases;

    /**
     * 构建测试用例生成Prompt
     */
    @Override
    public String buildGeneratePrompt(String requirement, List<String> referenceCases) {
        return buildGeneratePrompt(requirement, referenceCases, null);
    }

    /**
     * 构建测试用例生成Prompt（增强版 - 同时支持知识库文档）
     */
    @Override
    public String buildGeneratePrompt(String requirement, List<String> referenceCases, List<String> knowledgeDocs) {
        String referenceStr = buildReferenceCasesContext(referenceCases);
        String knowledgeStr = buildKnowledgeContext(knowledgeDocs);

        return """
                # 角色：你是一位资深测试架构师，擅长将复杂需求转化为高质量、全覆盖的移动端测试用例。

                ## 任务
                根据【需求内容】生成测试用例，必须输出标准JSON格式。

                ## JSON输出格式（严格遵守）
                {
                  "summary": {
                    "app": "目标App包名/标识，如com.example.app",
                    "page": "目标页面/模块名称",
                    "totalCases": 用例总数,
                    "testScope": "功能测试范围简述"
                  },
                  "cases": [
                    {
                      "id": "TC_001",
                      "title": "用例标题（清晰描述测试目标）",
                      "caseType": "功能测试|流程测试|异常测试|边界测试|UI测试|兼容性测试",
                      "priority": "P0|P1|P2|P3",
                      "risk": "高|中|低",
                      "precondition": ["前置条件1", "前置条件2"],
                      "testData": {"key": "value", "说明": "测试数据描述"},
                      "steps": [
                        {
                          "order": 1,
                          "action": "操作类型枚举值（见下方列表）",
                          "target": "目标元素描述",
                          "value": "输入值（可选）",
                          "expectedResult": "该步预期结果"
                        }
                      ],
                      "expectedOutcome": "整体用例的最终预期结果",
                      "assertions": ["验证点1", "验证点2"],
                      "tags": ["标签1", "标签2"]
                    }
                  ]
                }

                ## MobileAction 操作枚举（严格使用下列值）
                - CLICK: 点击操作
                - LONG_PRESS: 长按操作
                - DOUBLE_CLICK: 双击操作
                - INPUT: 输入文本
                - CLEAR_INPUT: 清空输入框
                - SWIPE_UP: 向上滑动
                - SWIPE_DOWN: 向下滑动
                - SWIPE_LEFT: 向左滑动
                - SWIPE_RIGHT: 向右滑动
                - OPEN_APP: 启动App
                - CLOSE_APP: 关闭App
                - BACK: 返回操作
                - HOME: 按Home键
                - SCREENSHOT: 截图
                - WAIT: 等待（N秒）
                - ASSERT_ELEMENT_PRESENT: 断言元素存在
                - ASSERT_ELEMENT_NOT_PRESENT: 断言元素不存在
                - ASSERT_TEXT: 断言文本内容
                - ASSERT_VALUE: 断言输入框值
                - PERMISSIONS_GRANT: 授予权限
                - PERMISSIONS_DENY: 拒绝权限
                - NETWORK_CHANGE: 切换网络（2G/3G/4G/5G/WiFi）
                - ROTATION: 屏幕旋转（PORTRAIT/LANDSCAPE）

                ## 用例生成质量标准
                1. **完整性**：覆盖正常流程、异常流程、边界条件、兼容性
                2. **可执行性**：每个步骤清晰可操作，预期结果明确
                3. **独立性**：用例之间无依赖，可独立执行
                4. **可验证性**：每个用例都有明确的断言点

                ## 用例优先级定义
                - P0（最高）: 核心功能冒烟测试，必须通过
                - P1（高）: 重要功能测试，涉及主要业务流程
                - P2（中）: 一般功能测试，涉及次要功能
                - P3（低）: 边界/UI测试，可选执行

                ## 生成要求（必须遵守）
                1. 只输出JSON，不要包含任何解释、前后缀、代码块标记
                2. 严禁输出 <think> 或任何思考过程
                3. 每条用例至少包含3-8个步骤
                4. 生成用例数量控制在%d以内
                5. 必须包含至少1个P0级别的用例
                6. 必须覆盖至少一种异常场景
                7. 涉及登录/支付等重要操作必须包含异常测试
                8. 每个用例的steps必须按顺序编号（order从1开始连续）
                9. assertions必须包含至少2个验证点
                10. **重要**：每输出一个完整的用例对象后，立即输出分隔符 `__CASE_END__`（无引号、无空格），以便前端逐条渲染

                ## 需求内容
                %s

                ## 可参考的历史用例（如有）
                %s

                ## 相关的项目知识文档（如有）
                %s

                请严格按照上述格式输出JSON，确保：
                - JSON语法正确可解析
                - 所有必填字段都有值
                - steps中的action必须使用枚举值
                - 输出内容只包含JSON，不要有任何其他文字
                """.formatted(maxCases, requirement, referenceStr, knowledgeStr);
    }

    /**
     * 构建参考用例上下文
     */
    private String buildReferenceCasesContext(List<String> referenceCases) {
        if (referenceCases == null || referenceCases.isEmpty()) {
            return "无历史用例参考";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是相似功能的已有测试用例，供学习和参考格式：\n\n");

        for (int i = 0; i < referenceCases.size(); i++) {
            String jsonCase = referenceCases.get(i);
            // 格式化输出，避免JSON被压缩成一行的
            sb.append("【参考用例").append(i + 1).append("】:\n");
            sb.append(formatJson(jsonCase));
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建知识库文档上下文
     * 改进：按语义完整句子截断，避免破坏语义完整性
     */
    private String buildKnowledgeContext(List<String> knowledgeDocs) {
        if (knowledgeDocs == null || knowledgeDocs.isEmpty()) {
            return "无相关知识文档";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是相关的项目知识文档，请结合这些上下文生成用例：\n\n");

        for (int i = 0; i < Math.min(knowledgeDocs.size(), 3); i++) { // 限制3条，减少token消耗
            String doc = knowledgeDocs.get(i);
            // 按句子截断，确保语义完整
            String truncated = truncateBySentence(doc, 800);
            sb.append("【知识文档").append(i + 1).append("】:\n");
            sb.append(truncated);
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 按句子截断文本，确保语义完整
     */
    private String truncateBySentence(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }

        // 查找最后一个完整句子结束符
        int lastPeriod = text.substring(0, maxLength).lastIndexOf('。');
        int lastQuestion = text.substring(0, maxLength).lastIndexOf('？');
        int lastExclaim = text.substring(0, maxLength).lastIndexOf('！');
        int lastNewline = text.substring(0, maxLength).lastIndexOf('\n');

        int cutPoint = lastPeriod;
        if (lastQuestion > cutPoint) cutPoint = lastQuestion;
        if (lastExclaim > cutPoint) cutPoint = lastExclaim;
        if (lastNewline > cutPoint) cutPoint = lastNewline;

        // 如果找不到任何断句点，使用硬截断
        if (cutPoint < 0 || cutPoint < maxLength - 200) {
            cutPoint = maxLength;
        }

        return text.substring(0, cutPoint + 1);
    }

    /**
     * 格式化JSON以便阅读
     */
    private String formatJson(String json) {
        try {
            // 尝试解析并重新格式化
            Object obj = com.alibaba.fastjson2.JSON.parse(json);
            return com.alibaba.fastjson2.JSON.toJSONString(obj,
                    com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        } catch (Exception e) {
            // 如果解析失败，返回原文
            return json;
        }
    }

}
