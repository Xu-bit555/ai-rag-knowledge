package cn.bugstack.rag.service;

import cn.bugstack.rag.service.DocumentParserService;

import java.util.List;

/**
 * 段落级文档摄入服务接口
 */
public interface ParagraphIngestService {

    void ingestDocumentFromParagraphs(List<DocumentParserService.Paragraph> paragraphs, String sourceDoc, String ragTag);
}