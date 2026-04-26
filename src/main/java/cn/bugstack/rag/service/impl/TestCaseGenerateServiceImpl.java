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

                ## ⚠️ 核心约束（严格遵守，否则生成结果无效）

                ### H1: 禁止编造原则（最高优先级）
                1. **只基于提供的参考资料生成用例**：每个用例的步骤和预期结果必须能在参考资料中找到明确依据
                2. **参考资料为空时的处理**：
                   - 如果【相关的项目知识文档】显示"无相关知识文档"，则只生成基于【需求内容】的用例
                   - 如果【需求内容】本身信息不足（如缺少步骤、字段定义），则生成的用例应标注"基于有限信息推断"
                   - **严禁编造**：不能创造需求文档中不存在的字段、按钮、流程、验证点
                3. **无法生成时的处理**：如果信息极度匮乏无法生成有效用例，返回空cases数组，并在summary中说明原因

                ### H2: 一致性校验要求
                1. 生成的用例必须与【需求内容】保持语义一致
                2. 用例中的字段名、按钮名、流程必须与参考文档完全匹配（大小写敏感）
                3. 禁止添加需求中不存在的功能点

                ### H3: 引用溯源要求
                1. 每个用例的tags中必须包含来源标识，如：["需求原文", "来源:xxx文档"]
                2. 如果用例来自【历史参考用例】，在tags中标注：["参考用例:#N"]
                3. 如果用例来自【知识文档】，在tags中标注：["知识库:#N"]

                ## JSON输出格式（严格遵守）
                {
                  "summary": {
                    "app": "目标App包名/标识，如com.example.app",
                    "page": "目标页面/模块名称",
                    "totalCases": 用例总数,
                    "testScope": "功能测试范围简述",
                    "informationSource": "信息来源于：需求原文|需求原文+历史用例|需求原文+知识库|有限信息推断",
                    "unInfoWarnings": ["无法验证的推断点1", "无法验证的推断点2"]
                  },
                  "cases": [
                    {
                      "id": "TC_001",
                      "title": "用例标题（清晰描述测试目标）",
                      "caseType": "功能测试|流程测试|异常测试|边界测试|UI测试|兼容性测试",
                      "designMethod": "等价类|边界值|场景法|因果图|错误推测|正交法",
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

                ## 测试用例设计方法论（综合运用多种方法保障覆盖率）
                1. **等价类划分法**：将输入域划分为有效等价类和无效等价类，每个等价类至少选取一个代表性值进行测试
                   - 有效等价类：符合需求规范的输入
                   - 无效等价类：不符合需求规范的输入（如格式错误、范围外、特殊字符等）
                2. **边界值分析法**：重点测试边界值及其附近的数据
                   - 包含：最小值、最大值、比最小值小1、比最大值大1
                   - 适用于：数值范围、字符串长度、数组大小等
                3. **场景法/流程分析法**：基于用户使用场景设计测试路径
                   - 基本流：正常业务流程
                   - 备选流：异常处理流程、替代操作流程
                4. **因果图/判定表法**：针对多个输入条件组合的测试
                   - 适用于存在输入组合且相互制约的场景
                   - 考虑：条件组合、依赖关系、互斥关系
                5. **错误推测法**：基于经验推测可能出现的错误
                   - 考虑：空值输入、重复提交、网络异常中断、历史数据残留等
                6. **正交实验法**：在多因素多水平场景下，用最少的用例覆盖最多的组合

                ## ⚠️ 强制思维链（Chain of Thought）：在输出任何测试用例 JSON 之前，你必须先完成以下推理步骤

### 步骤1：规则溯源
仔细阅读【需求内容】和【相关的项目知识文档】，列出所有与当前测试目标相关的业务规则，并标注每条规则的**来源文档**（如 sourceFile、文档名）。
**重要**：只列出文档中**明确包含**的规则，不要推理或扩展。

### 步骤2：信息完整性评估
评估当前需求是否包含以下必要信息：
- [ ] 操作步骤（用户如何完成这个功能）
- [ ] 字段定义（输入框、按钮等UI元素的名称）
- [ ] 验证规则（什么情况算成功，什么算失败）
- [ ] 异常处理（出错时如何处理）

如果某项缺失，在summary.unInfoWarnings中标注。

### 步骤3：冲突检测
评估列出的规则是否存在冲突。常见的冲突类型包括：
- **版本冲突**：文档A说"密码至少8位"，文档B说"密码至少6位"——此时**必须遵循"最新版本优先"原则**
- **字段冲突**：同一字段在不同文档中定义不同值
- **逻辑冲突**：前置条件相互矛盾的规则

### 步骤4：版本决断
如果存在冲突，严格执行以下优先级：
1. **最新版本号 / 最新更新时间优先**：版本号大的优先，更新时间晚的优先
2. **不生成折中值**：绝不生成"密码7位"这种两边都不符合的混合规则用例
3. **废弃规则不生成**：已在新版本中废弃的旧规则，**不生成**对应的测试用例

### 步骤5：生成决策声明
在正式开始生成 JSON 之前，你**必须**先输出以下格式的决策声明（写在 JSON 之前）：

```
<规则决策>
采用规则：来自【文档名v2.0】，规则内容：【具体规则】
废弃规则：来自【文档名v1.0】，原因：【版本已废弃】
冲突处理：采用最新版本，废弃旧版本
</规则决策>
```

### 步骤6：基于决断生成用例
只有在完成上述 5 个步骤后，才能基于最终决断的规则**生成且仅生成一套**测试用例 JSON。

---

                ## 生成要求（必须遵守）
                1. 先完成上面的【强制思维链】推理，再输出最终结果
                2. 在完成思维链后，只输出JSON（不接受任何理由拒绝）
                3. `<规则决策>` 标签是允许输出的，但 `<排除think>` 标签禁止输出
                4. 每条用例至少包含3-8个步骤
                5. 生成用例数量控制在%d以内
                6. 必须包含至少1个P0级别的用例
                7. 必须覆盖至少一种异常场景
                8. 涉及登录/支付等重要操作必须包含异常测试
                9. 每个用例的steps必须按顺序编号（order从1开始连续）
                10. assertions必须包含至少2个验证点
                11. **重要**：每输出一个完整的用例对象后，立即输出分隔符 `__CASE_END__`（无引号、无空格），以便前端逐条渲染
                12. **必须综合运用测试用例设计方法**：
                    - 每个用例需在tags中标注所使用的设计方法，如：["等价类", "边界值"]、["场景法"]、["错误推测"]
                    - 必须包含边界值测试用例（如：最小值、最大值、边界+1、边界-1）
                    - 必须包含无效等价类测试用例（如：空值、格式错误、范围外）
                    - 必须包含场景法用例（基本流+备选流）

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
