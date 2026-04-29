package cn.bugstack.rag.service;

import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.model.dto.QueryTagListResponse;
import cn.bugstack.rag.model.response.Response;

/**
 * 知识库服务接口
 */
public interface KnowledgeService {

    /**
     * 查询知识库标签列表
     */
    Response<QueryTagListResponse> queryRagTagList();

    /**
     * 上传文件到知识库（不落盘，直接从字节流解析）
     */
    Response<String> uploadFileBytes(String ragTag, byte[] fileBytes, String fileName);

    /**
     * 添加文本内容到知识库
     */
    Response<String> addKnowledge(String ragTag, String content);

    /**
     * 创建知识库标签（不添加内容）
     */
    Response<String> createRagTag(String ragTag);

    /**
     * 查询知识库文档（语义检索）
     */
    Response<QueryKnowledgeResponse> queryKnowledge(String ragTag, String query, Integer topK);

    /**
     * 删除知识库文档
     */
    Response<String> deleteKnowledge(String ragTag, String docId);
}
