# 智慧珞珈公告抓取

从武汉大学公开站点抓取通知公告，存 SQLite 去重，新条目推送桌面通知 / ntfy。

当前覆盖（均为公开页面，无需登录）：

| 来源 | 地址 | 说明 |
|---|---|---|
| `whu_notice` | `www.whu.edu.cn/tzgg.htm` | 学校通知公告，约 10 条/页 |
| `uc_notice` | `uc.whu.edu.cn/tzgg/xstz.htm` | 本科生院学生通知 |
| `nic_notice` | `nic.whu.edu.cn/szwd/news/tzgg.htm` | 智慧校园部通知（更新较少） |
| `gs_notice` | `gs.whu.edu.cn/tzgg/qb.htm` | 研究生院通知公告 |
| `future_news` | `future.whu.edu.cn/gzdt.htm` | 团委工作动态 |
| `zhlj_api` | `zhlj.whu.edu.cn/welcome/api/news/announcements` | 智慧珞珈公开 JSON，带正文 |
| `zhlj_notice` | `zhlj.whu.edu.cn/whdxIndex/getNotice` | 登录后的聚合通知，覆盖组织部、团委等 |
| `zhlj_message` | `zhlj.whu.edu.cn/whdxIndex/getMessage` | 作业与课程提醒（珞珈在线） |
| `jwgl_exams` | `jwgl.whu.edu.cn/kwgl/...` | 教务系统考试安排 |

团委的“通知公告”和党委组织部的“信息公告”列表页需要 CAS 登录，公开抓取只能拿到团委工作动态；`zhlj_notice` 通过登录会话补齐这两处。

课表、成绩、考试安排等在 CAS 登录之后；配置门户/教务会话后可用 `--courses`、`--exams`、`--grades` 查询，详见下文。

## 快速开始

```bash
cd ~/Projects/zhlj

# 查看库内统计
python3 -m zhlj --status

# 只抓取并打印，不写库、不通知（建议先跑这个）
python3 -m zhlj --dry-run --max-pages 1

# 验证通知渠道（桌面 / ntfy）
python3 -m zhlj --test-notify

# 正式抓取一次
python3 -m zhlj

# 只抓列表、不抓正文（请求更少）
python3 -m zhlj --no-details
```

首次运行只记录基线，不会把历史公告全部推送。之后每次只通知新增条目。

## 配置

编辑 `config.toml`：

- `interval_seconds` / `jitter_seconds`：请求间隔，默认 3 秒 + 0–2 秒抖动。**不要调小**，站点 WAF 会封连接。
- `max_pages`：每个列表源每次最多翻几页，默认 2。
- `first_run_notify`：首次运行是否推送历史公告，默认 `false`。
- `fetch_details`：是否抓新公告正文（每条多一次请求），默认 `true`，每轮最多 5 条。
- `sources`：启用的来源列表。
- `[notify] desktop`：桌面通知（依赖 `notify-send`）。
- `[notify] ntfy_url`：ntfy 推送地址，例如 `https://ntfy.sh/your-topic`，留空关闭。

## 登录会话（课表 / 组织部、团委通知）

门户的课表和聚合通知在登录态后面。只需配置一次 Cookie：

1. 在已登录的 `zhlj.whu.edu.cn/pc/index` 页面按 `F12`；
2. Network 面板筛选 `getCurriculum`，点任意一条请求；
3. Request Headers 里找到 `Cookie:`，复制整行值；
4. 回到终端执行：

```bash
wl-paste | python3 -m zhlj --set-cookie   # 已复制到剪贴板
# 或者直接运行后粘贴 Cookie，按 Ctrl+D
python3 -m zhlj --set-cookie
```

验证与使用：

```bash
python3 -m zhlj --check-session      # 检查会话是否有效
python3 -m zhlj --courses            # 打印本周课表
python3 -m zhlj --courses --week 12  # 指定周次
python3 -m zhlj --courses --json     # JSON 输出
```

### 教务系统（成绩 / 考试）

成绩和考试在 `jwgl.whu.edu.cn`，需要单独一份 Cookie：

1. 打开 `https://jwgl.whu.edu.cn`，登录到「本科教务管理系统」；
2. `F12` → Network → 刷新，点一条 `jwgl.whu.edu.cn` 请求；
3. 复制 Request Headers 里的 `Cookie:` 值；
4. 保存：

```bash
wl-paste | python3 -m zhlj --set-cookie --session jwgl
```

查询：

```bash
python3 -m zhlj --exams                 # 当前学期考试安排
python3 -m zhlj --exams --xnm 2025 --xqm 3
python3 -m zhlj --grades                # 成绩（如遇验证码会提示）
python3 -m zhlj --grades --json
```

成绩查询在教务系统里带顶象验证码；如果会话或页面判定需要验证，`--grades` 会提示你手动过验证码。考试安排不需要验证码，已接入正常抓取流程。

Cookie 保存在 `data/session.json`（权限 0600，已在 `.gitignore` 里），不会写进配置或日志。会话过期后重复上面的步骤即可。没有配置 Cookie 时，`zhlj_notice` 会自动跳过，其余公开来源照常工作。

## 定时运行

仓库里提供了 systemd user unit 示例（未自动安装）：

```bash
mkdir -p ~/.config/systemd/user
cp deploy/zhlj.service deploy/zhlj.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zhlj.timer
systemctl --user list-timers zhlj.timer
journalctl --user -u zhlj.service -n 50
```

默认每小时运行一次。想更保守可以改 `deploy/zhlj.timer` 里的 `OnUnitActiveSec`。

## 数据

- `data/zhlj.db`：公告、去重键、运行记录
- `data/zhlj.log`：运行日志（滚动保留 3 份）
- `data/session.json`：门户登录 Cookie（敏感，0600 权限，务必不要外传）
- `data/session_jwgl.json`：教务系统登录 Cookie（同样敏感）

去重键按文章 ID 归一化，因此同一篇公告即使同时出现在官网列表和智慧珞珈 JSON 里，也只会入库、通知一次。

## 测试

```bash
python3 -m unittest discover -s tests -t . -v
```

测试全部使用 `tests/fixtures/` 下保存的页面快照，不访问网络。

## 已知限制

- 站点带 WAF：连续快速请求会直接断开 TLS 连接。客户端已做串行、限速、退避；被封后本轮停止，下次定时再试。
- `new2024/tzggnr.jsp` 形式的详情页可能返回“暂无权限”，此时只保留列表元数据。
- 页面改版会导致解析失败，日志里会记录，不会静默。

## 实现说明：登录态抓取

教务系统和门户都在 CAS 之后。本版本没有使用 Playwright，而是复用浏览器里已经登录的 Cookie：

1. 在浏览器登录后，从 DevTools 的请求头复制 `Cookie`；
2. 用 `--set-cookie` 存到本地（门户）和 `--set-cookie --session jwgl`（教务）；
3. 定时抓取时直接带 Cookie 请求接口。

已实现的登录态能力：

- 门户聚合通知（`zhlj_notice`）：组织部、团委、研究生院、智慧校园部等
- 作业与课程提醒（`zhlj_message`）：珞珈在线
- 课表（`--courses`）：`/whdxIndex/getCurriculum`
- 考试安排（`jwgl_exams` / `--exams`）：`/kwgl/kscx_cxXsksxxIndex.html?doType=query`
- 成绩（`--grades`）：`/cjcx/cjcx_cxXsgrcj.html?doType=query`

会话过期后重新执行 `--set-cookie` 即可。成绩查询页带顶象验证码；如果教务系统要求验证，程序会提示手动完成，不会尝试绕过。

## 后续：Android APK

后续计划是做成手机端直接抓取的 APK。需要把现有的 HTTP 抓取逻辑迁移到 Android（Kotlin/Java 或嵌入式 Python），并处理：

- 首次登录：用 WebView 走 CAS，读取 Cookie；
- 后台调度：WorkManager（系统限制下最短约 15 分钟）；
- 通知：本地通知或 ntfy；
- 站点 WAF：保持低频、串行请求。

当前 Python 版本可以作为接口和字段的参考实现。
