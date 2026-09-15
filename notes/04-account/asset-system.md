# 资产系统（Step 2）

交易所的账本：下单、成交、撤单最终都落到账户余额。本篇沉淀 Step 2 的概念性结论，过程记录见文末来源链接。

## 1. 核心模型：available 与 frozen 分离

下单时钱不一定马上成交，但已被订单占用，不能再用于其他订单。因此余额拆成两个桶：

- **available**：可自由支配
- **frozen**：被活动订单占用，仍属于本人

关键性质：**冻结/解冻只改变钱的状态，不改变钱的归属和账户总额**；只有成交划账才改变归属。

- 买单冻结 `价格 × 数量` 的 USD，卖单冻结对应数量的 BTC
- 成交后从 frozen 划给对方
- 撤单时未成交部分的 frozen 退回 available
- 买入实际成交价低于委托价时，退回多冻结的差价

## 2. 三种转账类型覆盖全部余额迁移

| Transfer | 字段变化 | 业务含义 |
| --- | --- | --- |
| `AVAILABLE_TO_AVAILABLE` | from.available −，to.available + | 普通转账；负债账户充值 |
| `AVAILABLE_TO_FROZEN` | from.available −，to.frozen + | 冻结（from==to 即 tryFreeze） |
| `FROZEN_TO_AVAILABLE` | from.frozen −，to.available + | 成交划账；from==to 即 unfreeze |

实现上只有一个核心方法 `tryTransfer`，`tryFreeze` / `unfreeze` / `transfer` 都是它的薄封装。

## 3. 失败语义：返回 false 还是抛异常

- **余额不足是正常业务结果** → `tryTransfer` / `tryFreeze` 返回 `false`，余额不变
- **解冻/强制转账失败说明内部状态不一致**（钱本应已冻结）→ `unfreeze` / `transfer` 抛 `RuntimeException`
- **负金额是编程错误** → 所有入口抛 `IllegalArgumentException`；**零金额是 no-op**，直接返回 true，且不触发账户初始化

`tryTransfer` 先检查后修改，失败不存在部分写入。这个"异常类型区分失败路径"的设计也让测试可以精确断言命中的是哪个分支。

## 4. 负债账户与守恒对账

充值不是"印钱"，而是从系统负债账户（DEBT）转账：用户余额增加，DEBT 余额变负。由此得到全系统不变量：

```
每种资产：所有账户的 available + frozen 之和 = 0
普通用户：available >= 0 且 frozen >= 0
负债账户：available 可为负，frozen 恒为 0
```

测试把这三条放在 `@AfterEach` 里对每个用例自动验证，任何操作序列结束后账本都必须守恒——这比逐用例手算对账更可靠，也让"组合操作是否破坏账本"这类问题有了统一兜底。

完整系统中还有第二层对账（订单冻结额与账本 frozen 的对应、活动订单与订单簿的对应），等撮合/清算阶段实现。

## 5. 并发模型：单写线程，读路径无保证

**写路径有严格保证**：事件经定序器定序后由交易线程单线程串行处理，资产修改只发生在这一个线程。`AssetService` 的"无锁正确"完全依赖这个调用纪律，`ConcurrentHashMap` 只解决 map 结构本身的并发访问，不能把"检查-扣款-入账"变成原子操作。

**读路径没有保证**（已对照上游 step-11 确认）：用户查余额时，Web 容器线程直接序列化引擎内存中的 `Asset`，而 `Asset.available/frozen` 是非 volatile 普通字段、靠重新赋值修改——读写线程间无 happens-before，可能读到过期值，或读到"available 已扣、frozen 未加"的中间态（torn read）。

教程可接受是因为余额查询仅作展示，权威状态在事件日志；行情数据走另一条路（引擎推快照到 Redis），不碰账本。

生产级候选方案：

1. 读写分离的只读快照（行情已这么走 Redis）
2. 不可变 Asset + copy-on-write，读到的一定是某个完整版本
3. 版本号乐观快照读

## 6. 精度：账本无舍入，SCALE 在输入侧

`AssetService` 对金额只做 `add`/`subtract`，没有任何 `setScale`——BigDecimal 运算天然精确，账本层不丢精度（开发期用 8 位小数和 1 satoshi 验证过）。

`AssetEnum.SCALE = 2` 是**输入侧契约**，应在 API 边界（请求 Bean）校验，目前尚未在任何入口强制，随多交易对/精度扩展（#8）统一处理。因此"系统精度是 2 位"成立的原因是边界控制，不是账本能力。

## 7. 测试方法小结

Step 2 测试的设计方法（9 个用例 + 全局不变量）：

1. **分支驱动**：从 `tryTransfer` 的决策结构（signum 三分支、null 懒初始化、余额检查、三种类型）推测试清单，JaCoCo 分支覆盖率做事后验证
2. **等价类划分**：金额输入分为 零 / 负数 / 小于余额 / 等于余额 / 大于余额，每类取代表值
3. **边界值**：`compareTo < 0` 严格小于，"刚好够"必须单独测，防 off-by-one
4. **结构特殊形态**：同账户转账的对象别名（from==to 是同一个 Asset）、账户懒初始化
5. **不变量下沉**：守恒/非负/负债账户 frozen 为 0 放入 `@AfterEach`，每个用例自动采样

裁剪原则同样重要：不带来新分支覆盖的组合测试、只验证 JDK 行为的测试（如 BigDecimal 精度）一律不留，结论记入笔记而非测试。

## 8. 已知限制与后续

- `getUserAssets()` 暴露可变的内部账本引用；`checkBalance=false` 只靠 Javadoc 约定限负债账户使用——见 `docs/ASSET_SERVICE_FOLLOW_UP.md`，待订单/清算流程可见后统一处理
- `AssetService.debug()` 是 System.out 打印的诊断方法，无测试价值，随 follow-up 删除或改 logger
- 订单冻结额对账（`validateOrders`）、宕机恢复实验、性能压测分别属于后续里程碑

## 来源

- 主 Issue：#4；架构理解：#24；源码阅读：#28；实现：#29；扩展实验：#30
- PR：#27（common）、#33（资产实现）、#34（扩展测试）
- 上游参考：本地 `upstream-reference` 分支 `a073b0b`，Step 2 为主，Step 11 完整流程对照
