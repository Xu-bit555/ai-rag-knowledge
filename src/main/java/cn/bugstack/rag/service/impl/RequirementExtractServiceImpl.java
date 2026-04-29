package cn.bugstack.rag.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求提炼服务实现
 */
@Slf4j
@Service
public class RequirementExtractServiceImpl implements cn.bugstack.rag.service.RequirementExtractService {

    // 新格式：匹配 "[维度]：[模块] - [操作] → [预期结果]"
    private static final Pattern SCENARIO_PATTERN = Pattern.compile(
            "^[\\S]+[：:][^→]+→[^→]+$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 构建需求提炼的Prompt（增强版 - 多维度分析）
     * 输出格式贴近用例结构：模块 - 操作 → 预期结果
     */
    public String buildExtractPrompt(String rawContent) {
        return """
                # 角色：你是一位资深需求分析师，擅长从复杂需求文档中提取测试场景。

                ## 任务
                对【需求内容】进行多维度分析，输出可检索的测试场景描述。
                
                ## ⚠️ 核心约束（严格遵守）

                ### H1: 禁止推理扩展原则
                1. **只提取需求中明确包含的内容**，不要推理或扩展
                2. **禁止添加需求中不存在的**：
                   - 不存在的按钮、字段、页面
                   - 不存在的验证规则
                   - 不存在的异常提示文本
                3. **无法提取时的处理**：如果某个维度在需求中未明确提及，输出"未明确提及"，而非推断

                ### H2: 信息溯源要求
                1. 每个场景描述必须能在【需求内容】中找到原文依据
                2. 如果场景是直接引用自需求，使用"【原文】"标记
                3. 如果场景是基于多个段落推断，需要标注"【推断】"

                ## 分析维度（必须全部覆盖）
                1. **功能场景**：核心功能点和用户交互
                2. **业务流程**：完整的操作链路和状态变化
                3. **异常处理**：错误情况、边界条件、容错机制
                4. **数据验证**：输入校验、格式要求、长度限制
                5. **兼容性**：多平台、多版本、多网络环境

                ## 输出格式（严格遵守）
                对每个维度输出一行场景，格式为：

                [维度]：[模块/页面] - [具体操作] → [预期结果]

                ## 要求
                1. 只输出场景描述，每行一个，不要任何分析过程
                2. 每行必须包含"→"符号，箭头前是操作，箭头后是预期结果
                3. 不要使用任何标记（##、**、1.、- 等）
                4. 场景描述要具体，包含模块名、操作步骤、预期结果
                5. 如果某个维度不适用或需求中未提及，输出"未明确提及"

                ## 示例输出
                功能：用户登录页面 - 输入正确账号密码点击登录 → 登录成功并跳转首页
                功能：用户登录页面 - 点击获取验证码按钮 → 收到短信验证码
                异常：用户登录页面 - 输入错误验证码 → 提示"验证码错误，请重新输入"
                异常：用户登录页面 - 验证码过期 → 提示"验证码已过期，请重新获取"
                验证：用户注册页面 - 输入10位手机号 → 提示"手机号需为11位数字"
                验证：用户注册页面 - 输入非数字字符 → 提示"手机号只能输入数字"
                兼容：弱网环境 - 点击登录按钮 → 超时30秒后提示"网络不佳，请重试"
                流程：订单创建页面 - 选择商品确认订单选择支付完成支付 → 生成订单并跳转支付成功页

                ## ⚠️ 信息完整性检查（输出前必须确认）
                - [ ] 每个场景的操作步骤是否在需求中明确提及？
                - [ ] 每个场景的预期结果是否在需求中明确说明？
                - [ ] 是否存在编造的按钮名称、字段名称、验证规则？

                需求内容：
                """ + rawContent;
    }

    /**
     * 解析LLM返回的提炼结果
     * @param rawResult LLM返回的原始结果
     * @return 场景描述列表
     */
    public List<String> parseExtractedScenarios(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return List.of();
        }

        List<String> scenarios = new ArrayList<>();
        String[] lines = rawResult.split("\\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过空白行
            if (trimmed.isEmpty()) {
                continue;
            }

            // 跳过明显的说明性行
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("让我") || lower.startsWith("按照") ||
                lower.startsWith("这个") || lower.startsWith("要求") ||
                lower.startsWith("分析") || lower.startsWith("确认") ||
                lower.startsWith("输出") || lower.startsWith("以下") ||
                lower.startsWith("功能场景") && trimmed.contains("示例") ||
                lower.startsWith("任务") || lower.startsWith("##") ||
                lower.startsWith("# ") || lower.startsWith("**")) {
                continue;
            }

            // 匹配新格式："[维度]：[模块] - [操作] → [预期结果]"
            Matcher matcher = SCENARIO_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                // 清理格式：去掉列表标记
                String scenario = trimmed.replaceAll("^[\\-\\*\\d\\.]+\\s*", "");

                if (scenario.length() >= 10 && !scenarios.contains(scenario)) {
                    scenarios.add(scenario);
                }
            }
        }

        // 兜底：如果没有匹配到，尝试找包含"→"的行
        if (scenarios.isEmpty()) {
            for (String line : lines) {
                String trimmed = line.trim();
                // 清理思考标签
                trimmed = trimmed.replaceAll("<think>.*?</think>", "");
                if (trimmed.contains("→") && trimmed.length() >= 10) {
                    scenarios.add(trimmed);
                }
            }
        }

        log.debug("解析场景描述, 共找到: {} 个场景", scenarios.size());
        return scenarios;
    }

}
