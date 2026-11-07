# 校园二手商品交易系统

本仓库提供课程演示所用的校园二手交易网站。陈玉豪负责 M01 项目前端与交互、M02 交易履约与资金结算，覆盖个人需求 C01 至 C09。身份、商品、浏览、审核举报、售后和通知时钟通过 M03 至 M08 演示适配器运行，各模块可按接口独立替换。支付、退款、物流和账户余额均为本地模拟。

## 启动

安装并启动 Docker，确认 Docker Compose 可用。进入仓库根目录执行：

```sh
sh scripts/run.sh
docker compose ps
```

打开 [交易网站](http://localhost:5173)。前端通过同源路径 `/api/v1` 访问后端。主机服务端口为前端 5173、后端 8080、MySQL 3308。数据库和上传图片使用 Docker 命名卷。停止服务执行 `sh scripts/stop.sh`，再次启动时继续使用已有数据。

| 用户名 | 角色 |
| --- | --- |
| `buyer` | 陈同学，普通用户，可买可卖 |
| `seller` | 林同学，普通用户，可买可卖 |
| `other` | 周同学，普通用户，可买可卖 |
| `admin` | 商品审核、举报处理及演示管理 |
| `support` | 售后客服 |

演示账户密码均为 `Campus123!`。默认种子含三位同学发布的六件商品。买家和卖家由每笔交易中的参与关系确定；三个普通账号的功能权限相同，均可购买其他用户的商品、发布和管理自己的商品。自己的商品详情页显示归属说明，不提供向自己下单的入口。切换角色前可退出登录，也可用独立浏览器会话并行操作。

1. 买家进入商品详情直接下单，在 2 分钟内付款。付款成功后，货款进入平台暂管。
2. 卖家打开卖出订单，提交物流单号、商品和包装照片。提交成功 10 秒后，系统登记送达并开始 48 小时收货期。
3. 买家确认收货后，系统将暂管货款结算给卖家。未发货退款、送达后投诉及退货路径从订单和售后页面进入。退货寄出后 10 秒登记送达，再开始 24 小时卖家验收期。
4. 演示管理员可通过站内演示入口推进业务时间，也可在明确确认后重置演示业务数据。重置会恢复演示商品、模拟余额和业务时钟，并保留已注册账户。停止服务期间业务时间继续经过，重启后系统补办到期事项。

## 开发与验证

后端使用 Java 21、Spring Boot 4.1.2、MyBatis Spring Boot Starter 4.0.0、MySQL 8.4。前端使用 Node.js 24（至少 24.12.0）、Vue 3、TypeScript 和 Element Plus。

```sh
sh scripts/test-backend.sh
sh scripts/test-restart.sh
cd frontend
npm ci
npm run build
npm test
npx playwright install chromium
E2E_EXTERNAL=1 npm run test:e2e
```

后端测试使用 Docker 中的独立 `campus_market_test` 数据库。浏览器测试需要运行上述演示服务，具体基础地址及环境变量见 [Playwright 配置](frontend/playwright.config.ts)。本地前端开发可在 `frontend/` 执行 `npm run dev`，使用 Vite 的同源代理连接后端。Compose 前端占用 5173 时，可通过 `docker compose stop frontend` 释放端口。

| 验证项 | 结果 |
| --- | --- |
| 后端真实 MySQL 测试 | 47 项通过，失败、错误、跳过均为 0 |
| 前端类型检查、构建和单元测试 | 构建通过，9 项单元测试通过 |
| Playwright 浏览器端到端测试 | Chromium 13 项通过，包含普通用户买卖兼用回归 |
| 实际进程停止与重启恢复 | 通过；停机期间推进持久化模拟时钟 |
| Compose 交付镜像 | 前后端构建通过，服务已启动并通过浏览器回归 |

实际验证记录及适用范围见[验证记录](docs/implementation/VERIFICATION.md)。重启测试使用一次性数据库和独立 8081 端口，完成后清理测试资源。

接口字段、模块所有权、适配器切换、错误处理和测试关联见[模块接入说明](docs/implementation/INTEGRATION.md)。接口契约见 [OpenAPI 文件](contracts/openapi.yaml)。
