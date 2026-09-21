# 登录模块 PRD (Demo)

> 用于 OneCase 演示。配合 demo-web Spring Boot 模块使用。

## 1. 产品背景

本系统提供 Web 端用户登录功能。用户通过浏览器访问登录页面，输入用户名和密码完成身份验证。

- 部署地址：`http://127.0.0.1:8081/demo/login`
- 演示账号：`test / test123`

## 2. 登录规则

### 2.1 输入规则

| 字段 | 必填 | 约束 |
|------|------|------|
| 用户名 | 是 | 任意非空字符串 |
| 密码 | 是 | 任意非空字符串 |

### 2.2 成功条件

- 用户名 = `test`
- 密码 = `test123`

满足 → 跳转到 `/demo/dashboard`，显示"欢迎回来,登录成功"。

### 2.3 失败条件

| 情形 | 期望 |
|------|------|
| 用户名/密码不匹配 | 显示红色错误提示：`用户名或密码错误` |
| 用户名为空 | 浏览器 HTML5 验证提示 |
| 密码为空 | 浏览器 HTML5 验证提示 |

## 3. UI 元素 (供自动化测试)

| 元素 | 角色 / 定位 |
|------|------------|
| 用户名输入框 | `<input type="text" id="username">` |
| 密码输入框 | `<input type="password" id="password">` |
| 登录按钮 | `<button id="loginButton">登录</button>`（按钮文本可通过 `app.login.button-text` 配置） |
| 错误提示 | `<p class="error" id="errorMessage">` |
| Dashboard 欢迎语 | `<p id="welcomeMessage" class="welcome">` |

## 4. 配置项

```yaml
app:
  demo:
    username: test
    password: test123
  login:
    button-text: 登录   # 可改为 Sign in 演示 Recovery
    welcome: 欢迎回来,登录成功
```

## 5. 演示测试用例（供 OneCase 生成）

由 `onecase_generate_cases` 根据本 PRD 生成。预期产出：

| caseId | 标题 | 预期 |
|--------|------|------|
| `TC_LOGIN_001` | 正确用户名密码登录 | PASSED — Dashboard 可见 |
| `TC_LOGIN_002` | 错误密码登录 | FAILED — 显示错误提示 |
| `TC_LOGIN_003` | 空用户名/密码 | BLOCKED — HTML5 validation 拦截 |

## 6. Recovery 演示场景

将 `app.login.button-text` 从 `登录` 改为 `Sign in` 后：
- TC_LOGIN_001 step 3 (click login) FAILED (button text changed)
- DiagnoseFailure → category = LOCATOR_DRIFT / TARGET_NOT_FOUND
- Semantic Recovery Level 1 → 重新 snapshot → 找 "Sign in" button → retry
- Attempt #2 → PASSED

## 7. 不在本 PRD 范围

- 多用户管理
- 密码加密
- JWT / Session 持久化
- 验证码
- 第三方登录
- 注册流程