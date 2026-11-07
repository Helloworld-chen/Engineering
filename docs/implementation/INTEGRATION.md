# 模块接入说明

本说明对应陈玉豪个人需求 V1.2。M01 网站前端和 M02 交易履约与资金结算由本分支交付。M03 至 M08 当前由演示适配器提供联调能力，正式模块按下列接口接入。同一进程、同一数据库事务管理器和同一业务时间用于强一致操作。

## 接入边界

Java 接口位于 `backend/src/main/java/cn/edu/campus/`。下表路由均以 `/api/v1` 为前缀。HTTP 方法、字段、枚举、请求头及响应结构以 [OpenAPI 契约](../../contracts/openapi.yaml)为准。

| 模块 | 接口与职责 | 主要路由 |
| --- | --- | --- |
| M01 前端与交互 | `frontend/src/api/` 消费统一契约。页面按服务端授权操作、对象版本、截止时间和暂停信息展示 | 商品浏览及编辑、买卖订单、售后、审核举报、通知、个人资料和身份页面 |
| M02 交易履约与资金结算 | `trade.TradePort`。向 M07 提供 `TradeAfterSalesPort`，向 M08 提供 `TradeTaskPort`。拥有订单、占用、物流、账户、暂管款和交易流水。统一校验对象归属和执行资金变更 | `/orders`、`/orders/{id}`、`/orders/{id}/payment`、`/cancel`、`/shipment`、`/receipt`、`/return-shipment`（后五项均在订单下） |
| M03 身份与权限 | `identity.IdentityPort`。管理身份、角色和资料，返回服务端认可的 `Actor` | `/session`、`/auth/{login,register,logout}`、`/profile` |
| M04 商品发布与维护 | `product.ProductPort`。拥有商品、审核状态和出售状态，支持加锁读取和交易状态写入 | `/products`、`/products/{id}`、`/my/products` |
| M05 商品浏览检索 | `browse.BrowsePort`。公开检索和分页查询。详情的购买入口直接提交 M02 下单 | `GET /products` |
| M06 商品审核与举报 | `moderation.ModerationPort`。管理举报及处理记录，通过 M04 更新商品审核结果 | `/moderation/products`、`/moderation/products/{id}`、`/reports`、`/reports/{id}/resolve` |
| M07 售后审核与客服裁决 | `aftersales.AftersalesPort`、`CaseRepositoryPort`。拥有售后单、决定和处理历史，调用 M02 执行履约限制及退款 | `/cases`、`/cases/{id}/actions` |
| M08 通知与业务时钟 | `notification.TimePort`、`NotificationPort`、`TaskRuntimePort`。拥有业务时间、到期任务、待投递事件和通知 | `/notifications`、`/notifications/{id}/read`、`/events`、`/demo/advance`、`/demo/reset`（演示专用） |
| 公共文件服务 | `files.FilePort`。负责上传人、用途、图片校验、绑定及读取权限 | `POST /files`、`GET /files/{id}` |

## 事务与数据所有权

- 写操作统一通过共享事务协调器按**商品 → 订单 → 售后单 → 按标识排序的账户 → 暂管记录**加锁。M04 的 `lock` 和 `updateSale`、M07 的 `lockForOrder` 和决定保存、M08 的 `schedule` 和 `emit` 加入调用方事务。模块通过进程内接口协作，使用同一事务传播边界。禁止在这些调用中使用 `REQUIRES_NEW` 或向本服务发送 HTTP 请求。
- M04 管理商品行，M02 独占订单和资金执行，M07 独占售后决定，M08 管理时钟、任务和通知。M07 在同一事务中保存有效决定，再由 M02 复核状态与权限、设置履约限制或执行资金动作。客户端金额、身份和角色不参与授权。货款金额取订单价格快照，单位为整数分。
- 请求幂等记录、对象状态、金额流水、附件引用、任务和待投递事件在相应业务事务内提交。资金流水和暂管终态另有业务级防重。外部消息投递在提交后执行。
- 发货及退货凭证须包含商品与包装照片，各至少一张，合计最多六张。每张不超过 5 MB，允许 JPEG、PNG、WebP，服务端实际解码并限制像素规模。上传返回的临时文件标识只能由有权用户按用途绑定。成功绑定的凭证保持不变，未绑定文件满 24 小时后清理。

## 会话、错误与重试

1. 调用 `GET /session` 获得当前会话与防跨站请求伪造令牌（CSRF）。浏览器同源发送 HttpOnly 会话 Cookie。修改请求携带 `X-CSRF-TOKEN`。登录、注册、退出后重新查询会话。
2. 业务修改携带 `Idempotency-Key`。同一操作者、操作作用域及键重放相同请求体时返回原结果，复用键但改动请求体会冲突。连接中断时保留原键重试，并重新查询最新对象。登录、注册、退出和临时上传按各自契约处理。
3. 接口错误以 HTTP 状态、错误代码和可读消息表达。401 进入登录恢复，403 处理权限或 CSRF 问题，404 检查对象是否可见，409 重新查询对象并处理状态或键冲突，503 等待后端恢复。鉴权和状态校验最终由后端执行。
4. 服务器推送事件（SSE）只提示业务变化，前端收到后查询权威状态，重连时亦重新查询。消息投递失败独立于已提交交易。时间接口采用 UTC 的 ISO 8601 时刻，显示倒计时以服务端当前时间及截止时刻为准。

## 时钟、恢复与售后口径

M08 将到期任务的目标、类型、代次和 `dueAt` 持久化。恢复时按截止时刻和任务编号处理，每个任务使用独立事务并复核业务状态与代次。服务启动后补办到期任务，恢复门控开放前，受保护的写请求返回 503。停机时间继续计入业务期限，发货与退货登记送达采用原定 `dueAt`，由此开始 48 小时收货期或 24 小时验收期。

售后受理后按业务场景暂停发货或收货结算。客服驳回发货前退款后恢复待发货。客服处理退货验收争议时可原额退款，或要求补材料并继续暂管货款。订单已完成时售后入口关闭。卖家发货、买家寄回和客服处理沿用业务触发条件，本版没有额外期限。M05 当前接口范围为商品浏览检索，旧版申请排队流程已从购买链路移除。

## 切换正式提供者

| Spring 属性 | 环境变量 | 提供接口 |
| --- | --- | --- |
| `market.providers.identity` | `IDENTITY_PROVIDER` | `IdentityPort` |
| `market.providers.product` | `PRODUCT_PROVIDER` | `ProductPort` |
| `market.providers.browse` | `BROWSE_PROVIDER` | `BrowsePort` |
| `market.providers.moderation` | `MODERATION_PROVIDER` | `ModerationPort` |
| `market.providers.aftersales` | `AFTERSALES_PROVIDER` | `AftersalesPort` 和 `CaseRepositoryPort` |
| `market.providers.notification` | `NOTIFICATION_PROVIDER` | `TimePort`、`NotificationPort` 和到期执行协调 |

每项默认 `demo`。接入方按同一属性以 `real` 为条件注册正式实现，先部署实现和所需数据库迁移，再将对应属性切换为 `real`。同一端口只启用一个实现。可逐项替换，切换后检查端口依赖和联调测试。接入正式 M08 时还应接管启动恢复门控、任务派发、通知投递和会话关闭。通过 `TaskRuntimePort.ready()` 向共享门控报告恢复完成。演示管理仅在演示模式和 M08 演示提供者同时启用时可用。使用 `DEMO_MODE=false` 禁用演示种子与管理员演示操作。正式提供者各自维护数据初始化，避免把演示账户、图片和时钟偏移带入正式环境。

## 验证入口

从仓库根目录执行 `sh scripts/test-backend.sh`，然后在 `frontend/` 执行 `npm ci`、`npm run build`、`npm test`。启动 Compose 演示服务后安装 Chromium，再执行 `E2E_EXTERNAL=1 npm run test:e2e`。命令和种子账户详见[仓库运行指南](../../README.md)。

修改 API 时运行 `python3 scripts/build-contract.py`，并在 `frontend/` 执行 `npm run generate:types` 更新前端类型。验证业务响应和权限条件时以实际服务接口与 OpenAPI 契约为准。

## 主要表与迁移边界

[数据库迁移](../../backend/src/main/resources/db/migration/V1__module_schema.sql)定义当前联调数据模型。正式适配器迁移须保留其被依赖的标识、外键和锁语义。演示实现仍启用时，须兼容它们对下列数据模型的读写约定。

| 所有者 | 表 |
| --- | --- |
| M02 | `orders`、`product_holds`、`shipments`、`accounts`、`escrows`、`money_operations`、`money_entries`、`applied_decisions` |
| M03 | `users` |
| M04 | `products` |
| M06 | `reports`、`moderation_reviews` |
| M07 | `cases`、`case_decisions` |
| M08 | `clock_state`、`deadlines`、`outbox`、`notifications` |
| 共享服务 | `files`、`request_results`、`audit_log` |

演示管理员推进时间时传入正整数毫秒。重置请求须携带明确的 `confirmation: "RESET"`，并遵循业务写入的会话、CSRF 和幂等规则。重置清空演示业务数据并恢复演示商品、模拟余额和业务时钟，保留注册账户。推进与重置由演示协调入口管理，避免与在途业务修改及到期任务交错。
