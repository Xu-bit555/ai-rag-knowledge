package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.TableParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import technology.tabula.CommandLineExtractor;
import technology.tabula.Table as TabulaTable;
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

        try (ByteArrayInputStream bis = new ByteArrayInputStream(pdfBytes)) {
            CommandLineExtractor extractor = new CommandLineExtractor();
            extractor.setInput(bis);

            // 使用BasicExtractionAlgorithm提取表格
            BasicExtractionAlgorithm algorithm = new BasicExtractionAlgorithm();
            List<technology.tabula.Table> tabulaTables = extractor.extract(algorithm);

            int globalIndex = 0;
            for (int pageNum = 0; pageNum < tabulaTables.size(); pageNum++) {
                technology.tabula.Table tabulaTable = tabulaTables.get(pageNum);
                List<List<String>> rows = convertTabulaTable(tabulaTable);

                if (!rows.isEmpty()) {
                    Table table = new Table(pageNum + 1, globalIndex++, rows);
                    tables.add(table);
                }
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

        try (ByteArrayInputStream bis = new ByteArrayInputStream(pdfBytes)) {
            CommandLineExtractor extractor = new CommandLineExtractor();
            extractor.setInput(bis);

            // 只提取指定页面
            int[] pages = {page};
            List<technology.tabula.Table> tabulaTables = extractor.extract(pages);

            int globalIndex = 0;
            for (int pageNum = 0; pageNum < tabulaTables.size(); pageNum++) {
                technology.tabula.Table tabulaTable = tabulaTables.get(pageNum);
                List<List<String>> rows = convertTabulaTable(tabulaTable);

                if (!rows.isEmpty()) {
                    Table table = new Table(page, globalIndex++, rows);
                    tables.add(table);
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
     * 将Tabula表格转换为List<List<String>>
     */
    private List<List<String>> convertTabulaTable(technology.tabula.Table tabulaTable) {
        List<List<String>> rows = new ArrayList<>();

        if (tabulaTable == null) {
            return rows;
        }

        for (int i = 0; i < tabulaTable.getRows().size(); i++) {
            technology.tabula.Rectangle cell;
            List<String> row = new ArrayList<>();

            for (int j = 0; j < tabulaTable.getColumns(); j++) {
                try {
                    cell = tabulaTable.getCell(i, j);
                    String text = cell != null ? cell.getText() : "";
                    // 清理文本：去除多余空白，保留换行
                    text = text.replaceAll("\\s+", " ").trim();
                    row.add(text);
                } catch (Exception e) {
                    row.add("");
                }
            }

            // 跳过空行
            if (!row.isEmpty() && row.stream().anyMatch(s -> !s.isEmpty())) {
                rows.add(row);
            }
        }

        return rows;
    }
}