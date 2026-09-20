# jvm-incident-agent 项目规划书

> 版本 v1.0 · 2026-09-20 · 状态:待开工(旗舰项目一,大体量)
> 本文档是施工依据,内容自洽。开工前把本文件复制进项目仓库 `docs/PLAN.md`。
> 说"按 PLAN 执行 M1"即只做 M1 范围,做完停下等验收。
> 标注 [需验证] 的技术事实,开工第一天先核实再动工。

---

## 1. 一句话定位

**JVM 事故分析 Agent:把线程 dump、GC 日志、堆直方图、异常堆栈扔进去,输出一份带证据链的根因分析报告。确定性解析器负责"事实",LLM 只负责"把事实讲成人话并给修复建议"——离线可跑,不上传任何数据。**

## 2. 为什么这是旗舰

- 目标用户:每个跑 Java 的工程师和团队,痛点高频且剧烈(线上出事就要它)
- 技术密度:二进制格式解析、图算法、时序分析、LLM 管线,面试/简历能讲一年
- 竞品全是"半件":要么有工具没分析(Eclipse MAT 重且教学门槛高),要么在线不私密(gceasy.io),要么纯聊天没证据(直接问 ChatGPT)

## 3. 输入输出定义(MVP 边界)

**输入(事故快照目录,四种文件自动识别):**

| 文件 | 格式 | 解析方式 |
|---|---|---|
| 线程 dump | `jstack -l <pid>` 输出,支持同一时刻多份 | 文本状态机 |
| GC 日志 | JDK8 传统格式 + JDK9+ `-Xlog:gc*` 统一日志 | 正则逐行 + 时间线重建 |
| 堆直方图 | `jmap -histo[:live] <pid>` 文本输出 | 表格解析 |
| 应用日志/异常 | 任意文本,提取异常堆栈聚类 | 正则 + 堆栈指纹去重 |

**输出:**
- `report.md`:事故时间线 + 发现清单(每条带证据引用)+ 根因假设排序 + 修复建议
- `--format json`:结构化 findings,供程序/agent 消费
- MCP 工具面 + Skill,让 Claude/Qoder 直接调用它分析用户贴的 dump

## 4. 分析规则清单(确定性内核)

### 线程 dump(TDA 系列)
| ID | 检测 | 方法 |
|---|---|---|
| TDA001 | 死锁 | 解析 `waiting to lock <0x..>` / `locked <0x..>` 建 wait-for 图,Tarjan 求强连通分量 |
| TDA002 | 锁竞争热点 | 统计被等待监视器地址的入度 Top N,定位"一锁多等" |
| TDA003 | 线程泄漏 | 同名线程组数量异常(如 pool-N-thread-M 超阈值)、多份 dump 间只增不减 |
| TDA004 | 阻塞栈热点 | RUNNABLE 但栈顶落在 socketRead/epollWait 的分组计数;BLOCKED/WAITING 按栈指纹聚类 |
| TDA005 | 线程池饥饿 | 全池线程同处一个业务栈帧(结合 dump 数量与时间) |

### GC 日志(GCA 系列)
| ID | 检测 |
|---|---|
| GCA001 | Full GC 频率超阈值(如 >1 次/分钟持续 5 分钟) |
| GCA002 | 长停顿(P99 pause > 用户设的 SLA,默认 200ms) |
| GCA003 | 堆泄漏指纹:连续 N 次 Full GC 后老年代最低水位单调上升 |
| GCA004 | 过早晋升:Survivor 不足 + 大对象直入老年代事件计数 |
| GCA005 | 元空间/压缩指针类配置问题(按日志中的具体提示匹配) |

### 堆直方图(HIS 系列)
| ID | 检测 |
|---|---|
| HIS001 | Top 消费者按类分组(byte[]/char[]/String 占比异常提示配 dump 分析) |
| HIS002 | 实例数与浅堆乘积排序,给出"值得用 MAT 深挖"的类清单 |

### 异常堆栈(EXC 系列)
| ID | 检测 |
|---|---|
| EXC001 | 堆栈指纹聚类(异常类 + 前 5 帧),Top 异常排行 |
| EXC002 | 因果链提取(caused by 根因帧落在哪个业务包) |

**LLM 层职责边界(测试锁定)**:规则引擎产出的 findings 集合不因 `--llm` 开关而改变;LLM 只做三件事——把 findings 串成叙事、给修复建议、追问时解释原理。默认 Mock Provider 离线可用;`--llm` 接 OpenAI 兼容端点(环境变量 `JIA_LLM_BASE_URL / JIA_LLM_API_KEY / JIA_LLM_MODEL`)。

## 5. 技术栈与架构

**语言:Java 17**(旗舰就该是 Java:目标用户信任度、你讲设计的深度、生态工具链都占优)。CLI 框架 Picocli;构建 Maven;测试 JUnit 5。

```
jvm-incident-agent/
├── src/main/java/dev/jingyu/jia/
│   ├── parse/
│   │   ├── ThreadDumpParser.java    # jstack 文本 → Thread[] 模型(含锁地址)
│   │   ├── GcLogParser.java         # 双格式 → GcEvent 时间线
│   │   ├── HistoParser.java         # jmap -histo 表格 → ClassStat[]
│   │   └── StackParser.java         # 异常堆栈 → 指纹
│   ├── analyze/
│   │   ├── WaitForGraph.java + TarjanSCC.java   # TDA001
│   │   ├── rules/tda/...  gca/...  his/...  exc/...   # 每规则一个类
│   │   └── Engine.java              # 注册表 + 执行 + Finding 汇总排序
│   ├── llm/  (Provider 接口 / MockProvider / OpenAICompatProvider)
│   ├── report/ (MarkdownReport / JsonReport)
│   ├── mcp/  # stdio JSON-RPC 2.0 服务器:tools/list + tools/call
│   └── Cli.java  # jia analyze <dir> [--llm] [--format md|json]
├── skills/jvm-incident-agent/SKILL.md
├── server/Dockerfile + docker-compose.yml   # 一键:容器内跑靶子+生成真实 dump
├── corpus/          # 测试语料,见 §8
├── tests/
└── README.md / README.zh-CN.md / LICENSE(MIT)
```

**MCP 实现说明 [需验证]**:优先评估官方 `io.modelcontextprotocol.sdk:mcp`(存在性与版本第一天确认);若依赖过重,直接手写 stdio JSON-RPC 2.0(协议面很小:initialize / tools/list / tools/call 三个方法),不引入 Spring。

## 6. 接口设计

```bash
jia analyze ./incident-2026-09-20/            # 自动识别目录内各文件
jia analyze dump.txt --format json
jia deadloop --pid 12345                       # v1.1:现场抓 dump(jstack 子进程)
jia mcp                                        # 以 MCP server 方式常驻 stdio
```

MCP 工具:`analyze_snapshot`(路径/内容) / `explain_finding`(规则 ID → 文档) / `list_rules`。
退出码:0 无高危发现 / 1 有 / 2 解析失败。

## 7. 里程碑(按每周 10~15 小时,共 6~8 周)

- **M1(D1~D5)靶子应用先行**:写一个 `demo-victim` Spring Boot 小应用,埋 4 个可复现事故:死锁接口、内存泄漏接口(byte[] 缓存无界增长)、GC 风暴接口(大量短命大对象)、线程泄漏接口。Docker Compose 一键起,附"如何抓 dump"脚本。**验收:本地能产出四种真实事故文件。**
- **M2(D6~D12)解析层**:四个 Parser + 全部单测(corpus 用 M1 真实产物 + 手工 fixture)。**验收:解析结果与人工核对一致;畸形输入不 crash。**
- **M3(D13~D20)规则引擎**:17 条规则逐条实现,每条配正/负用例(负例=健康 dump 必须 0 误报)。**验收:对 corpus 四场景,根因假设第一名命中真相。**
- **M4(D21~D25)LLM + 报告**:Mock/真实双 Provider、Markdown/JSON 报告、`--llm` 不改变 findings 的锁定测试。
- **M5(D26~D30)MCP + Skill + Docker 分发**:MCP server 实测跑通;Skill;Docker 一键复现事故环境(这是传播素材)。
- **M6(D31~D35)文档与发布素材**:双语 README、demo GIF(录:容器起事故 → jia 一条命令 → 报告生成)、文章《一次 Full GC 风暴的完整解剖》。
- **M7 发布周**:Show HN + 掘金 + V2EX + r/java;提交 awesome-spring / awesome-java 收录;48h 在线响应。

## 8. 测试语料(corpus)策略

真实 dump 涉及隐私难收集,方案:**M1 的靶子应用自产 corpus 入库**(git LFS 或压缩存放),再加社区公开样例。每条规则的单测必须同时喂"事故样本"和"健康样本",健康样本 0 findings 是硬门禁。

## 9. 范围外(v2 候选,现在不做)

- HPROF 二进制堆文件解析(工作量大,引导用户用 MAT,v2 评估)
- async-profiler 火焰图、JFR 分析
- 接入生产环境的常驻 agent(字节码增强)
- Web UI(报告就是 md,MCP 就是界面)

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| jstack 格式跨 JDK 版本差异 | 只承诺支持 JDK8~21 常见格式 [需验证:开工首日各版本实测生成];解析失败降级为 info |
| 规则误报毁口碑 | 健康样本 0 误报门禁;每条 finding 带证据行号可人工复核 |
| 体量太大烂尾 | 里程碑串行,M3 结束即达到"能用",M4~M6 是增强;最坏情况 M3 后先发 0.1 版 |
| 竞品(Eclipse MAT/gceasy)心智 | README 明确定位差异:命令行/可脚本化/agent 可调用/数据不出本机 |

## 11. 完成判定

- [ ] 对 corpus 四类事故,报告第一名根因正确;健康样本 0 误报
- [ ] `docker compose up` 一键复现事故 + 一键分析
- [ ] MCP 在 Claude 或 Qoder 实测;Skill 收录
- [ ] build/test 全绿、双语 README、GIF、LICENSE、文章发布

---

## Build log (deviations from the plan)

Everything above was built as written. Four things turned out differently from the plan, and
only those four:

1. **Rule count.** §4 lists 14 rule ids while M3 says "17 条规则"; the shipped engine has
   **18** — TDA001-006, GCA001-006, HIS001-003, EXC001-003 (`jia rules`, catalogued in
   `docs/rules.md`). The extra rows are the ones the tables implied but did not enumerate
   (TDA006 CPU-hot thread, GCA006 GC throughput, HIS003 container-count ceiling,
   EXC003 exception burst over time).
2. **MCP SDK.** The plan's `[需验证]` note is resolved: `io.modelcontextprotocol.sdk:mcp:2.0.1`
   exists and was verified, but is **not used**. The protocol surface needed is three methods
   (`initialize`, `tools/list`, `tools/call`), so `dev.jingyu.jia.mcp.McpServer` hand-rolls a
   newline-framed JSON-RPC 2.0 stdio loop instead of pulling reactor-core into a CLI jar.
3. **Version coverage.** §10 assumed dumps/logs would be generated on several JVMs
   (`[需验证:开工首日各版本实测生成]`). Only JDK 17 exists on the build machine, so JDK 8 and
   other-format inputs are covered by hand-written fixtures under
   `src/test/resources/fixtures/` (e.g. `gc-jdk8-parallel.log`, `histo-jdk8.histo`) plus the
   real JDK 17 captures in `corpus/`, not by multi-JVM captures.
4. **GC log capture.** `-Xlog:gc*:file=` is block-buffered on Windows, so a file tail can be
   empty at the moment `scripts/capture.sh` copies it. Capture therefore tees GC output from
   the JVM's **stdout** (`-Xlog:gc*` with no `file=`) into `corpus/*/gc.log`; the artifact is
   byte-for-byte the same unified-log format.

