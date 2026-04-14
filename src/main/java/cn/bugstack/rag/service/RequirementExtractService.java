package cn.bugstack.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求提炼服务
 * 负责将原始需求文档提炼为多维度、结构化的场景描述
 */
@Slf4j
@Service
public class RequirementExtractService {

    // 场景提取的正则模式
    private static final Pattern SCENARIO_PATTERN = Pattern.compile("在([^，,，]+)[中，,]", Pattern.CASE_INSENSITIVE);

    /**
     * 构建需求提炼的Prompt（增强版 - 多维度分析）
     */
    public String buildExtractPrompt(String rawContent) {
        return """
                # 角色：你是一位资深需求分析师，擅长从复杂需求文档中提取关键信息。

                ## 任务
                对【需求内容】进行全面的结构化分析，并输出多个维度的场景描述。

                ## 分析维度（必须全部覆盖）
                1. **功能场景**：核心功能点和用户交互
                2. **业务流程**：完整的操作链路和状态变化
                3. **异常处理**：错误情况、边界条件、容错机制
                4. **数据验证**：输入校验、格式要求、长度限制
                5. **兼容性**：多平台、多版本、多网络环境

                ## 输出格式（严格遵守）
                对每个维度输出一行场景描述，格式为：

                功能场景：在[模块/页面]中，[用户操作]和[系统响应]
                业务流程：在[模块/页面]中，[完整流程描述]包括[步骤1]→[步骤2]→[步骤3]
                异常处理：在[模块/页面]中，当[异常条件]时，[系统如何处理]
                数据验证：在[模块/页面]中，输入[非法数据]时，[验证规则]
                兼容性：在[环境]下，[功能表现]

                ## 要求
                1. 只输出场景描述，不要输出任何分析过程或解释
                2. 每个维度输出一行，使用上述固定格式
                3. 不要使用任何标记（如 ##、**、1. 等）
                4. 场景描述要具体，包含足够的上下文信息
                5. 如果某个维度不适用，简化为"无相关场景"

                ## 示例输出
                功能场景：在用户登录页面中，支持手机号+验证码登录，登录成功后跳转首页
                业务流程：在订单创建流程中，用户选择商品→确认订单→选择支付方式→完成支付→生成订单
                异常处理：在登录页面中，当验证码错误或过期时，提示用户重新获取
                数据验证：在注册页面中，输入手机号需符合11位数字格式，验证码需为6位数字
                兼容性：在弱网环境下，登录超时时间延长至30秒并提示用户

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

            // 跳过空白行和明显的说明性行
            if (trimmed.isEmpty()) continue;

            String lower = trimmed.toLowerCase();
            if (lower.startsWith("让我") || lower.startsWith("按照") ||
                lower.startsWith("这个") || lower.startsWith("要求") ||
                lower.startsWith("分析") || lower.startsWith("确认") ||
                lower.startsWith("输出") || lower.startsWith("以下") ||
                lower.startsWith("功能场景：") && trimmed.contains("示例") ||
                lower.startsWith("任务") || lower.startsWith("##")) {
                continue;
            }

            // 匹配 "在...中，..." 格式的场景
            Matcher matcher = SCENARIO_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                String scenario = trimmed;
                // 清理格式标记
                scenario = scenario.replaceAll("^[\\-\\*\\d\\.]+\\s*", "");
                scenario = scenario.replaceAll("[。\\.。\\s]+$", "");

                if (scenario.length() >= 10 && !scenarios.contains(scenario)) {
                    scenarios.add(scenario);
                }
            }
        }

        // 如果没有匹配到格式，尝试返回原始内容（去除思考过程）
        if (scenarios.isEmpty()) {
            // 尝试提取最后一个明显的句子作为场景
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.length() >= 15 && !line.contains("<think>") && !line.contains("分析")) {
                    // 清理可能包含的思考内容
                    line = line.replaceAll("<think>.*?</think>", "");
                    if (line.contains("在") && line.contains("中")) {
                        scenarios.add(line);
                        break;
                    }
                }
            }
        }

        log.debug("解析场景描述, 共找到: {} 个场景", scenarios.size());
        return scenarios;
    }

}
