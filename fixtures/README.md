# fixtures/ —— 真机抢购夹具

淘宝的「定时抢购」商品不能靠固定链接：`能加购 + 开售前不能买 + 有精确开售时间` 三者只在
**限量全款预售**这类商品上同时成立，而且它们会开售、会下架、会换场次。
所以这里存的是**一批带开售时间的商品条目**，由脚本采集、巡检、驱动测试，而不是一个写死的链接。

## 快速用法

```bash
scripts/fixtures.py list                      # 有哪些夹具、离 T 还有几天
scripts/fixtures.py ingest shares.txt         # 吃进一批群分享/短链，逐条判定入库
scripts/fixtures.py probe 1103296796879       # 单个商品，判定后入库
scripts/fixtures.py harvest --source home --max 3 --scrolls 4
scripts/fixtures.py audit                     # 逐条重开，标 live/on_sale/changed/gone

scripts/android-seckill-test.sh prepare --fixture tb-1103296796879
scripts/android-seckill-test.sh run     --fixture tb-1103296796879 --at +3m
```

## 字段

```jsonc
{
  "id": "tb-1103296796879",          // 文件名同此
  "platform": "taobao",
  "kind": "timed_full",              // timed_full | deposit | presale_now | on_sale | gone
  "itemId": "1103296796879",
  "deepLink": "taobao://item.taobao.com/item.htm?id=1103296796879",
  "saleTime": "2026-11-25T16:00:00+08:00",   // 开售时间（北京时间）
  "bannerText": "即将开始，11.25 16:00开售",  // 原样保留，判定依据
  "title": "…",
  "prices": [258.0, 298.0],
  "cartCount": "100人加购",
  "sold": "0",
  "shipPromise": "承诺120天内发货",
  "presaleAttr": true,
  "evidence": "testdata/dumps/tb-1103296796879-detail.xml",
  "state": "live",                   // live | on_sale | changed | gone | unreachable
  "collectedAt": "…", "updatedAt": "…"
}
```

## 判定锚点（详情页 dump 文本，不靠图）

| 锚点 | kind | 能不能当定时夹具 |
|---|---|---|
| `即将开始，11.25 16:00开售` | `timed_full` | ✅ 最理想（0 定金，可加购） |
| `预售:出售状态` + `预售，200天内发货` | `presale_now` | ❌ 现在就能买，没有定时 |
| `定金` + `尾款` | `deposit` | ⚠️ 要真付定金 |
| `已下架` / 已开售 | `gone` / `on_sale` | ❌ 换一条 |

## 采集来源（按省力程度）

1. **`ingest`**：微信群/微博/小红书贴来的 `【淘宝】https://c.tb.cn/h.xxx?tk=…` —— 人已经筛过一轮，
   `c.tb.cn` 是分享短链，`curl` 就能解出 itemId，不需要登录。
2. **`harvest --source home`**：首页推荐流有文字节点，卡片标题带 `{预售}` 前缀的进详情判定，密度低但免费。
3. **`harvest --source search --sort 上新 --blind`**：搜索结果商品区是自定义渲染、**没有文字节点**，
   只能按网格盲点；条目按 `itemId` 去重。这是没有更好入口时的兜底。

## 纪律

- **频控**：单件间隔 `FIXTURE_VISIT_GAP`（默认 8s），命中「访问太频繁」后睡 `FIXTURE_THROTTLE_SLEEP`（默认 150s）。
- **不付款**：测试跑 `--submit-order` 会生成真实待付款订单，**不付款就不扣钱**，跑完 `--cancel-order` 取消。
- **开售前勾不上**：预售商品开售前购物车里是勾不上的，开售后需要人勾一次；程序只负责结算与提交。
- **过期就删**：`audit` 标成 `gone` 的条目直接删掉，别留在目录里骗自己。
