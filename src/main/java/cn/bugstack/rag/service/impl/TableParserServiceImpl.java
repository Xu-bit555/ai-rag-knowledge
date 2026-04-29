package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.TableParserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabula-Java 表格解析服务实现
 * 用于从PDF文档中提取表格内容
 */
@Slf4j
@Service
public class TableParserServiceImpl implements TableParserService {

    @Override
    public List<Table> extractTables(byte[] pdfBytes) {
        List<Table> tables = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            BasicExtractionAlgorithm algorithm = new BasicExtractionAlgorithm();

            int globalIndex = 0;
            int pageNum = 1;
            Page page;
            while ((page = extractor.extract(pageNum)) != null) {
                List<technology.tabula.Table> pageTables = algorithm.extract(page);
                for (technology.tabula.Table tabulaTable : pageTables) {
                    List<List<String>> rows = convertTabulaTable(tabulaTable);
                    if (!rows.isEmpty()) {
                        Table table = new Table(pageNum, globalIndex++, rows);
                        tables.add(table);
                    }
                }
                pageNum++;
            }

            log.info("从PDF中提取到 {} 个表格", tables.size());

        } catch (IOException e) {
            log.error("PDF表格提取失败", e);
        }

        return tables;
    }

    @Override
    public List<Table> extractTablesFromPage(byte[] pdfBytes, int page) {
        List<Table> tables = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            BasicExtractionAlgorithm algorithm = new BasicExtractionAlgorithm();

            Page pdfPage = extractor.extract(page);
            if (pdfPage != null) {
                List<technology.tabula.Table> pageTables = algorithm.extract(pdfPage);
                int globalIndex = 0;
                for (technology.tabula.Table tabulaTable : pageTables) {
                    List<List<String>> rows = convertTabulaTable(tabulaTable);
                    if (!rows.isEmpty()) {
                        Table table = new Table(page, globalIndex++, rows);
                        tables.add(table);
                    }
                }
            }

            log.info("从PDF第{}页提取到 {} 个表格", page, tables.size());

        } catch (IOException e) {
            log.error("PDF表格提取失败, 页码: {}", page, e);
        }

        return tables;
    }

    @Override
    public String tableToMarkdown(Table table) {
        return table.toMarkdown();
    }

    /**
     * 将Tabula表格(RectangularTextContainer)转换为List<List<String>>
     */
    private List<List<String>> convertTabulaTable(technology.tabula.Table tabulaTable) {
        List<List<String>> rows = new ArrayList<>();

        if (tabulaTable == null) {
            return rows;
        }

        for (List<RectangularTextContainer> tabulaRow : tabulaTable.getRows()) {
            List<String> row = new ArrayList<>();
            for (RectangularTextContainer cell : tabulaRow) {
                String text = cell != null ? cell.getText() : "";
                // 清理文本：去除多余空白，保留换行
                text = text.replaceAll("\\s+", " ").trim();
                row.add(text);
            }

            // 跳过空行
            if (!row.isEmpty() && row.stream().anyMatch(s -> !s.isEmpty())) {
                rows.add(row);
            }
        }

        return rows;
    }
}
