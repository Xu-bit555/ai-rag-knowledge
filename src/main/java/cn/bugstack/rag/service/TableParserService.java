package cn.bugstack.rag.service;

import java.util.List;

/**
 * 表格解析服务接口
 * 专门用于从PDF文档中提取表格内容
 */
public interface TableParserService {

    /**
     * 从PDF中提取所有表格
     * @param pdfBytes PDF文件的字节数组
     * @return 表格列表，每个表格表示为字符串列表（行）列表（表格）
     */
    List<Table> extractTables(byte[] pdfBytes);

    /**
     * 从PDF中提取指定页码的表格
     * @param pdfBytes PDF文件的字节数组
     * @param page 页码（从1开始）
     * @return 该页的表格列表
     */
    List<Table> extractTablesFromPage(byte[] pdfBytes, int page);

    /**
     * 将表格转换为Markdown格式，便于LLM理解
     * @param table 表格对象
     * @return Markdown格式的表格字符串
     */
    String tableToMarkdown(Table table);

    /**
     * 表格数据类
     */
    class Table {
        private int pageNumber;
        private int tableIndex;
        private List<List<String>> rows;
        private List<String> headers;

        public Table(int pageNumber, int tableIndex, List<List<String>> rows) {
            this.pageNumber = pageNumber;
            this.tableIndex = tableIndex;
            this.rows = rows;
            // 第一行作为表头
            if (rows != null && !rows.isEmpty()) {
                this.headers = rows.get(0);
            }
        }

        public int getPageNumber() { return pageNumber; }
        public int getTableIndex() { return tableIndex; }
        public List<List<String>> getRows() { return rows; }
        public List<String> getHeaders() { return headers; }

        public String toMarkdown() {
            if (rows == null || rows.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();

            // 表头
            if (headers != null && !headers.isEmpty()) {
                sb.append("| ");
                for (String header : headers) {
                    sb.append(header != null ? header : "").append(" | ");
                }
                sb.append("\n");

                // 分隔线
                sb.append("| ");
                for (int i = 0; i < headers.size(); i++) {
                    sb.append("--- | ");
                }
                sb.append("\n");
            }

            // 数据行（跳过表头）
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                sb.append("| ");
                for (String cell : row) {
                    sb.append(cell != null ? cell.replace("\n", " ").replace("|", "\\|") : "").append(" | ");
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }
}