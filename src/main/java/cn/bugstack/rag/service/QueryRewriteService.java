package cn.bugstack.rag.service;

import java.util.List;

/**
 * Query Rewrite 服务接口
 * 专门处理PRD风格的用户输入，将其转换为更适合检索的查询形式
 *
 * 解决的问题：
 * 1. PRD文档通常以"作为...我想要...以便于..."格式编写，需要提取核心功能需求
 * 2. 用户输入可能是模糊的、简短的，需要扩展为多个具体查询
 * 3. PRD中的需求可能包含业务术语，需要转换为知识库中的标准术语
 */
public interface QueryRewriteService {

    /**
     * 核心重写方法
     * @param userQuery 用户原始输入（可能是PRD风格）
     * @return 重写后的查询列表（支持多查询扩展）
     */
    List<String> rewrite(String userQuery);

    /**
     * 单一查询重写（不扩展为多查询）
     * @param userQuery 用户原始输入
     * @return 重写后的单一查询
     */
    String rewriteToSingle(String userQuery);

    /**
     * 检测查询是否为PRD风格
     * @param query 查询文本
     * @return true if PRD style detected
     */
    boolean isPRDStyle(String query);

    /**
     * 从PRD格式提取功能需求
     * 输入示例："作为管理员，我想要创建用户，以便于管理团队成员"
     * 输出示例：["创建用户", "管理员创建用户流程", "用户管理功能"]
     */
    List<String> extractFromPRD(String prdQuery);

    /**
     * HyDE风格重写：生成假设性答案，用答案的向量辅助检索
     * @param userQuery 用户原始查询
     * @return 生成的假设性答案片段
     */
    String generateHypotheticalAnswer(String userQuery);
}