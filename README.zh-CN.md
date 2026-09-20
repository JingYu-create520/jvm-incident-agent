# jvm-incident-agent(中文版)

**把线程 dump、GC 日志、堆直方图、应用日志一起丢进去,得到一份根因报告——每条结论都标注它来自哪个文件的第几行。**

确定性解析器负责"事实",语言模型(可选、默认关闭)只负责把事实讲成人话。全程离线运行:你的 dump 不会离开这台机器,同样的输入永远得到同样的结论。

```
$ jia analyze ./incident-2026-09-20/

## Verdict

**The live set is growing — after each major collection more heap survives than the last
time. Full GC pressure is downstream of this, not the cause. Corroborated by GCA003.**

Confidence 90% · severity CRITICAL · corroborated by GCA003

$ echo $?
1
```

这是对 [`corpus/incident-heap-leak`](corpus) 里一台**真实 JVM** 的实际输出——一个埋了无界 `byte[]`
缓存的 Spring Boot 应用。规则抓到的是泄漏本身,不只是"GC 很频繁"这个症状:

```
GCA001 CRITICAL 1.0 分钟内 13 次 Full GC(13.0/min),累计 STW 551 ms
GCA003 CRITICAL 13 次主要回收后老年代低水位停在 217M,13 次里有 11 次 Full GC 后仍有 88%
                  的堆是活对象——已经回收不动了(容量 256M)
HIS001 HIGH      [B 占了 229.2 MB 中的 165.2 MB(占全部可统计字节的 72.1%,
                  2,144,188 个实例,平均 81 字节)
GCA004 HIGH      11 次复制空间耗尽;41 次由超大对象(humongous)分配触发
```

同一个工具跑同一套流程抓的**健康服务** [`corpus/healthy`](corpus):`0 findings`,退出码 `0`。
这条硬性门禁就是整个项目的立身之本,见[误报才是真问题](#误报才是真问题)。

---

## 目录

- [为什么不用 MAT / gceasy / 直接问大模型](#为什么不用-mat--gceasy--直接问大模型)
- [安装](#安装) · [快速上手](#快速上手) · [能读什么](#能读什么)
- [18 条规则](#18-条规则) · [证据链](#证据链) · [LLM 只负责叙述](#llm-只负责叙述)
- [给 Agent 用(MCP)](#给-agent-用mcp) · [CI 门禁](#ci-门禁)
- [主动复现事故](#主动复现事故) · [误报才是真问题](#误报才是真问题) · [边界](#边界) · [开发](#开发)

## 为什么不用 MAT / gceasy / 直接问大模型

| | |
|---|---|
| **Eclipse MAT** | 分析堆转储它确实是最强工具,本项目也会明确把你引导到它。但它是一个 IDE 形态的 GUI,有真实学习成本,凌晨三点没法用命令行跑,也不能把 GC 日志和线程 dump 当成同一起事故看。 |
| **gceasy.io** | GC 分析很好。前提是你把生产的 GC 日志传到别人的网站上。 |
| **直接问大模型** | 它会基于一小段栈信息认真地告诉你"把 `-Xmx` 调大",没有证据链,不可复现,也不知道 200 个线程停在 `getTask` 上是一台健康服务器的常态。 |
| **`jstack` + grep** | 大多数人的现状。这个项目等于把其中的 wait-for 图、GC 滑动窗口、直方图算术提前做完了,而且是四种输入一起看。 |

定位一句话:**命令行、可脚本化、可被 Agent 调用、数据不出本机。**

## 安装

只需要 Java 17+ 运行时,没有别的安装步骤。

```bash
./mvnw -q -DskipTests package          # 产出 target/jia.jar(shaded,可直接运行)
java -jar jia.jar --version
```

也可以用 `bin/jia analyze ./incident/`,首次运行会自动把 jar 构建出来。

```bash
docker build -f Dockerfile.cli -t jia .
docker run --rm -v "$PWD/incident:/in:ro" jia analyze /in
```

## 快速上手

**指向一个目录**。文件类型靠内容识别而不是文件名,混着放也没关系。

```bash
mkdir -p incident-2026-09-20 && cd incident-2026-09-20
jstack -l 12345 > threads.dump
sleep 5 && jstack -l 12345 > threads-2.dump     # 第二份 dump 才能解锁"只增不减"类规则
jmap -histo  12345 > heap.histo
cp /var/log/app/app.log .                        # 有 -Xlog 产出的 GC 日志也放进来
cd .. && jia analyze ./incident-2026-09-20 -o ./incident-2026-09-20/ --format both
```

`report.md` 给人看,`report.json` 给程序看,每条结论都带 `file:line` 证据。常用参数:

```
--sla-ms 100            你的停顿预算,而不是默认 200
--full-gc-per-min 2     风暴的判定线
--thread-leak-threshold 60
--lock-waiters 5        多少个等待者算锁竞争
--exception-threshold 10
--format json           结构化输出
--llm                   用 OpenAI 兼容端点生成叙述(结论不变)
--no-narrative          只要规则输出
```

## 能读什么

| 输入 | 格式 | 解析方式 |
|---|---|---|
| 线程 dump | `jstack -l`、`jcmd Thread.print`,一个文件里多份 | 显式状态机而不是一个大正则——JDK 8→21 的漂移(`cpu=`/`elapsed=` 列、模块限定帧、死锁尾巴重复线程段)逐个处理 |
| GC 日志 | JDK 8 传统 `-XX:+PrintGCDetails`,JDK 9+ 统一 `-Xlog:gc*` | 逐行归一成一条时间线;`GC(n)` 的多条记录合并回一次回收 |
| 堆直方图 | `jmap -histo[:live]`、`jcmd GC.class_histogram` | 表格解析,剥掉模块后缀,`Total` 行缺失时用可见行求和兜底 |
| 应用日志 | 任何含堆栈的文本,适配 logback/log4j 形态 | 提取 throwable 块与 `Caused by:` 链,按"根因类 + 前 5 帧"做指纹 |

字符集会探测(UTF-8 → GBK → Latin-1),因为这些文件来自别人的机器。

## 18 条规则

`jia rules` 列出清单,`jia explain <ID>` 给出它**怎么工作**以及**会在哪里出错**——每条规则都必须写清自己的误报边界。

**线程 dump**:TDA001 死锁(wait-for 图 + Tarjan SCC,监视器和 `ReentrantLock` 都算,JVM 自带检测器看不见后者)· TDA002 锁竞争热点(含持有者是谁)· TDA003 线程泄漏(同名族过大,或多份 dump 间只增不减)· TDA004 阻塞栈热点(以及 RUNNABLE 其实卡在 socket 上的那类)· TDA005 线程池饥饿 · TDA006 CPU 热点线程(用 `cpu`/`elapsed` 算核数,单 dump 是生命周期均值,两份 dump 取增量)

**GC 日志**:GCA001 最密窗口内的 Full GC 风暴 · GCA002 超过 SLA 的停顿(报 p50/p95/p99/max)· GCA003 GC 后活集合上升,以及堆满之后的"高位平台"形态 · GCA004 过早晋升(复制空间耗尽、humongous、低收益 young GC)· GCA005 配置异味(metaspace 压力、`System.gc()`、JVM 自己的提示语)· GCA006 GC 吞吐过低

**堆直方图**:HIS001 单类占比压制,以及只有 `byte[]/char[]/String` 可言的情形 · HIS002 值得拿去 MAT 打开的业务类 · HIS003 容器/节点实例数不合理

**应用日志**:EXC001 按根因栈聚类的重复异常 · EXC002 穿透框架包装定位到你自己的代码 · EXC003 时间突增——事故是从哪一分钟开始的

之后规则结果会被**关联**成排序后的根因假设,因为"GC 风暴"和"活集合在涨,所以 GC 风暴"来自同一份日志,而只有一个值得先做。

## 证据链

每条结论都说明出处,而且你可以去核对:

```
### TDA001 · Deadlock (cycle in the wait-for graph)
CRITICAL · confidence 99% · from thread dump

victim-statement-writer waits for 0x00000000ff6309a0 (java.lang.Object), held by
victim-ledger-poster; victim-ledger-poster waits for 0x00000000ff6309b0, held by
victim-statement-writer.

**Evidence**
threads.dump:495  "victim-ledger-poster" #51 daemon prio=5 … ← BLOCKED,等的是 0x…9b0
threads.dump:498  - waiting to lock <0x00000000ff6309b0> (a java.lang.Object)
threads.dump:511  - locked <0x00000000ff6309b0>   ← 监视器实际在另一个线程这里被持有
```

退出码是接口的一部分:`0` 无高危,`1` 至少一条 HIGH/CRITICAL,`2` 输入无法理解。

## LLM 只负责叙述

规则先跑完,模型最后才被联系。`--llm` 只多一段话,不能增删、改写或改权重任何结论——而且有测试专门盯着这件事:

```java
// EngineTest.narrativeCannotMoveFindings
Provider blabber = result -> "THERE IS NO DEADLOCK, EVERYTHING IS FINE, RESTART THE POD";
assertEquals(before, after);                     // 结论列表逐字节一致
assertTrue(report.contains("TDA001"));           // 谎言没能进入结论区
```

用环境变量配置,任何 OpenAI 兼容端点都行(OpenAI、DeepSeek、Qwen、Ollama、vLLM、公司网关):

```bash
export JIA_LLM_BASE_URL=https://api.openai.com/v1
export JIA_LLM_API_KEY=sk-…
export JIA_LLM_MODEL=gpt-4o-mini
jia analyze ./incident/ --llm
```

发出去的只有结论摘要——绝不发原始 dump、日志或直方图。没配环境变量或端点失败时,自动退回内置的离线叙述,分析照样成功完成。

## 给 Agent 用(MCP)

```bash
jia mcp        # stdio,换行分帧的 JSON-RPC 2.0
```

```json
{
  "mcpServers": {
    "jvm-incident-agent": { "command": "java", "args": ["-jar", "/path/to/jia.jar", "mcp"] }
  }
}
```

三个工具:`analyze_snapshot`(给路径,或直接给 `content` + `fileName`)、`explain_finding`(规则 ID → 文档)、`list_rules`。聊天窗口里粘的一段 `jstack` 也能变成带行号引用的真正分析。

传输层是手写的,没有用官方 SDK——理由写在
[`docs/adr/0001-hand-written-mcp-transport.md`](docs/adr/0001-hand-written-mcp-transport.md)。

## CI 门禁

`jia` 的退出码就是给断言用的:

```yaml
- name: JVM incident triage
  run: |
    java -jar jia.jar analyze ./dist/tomcat/logs/incident --format json -o /tmp/jia/ || rc=$?
    # 0 = 干净,1 = 有高危,2 = 没解析动
    test "${rc:-0}" -lt 2
```

也可以当成失败构建的摘要器:JSON schema 是 `jvm-incident-agent/1`,版本号在负载里。

## 主动复现事故

分析总得有东西可分析,而把生产数据塞进测试仓库并不合理。所以仓库同时提供**靶子应用**和弄坏它之后的真实产物。

[`demo-victim/`](demo-victim) 是一个埋了事故的 Spring Boot 应用:

| 接口 | 埋的事故 |
|---|---|
| `GET /victim/deadlock` | 两个线程两把监视器反向加锁(外加一把有 8 个等待者的热点锁) |
| `GET /victim/leak?mb=32` | `byte[]` 分片不断追加进一个无界缓存 |
| `GET /victim/gcstorm?rounds=40` | 成千上万个短命大数组 |
| `GET /victim/leak-threads?count=80` | 每次请求都新建线程且从不回收 |
| `GET /victim/errors?count=40` | 带真实框架帧的包装异常链 |
| `GET /victim/healthy` | 干净路径——零误报门禁就拿它来量 |

```bash
cd demo-victim && ../mvnw -q -DskipTests package
cd .. && docker compose -f server/docker-compose.yml up    # 或者直接在宿主机上跑
scripts/capture.sh -o corpus/incident-deadlock -d 6        # jstack -l、jmap -histo、日志
```

[`corpus/`](corpus) 里的每个字节都是活 JVM 的真产物,没有一份是手写的。六个场景,每个配一份
`TRUTH.md` 写清埋了什么、应该触发哪些规则。`CorpusTest` 就断言这些,所以"改了一条规则,结果
分不清堆泄漏和分配风暴"这种提交会直接把构建搞挂。

## 误报才是真问题

一个总喊狼来了的排障工具会被卸载。三条写在代码里、由测试强制执行的规矩:

1. **健康输入必须零结论。** `corpus/healthy` 是一个真实运行、有负载的 Spring Boot 应用,10 个
   servlet 线程、直方图榜首就是 `[B`,测试断言它一条都不报。启动期的 `Metadata GC Threshold`
   ——每个 Spring Boot 日志都有——被明确判定为不算事故。
2. **"空转"不等于"卡住"。** 200 个 worker 停在 `ThreadPoolExecutor.getTask` 上是安静的夜晚。
   排除名单是 `ThreadNoise` 里显式写出来的,不是猜出来的——这一条决定了线程 dump 规则到底能用
   还是只会吵。
3. **比例之外还要有绝对值。** 在一份 6 MB 的直方图里说"`byte[]` 占了 24%"毫无意义,所以规则要
   同时满足 16 MB 和 35% 才开口。

规则仍可能误导的地方,它自己的文档里就写着——这一段是 `RulesTest.catalogueIsComplete` 强制要求的。

## 边界

- **不支持 `.hprof`。** 堆转储解析是另一个工程;报告只会告诉你该在 MAT 里看什么。
- **按 JDK 8 → 21 的形态测过**,但产物是在 JDK 17 上抓的。老格式由手写 fixture 覆盖;真正古怪的
  格式会降级成一条 `INFO`,而不是报错。
- **一次快照只看一个 JVM。** 不做跨服务、跨进程的关联。
- GC 吞吐只统计 `-Xlog:gc*` 打出来的停顿;不是 GC 停顿的 safepoint 停顿看不见,所以真实值只会
  比报告更差,不会更好。
- 墙钟与 uptime 对齐需要日志同时带两种装饰器;不带时,报告里会明说。

## 开发

```bash
./mvnw test                       # 74 个测试,约 4 秒
./mvnw -q -DskipTests package     # target/jia.jar
jia rules --format json           # 机器可读的规则目录
```

结构:`parse/`(四个解析器 + 类型嗅探)、`analyze/`(图、Tarjan、18 条规则、假设排序)、
`llm/`、`report/`、`mcp/`、`Cli.java`。`docs/PLAN.md` 是本次施工遵循的设计原件。

新增规则的贡献必须同时带上它的反例——这是真正的硬性要求。

## 许可

MIT,见 [LICENSE](LICENSE)。
