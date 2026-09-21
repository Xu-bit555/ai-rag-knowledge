# TeraBox AI 内容生产约定

## 文件命名

- PRD 文件: `prd/{NN}_{slug}_prd.md`，其中 `slug` 为英文短横线
- 用例文件: `cases/{NN}_{slug}_cases.json`
- 序号按 tab 在主页侧边栏从上到下：01=Tera AI, 02=图片生成, 03=视频生成, 04=AI 笔记本

## PRD 模板（小节固定）

每个 PRD 必须包含 §1-§8，章节标题用 `##`，便于 TokenTextSplitter 切出有边界的 chunk。

## DSL caseId 命名

格式：`TC_{TAB_PREFIX}_{SCENARIO}_{VARIANT}`

示例：
- `TC_AI_OK` — Tera AI 正常路径
- `TC_IMG_OK` — 图片生成正常
- `TC_IMG_FAIL_NO_QUOTA` — 图片生成失败（无积分）
- `TC_VID_OK` — 视频生成正常
- `TC_NB_OK` — 笔记本正常

前缀约定：
- `TC_AI_*` — Tera AI (chat)
- `TC_IMG_*` — 图片生成
- `TC_VID_*` — 视频生成
- `TC_NB_*` — AI 笔记本

## 元素命名（a11y 快照里读到的）

| 元素 | a11y role | name |
|------|-----------|------|
| 主输入框（Tera AI） | textbox | "搜索、创建或完成事项" |
| 主输入框（图片生成） | textbox (multiline) | "描述你想生成的画面最多可上传 9 张图片作为参考" |
| 生成按钮（图片生成） | button | "生成" |
| 继续按钮（Tera AI） | button | "继续" |
| 后退按钮 | link | "后退" |
| 登录链接 | link | "登录/注册" |
| 模型选择 | (div with image) | "Nano Banana" |

## automationCandidate 选择规则

- 默认 `WEB_FUNCTIONAL`（用户已选）
- 涉及**登录态**或**付费积分**的，保留 `WEB_FUNCTIONAL`（agent 应自动登录流程不在 MVP 范围）
- 涉及**音频/语音**（AI听记），标 `MANUAL_VISUAL`

## testData 字段

- 必填的测试输入（如 prompt 文本）放入 `testData` 字段
- 字符串类型（DSL schema 约束）

## 步骤顺序

- `order` 必须从 1 起连续递增（语义校验器要求）
- 通常顺序：NAVIGATE → INPUT → CLICK → WAIT → ASSERT
- NAVIGATE 必须用 `url`，不能有 `target`
- INPUT/SELECT 必须有 `target` + `value`
- CLICK 必须有 `target`

## 断言策略

- 登录态用例：用 `ELEMENT_VISIBLE` 检查登录引导元素
- 生成按钮可用性：用 `ELEMENT_VISIBLE` 检查按钮
- 生成结果出现：用 `ELEMENT_VISIBLE` + 较长 timeout（30s+）
- URL 跳转：用 `URL_CONTAINS` 验证导航
- 错误状态：用 `TEXT_CONTAINS` 检查错误提示文案
