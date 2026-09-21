# TeraBox AI 页面 Tab 发现清单

**来源**：Chrome MCP 实测（a11y snapshot，2026-09-21）

## 总览

`https://www.terabox.com/ai/index/chinese` 是一个 SPA 主页，左侧栏列出 9 个 AI tab，右下角是营销区。所有 tab 均**需要登录**才能进入实际功能页面（点击未登录会引导到「登录/注册」）。

## 已发现的 AI Tab

| # | Tab 名 | URL | 入口状态 | 实际状态 |
|---|--------|-----|---------|---------|
| 1 | **Tera AI** | `/ai/agent` | 可点 | 一站式对话助手主页（营销页 + 输入框 + 继续按钮） |
| 2 | **AI 笔记本** | `/ai/notebook/list` | 可点 | 笔记列表页（需登录） |
| 3 | **标准PPT生成** | `/ai/presentation-maker` | 可点 | PPT 制作（需登录） |
| 4 | **智能PPT助手** | `/ai/presentation-maker-agent` | 可点 | PPT 代理模式（需登录） |
| 5 | **美化幻灯片** | `/ai/presentation-maker-beautify` | 可点 | PPT 美化（需登录） |
| 6 | **图片生成** | `/ai/photo-to-photo/chinese` | 可点 | 文生图/图生图（marketing + 输入框） |
| 7 | **视频生成** | `/ai/photo-to-video/chinese` | 可点 | 文生视频/图生视频（需登录） |
| 8 | **AI听记** | `/ai/index/m/5` | 可点 | 录音转文字（需登录） |
| 9 | **Nano Banana 幻灯片** | `/ai/presentation-maker-nano` | 可点 | PPT BETA（需登录） |

## 选取入内容库的 4 个 tab

基于**代表性 + UI 清晰度**（更具可观察的 UI 元素，agent 更容易执行），选：

1. **Tera AI** — 旗舰对话，UI 元素最丰富
2. **图片生成** — 最有代表性的生成式 UI（prompt + 模型选择 + 生成）
3. **视频生成** — 与图片生成结构类似，独立的 tab
4. **AI 笔记本** — 笔记场景，独立的列表/详情模式

PPT 系列（标准/智能/美化/Nano）形态相似，只选 1 个；AI听记内容偏音频处理，UI 元素较少。

## 关键 UI 元素（按 tab）

### 1. Tera AI（`/ai/agent`）
- 多行文本框: "搜索、创建或完成事项"
- 按钮: 「继续」（输入为空时 disabled）
- 区域: 「从这里开始」（含 4 个示例 prompt 卡片 + 「换一批」按钮）
- 示例 prompt: 搜索猫和老鼠 / 小学生自我保护 / Ozempic 效应 / AI写真公司邮件营销
- 能力分类: AI 对话 / AI 总结 / AI 翻译 / 深度研究 / 影视搜索 / 云端整合
- 文件支持: DOCX / PDF / TXT / XLSX / PPTX / JPG / JPEG / PNG

### 2. 图片生成（`/ai/photo-to-photo/chinese`）
- 模式单选: 「图片」/ 「视频」
- 模式 chips: 「生成」/ 「特效」
- 多行文本框: "描述你想生成的画面最多可上传 9 张图片作为参考"
- 模型选择器: Nano Banana
- 提示: "本次消耗：5积分"
- 主按钮: 「生成」
- 示例 prompt（点击可填入）: 酥皮火车 / 软糖游乐场 / 毛线艺术 / 水果分解
- 广场分类: Gallery / 26世界杯 / 探索 / 拼图专题 / 精选推荐 / 热门短视频 / AI 照相馆
- 底部营销: 文生图/图生图 / 创意特效 / 背景替换 / 物体替换 / 场景扩展
- 智能工具链接: AI PPT制作-标准模式 / Deep Research / AI 视频制作 / AI PPT制作-代理模式 / 美化 PPT / AI 论文写作

### 3. 视频生成（`/ai/photo-to-video/chinese`）
- 结构与图片生成页相同，模型不同（推断）
- 输入：图片 + 文字描述 或 仅文字
- 输出：MP4 短视频
- 营销描述: "输入简短脚本或上传产品图片，Tera AI 即可生成适用于 TikTok、Instagram Reels 和广告投放的短视频"
- 需登录

### 4. AI 笔记本（`/ai/notebook/list`）
- 列表页面（具体 UI 未点击，营销提到「笔记管理」）
- 营销描述: "上传零散的报告或篇幅较长的 PDF。Tera AI 可一次读取最多 10 个文件，提取关键信息，回答后续问题"
- 需登录

## 通用交互约定

- 所有生成类操作需要**登录态**（消费积分或配额）
- 所有生成结果保存到 TeraBox 云盘
- 主要 CTA 按钮: 「免费生成图像」/ 「继续」/ 「生成」/ 「立即加入TeraBox，免费开启你的创作！」
- 顶部 "登录" 链接（未登录）/ 头像（已登录）
- "后退" 按钮回到 AI 主页 `/ai/index/chinese`

## 限制与豁免

- **登录态用例**：标记 `automationCandidate=MANUAL_VISUAL`（默认 WEB_FUNCTIONAL，但需要登录态）
- **审计要求**：UI 文案、按钮名、模型名称应与快照中的实际文案一致（用于 PRD 检索召回验证）
