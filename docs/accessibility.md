# 视障友好无障碍适配

本次为揭榜任务二适配既有校园二手商品交易系统。陈玉豪负责页面交互与交易履约。教师已确认网页项目可以提交。失物招领示例仅用于了解材料结构，本项目的功能、测试和证据均取自实际代码。

## 实现范围

| 场景 | 处理方式 | 验证入口 |
| --- | --- | --- |
| 页面导航 | 路由默认标题；异步内容出现后更新页面标题并聚焦主标题；失败时聚焦错误标题；筛选不重新抢焦点 | pageAccessibility.ts、router.ts |
| 弹窗与移动菜单 | 复用 Element Plus 焦点循环，打开时用 inert 隔离背景；Escape 关闭；关闭后恢复触发控件或主标题 | AccessibleDialog.vue、modalIsolation.ts |
| 控件语义 | 商品分类具备 group 角色；默认头像具备 img 角色；照片移除名称包含用途与序号；通知名称包含可见未读数 | CatalogView.vue、UserProfileView.vue、UploadField.vue、App.vue |
| 表单反馈 | 错误关联对应字段并标记无效；用户重新输入时清除旧错误，下次提交重新校验 | fieldErrors.ts |
| 低视力操作 | 明显焦点轮廓、当前导航下划线、输入框实体边框、forced-colors 系统颜色、文本放大和窄屏重排 | style.css |
| 既有功能 | 保留发布、审核、直接下单、2分钟付款、10秒配送、48小时收货、24小时退货验收及互评 | 既有业务回归；后端业务代码未改 |

主要证据见 [测试记录](个人专项作业/陈玉豪/无障碍适配/测试记录.md)，可复制的主帖见同目录 `主帖内容.txt`。

## 启动独立验收版

在仓库根目录执行以下命令，需要 Docker Compose：

```sh
docker compose -f compose.a11y.yaml up -d --build
```

打开 http://127.0.0.1:15180 。首次启动等待后端数据库迁移和演示数据初始化；登录页可选择演示账号。本环境只在本机监听，使用独立 Compose 项目、数据库卷、上传卷；现有 5173 网站不受影响。业务使用课程演示提供者，资金为模拟资金。

停止验收版并保留数据：

```sh
docker compose -f compose.a11y.yaml down
```

重新启动仍使用上次测试数据。不要将本环境当作正式业务数据库，也不要将自动测试指向现有个人演示网站：部分既有用例会重置演示数据、推进业务时钟。

## 复现检查

前端工具建议使用 package.json 指定的 Node 24.12+、小于25；Docker 构建使用 Node 24。首次安装依赖后安装 Playwright Chromium。

```sh
cd frontend
npm ci
npx playwright install chromium
npm test
npm run build
```

专项检查（应先启动上述独立环境）：

```sh
A11Y_ISOLATED=1 E2E_EXTERNAL=1 E2E_URL=http://127.0.0.1:15180 npm run test:a11y
```

完整浏览器回归：

```sh
A11Y_ISOLATED=1 SELLER_E2E_ISOLATED=1 NOTIFICATION_E2E_ISOLATED=1 PROFILE_E2E_ISOLATED=1 STAFF_E2E_ISOLATED=1 E2E_EXTERNAL=1 E2E_URL=http://127.0.0.1:15180 npm run test:e2e
```

报告位于 `frontend/playwright-report/index.html`，可用 `npx playwright show-report` 查看。专项测试把 axe JSON、浏览器可访问树、截图写入 `frontend/test-results`。每次运行会覆盖该目录；本轮提交材料保留了独立证据副本。

## 读屏人工复测步骤

本机 VoiceOver 尝试启动后未取得可核验的朗读输出，未计为通过；临时开关已关闭。下面步骤供后续在可用读屏环境复测，尚无 Windows + NVDA 实测结论。

1. 开启读屏器，进入登录页。使用 Tab 检查用户名、密码、登录按钮的名称及角色，密码内容应按安全输入处理。
2. 进入注册页，提交两次不一致的密码。确认错误被读出、焦点移至确认密码，修正后旧错误状态清除。
3. 以买家进入可售商品，打开下单弹窗。检查标题、说明、取消和确认按钮；Tab/Shift+Tab 应在弹窗内循环，读屏浏览不能进入背景；Escape 后返回下单按钮。
4. 下单付款，切换卖家发货并上传凭证，等待10秒送达；买家确认收货后用方向键选择星级、提交评价。逐步检查状态及结果朗读，避免遗漏失败消息。
5. 手机宽度打开菜单再切换页面，确认菜单关闭后能读出新页面标题并继续操作；浏览器放大和系统高对比度模式分别检查。

自动规则扫描、Chromium 可访问树、键盘自动化与人工听读分别记录。自动检查通过不能代表全部 WCAG 条款或所有读屏器兼容性通过。

## 参考依据

- [WAI-ARIA 模态对话框交互模式](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)
- [WCAG 2.2 文字最低对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
- [MDN forced-colors 媒体查询](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/forced-colors)
