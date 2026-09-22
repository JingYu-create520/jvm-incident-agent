# jvm-incident-agent(中文版)

**把线程 dump、GC 日志、堆直方图、应用日志一起丢进去,得到一份根因报告——每条结论都标注它来自哪个文件的第几行。**

确定性解析器负责"事实",语言模型(可选、默认关闭)只负责把事实讲成人话。全程离线运行:你的 dump 不会离开这台机器,同样的输入永远得到同样的结论。

![容器里复现事故 → 一条命令分析 → 退出码当门禁](docs/assets/demo.gif)

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

## 为什么不用 MAT / gceasy / 直接问大模型

Eclipse MAT 分析堆转储确实是最强工具,这个项目也会明确把你引导到它。但它是一个 IDE 形态的 GUI,
有真实学习成本,凌晨三点没法用命令行跑,也不能把 GC 日志和线程 dump 当成同一起事故看。

gceasy.io 的 GC 分析很好,代价是你生产的 GC 日志要传到别人的网站上。

直接问大模型,它会基于一小段栈信息认真地告诉你"把 `-Xmx` 调大"——没有证据链,不可复现,也不知道
200 个线程停在 `getTask` 上只是一台健康服务器的常态。

`jstack` 加 grep 是大多数人的现状。这个项目等于把其中的 wait-for 图、GC 滑动窗口、直方图算术提前
做完了,而且四种输入一起看。

我占的位置很窄,也很明确:命令行、可脚本化、可被 Agent 调用、数据不出本机。

## 安装

只需要 Java 17+ 运行时,没有别的安装步骤。

```bash
./mvnw -q -DskipTests package          # 产出 target/jia.jar(shaded,可直接运行)
java -jar jia.jar --version
```

也可以用 `bin/jia analyze ./incident/`,首次运行会自动把 jar 构建出来。

```bash
docker build -f Dockerfile.cli -t jia .
docker run --rm -v "$PWD/incident:/input:ro" jia          # 不用给路径:WORKDIR 就是 /input
```

镜像会把挂载进 `/input` 的内容分析一遍,报告打到 stdout。
**Windows + Git Bash 注意**:挂载要写 Windows 风格的主机路径,比如
`-v "D:/projects/incident:/input:ro"`——Git Bash 会把 `$PWD` 改写成 MSYS 路径(`/d/…`),
Docker 不报错,但挂进去是空目录。PowerShell 里没这个问题。

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
--mat-share 0.09        文档里那条边界:CachedRow 占 9.32%,调到 9% 就会点名它
--stall-min 1           多少次 ZGC 分配停顿才算"成模式"
--format json           结构化输出
--llm                   用 OpenAI 兼容端点生成叙述(结论不变)
--no-narrative          只要规则输出
```

凡是能决定一条结论的阈值都做成了参数(`jia analyze --help` 全列),而 `report.json` 会在
`thresholds` 里把整套阈值原样吐出来。这是故意的:一条你动不了的边界不叫工程决策,只是某人随手
定的默认值 —— 所以如果你不认同"9.32 % 不足以判定泄漏",你可以用一条命令把另一种答案跑出来,
而不是去开一个 issue。

## 能读什么

| 输入 | 格式 | 解析方式 |
|---|---|---|
| 线程 dump | `jstack -l`、`jcmd Thread.print`,一个文件里多份 | 显式状态机而不是一个大正则——JDK 8→21 的漂移(`cpu=`/`elapsed=` 列、模块限定帧、死锁尾巴重复线程段)逐个处理 |
| GC 日志 | JDK 7/8 传统 `-XX:+PrintGCDetails`(Parallel、Serial、CMS——认 `ParNew`、`[CMS: …]` 老年代,以及会被排除在堆统计之外的永久代块),JDK 9+ 统一 `-Xlog:gc*` | 逐行归一成一条时间线;`GC(n)` 的多条记录合并回一次回收;每次回收只取它自己的停顿总时长,不取第一个阶段 |
| 堆直方图 | `jmap -histo[:live]`、`jcmd GC.class_histogram` | 表格解析,剥掉模块后缀,`Total` 行缺失时用可见行求和兜底 |
| 应用日志 | 任何含堆栈的文本,适配 logback/log4j 形态,终端着色过的日志也算 | 提取 throwable 块与 `Caused by:` 链,按"根因类 + 前 5 帧"做指纹 |

字符集会探测(UTF-8 → GBK → Latin-1),BOM 和 CRLF 会剥掉,终端转义序列(`ESC[…m` 这些颜色码——
Spring Boot 输出被重定向进文件、或者被 CI 录下来时就会写进去)在读入时一律清除,所以证据引用是
人能读的文本,行号也还对得上同一行。这些文件都来自别人的机器。

一次运行只读**一份 GC 日志、一张堆直方图**,因为一次事故本来就只有这些:一个时间窗、一个瞬时。
你把轮转过的整套(`gc.log` 加 `gc.log.0`)或者两份 `jmap` 丢进来时,它保留先看到的那份——扫目录的
顺序意味着没有后缀的当前文件会胜出——并在 Markdown 的"覆盖范围与局限"一段、`report.json` 的
`ignoredInputs` 字段里点名丢掉的那份。只覆盖半个窗口的报告必须自己说清楚,否则它和覆盖了整个窗口的
报告长得一模一样。同样的规矩也适用于字段,不只是文件:如果你的应用日志只打 `HH:mm:ss.SSS` 这种不带日期
的时间,突发规则就没法把任何一条栈放进某一分钟里,于是报告会直接告诉你它解析出的栈里有多少条找不到绝对
时间戳(`report.json` 的 `exceptionClock`),而不是让"没有突发"被当成一次澄清。

第三种情况是规则压根没法测量。Full GC 频率至少要有两次主要回收,存活集指纹要有几次,吞吐百分比要十个事件
跨十秒,线程*增长*要第二份 dump——而规则拿到了工件却不够它算的时候,过去是返回空并被记成 `clean`,于是一份
被截断的日志就能印出"*这份快照里每条规则都跑得很干净*"。现在它会说清自己做了什么:`ruleStatus` 给出
`declined: <它需要什么>`,同样几行也出现在"覆盖范围与局限"里,而结论只有在没有任何规则弃权时才敢说全都干净。
同一轮里有一条规则变严了:`GCA002` 以前会忽略不足三次停顿的日志,所以单独一次 305 ms 的停顿撞上 200 ms SLA
就没人报;现在它报,但封顶在 `MEDIUM`,并且写明这就是只有一个数据点的测量。

## 19 条规则

`jia rules` 列出清单,`jia explain <ID>` 给出它**怎么工作**以及**会在哪里出错**——每条规则都必须写清自己的误报边界。

**线程 dump**:TDA001 死锁(wait-for 图 + Tarjan SCC,监视器和 `ReentrantLock` 都算,JVM 自带检测器看不见后者)· TDA002 锁竞争热点(含持有者是谁)· TDA003 线程泄漏(同名族过大,或多份 dump 间只增不减)· TDA004 阻塞栈热点(以及 RUNNABLE 其实卡在 socket 上的那类)· TDA005 线程池饥饿 · TDA006 CPU 热点线程(用 `cpu`/`elapsed` 算核数,单 dump 是生命周期均值,两份 dump 取增量)

**GC 日志**:GCA001 最密窗口内的 Full GC 风暴 · GCA002 超过 SLA 的停顿(报 p50/p95/p99/max)· GCA003 GC 后活集合上升,以及堆满之后的"高位平台"形态 · GCA004 过早晋升(复制空间耗尽、humongous、低收益 young GC)· GCA005 配置异味(metaspace 压力、`System.gc()`、JVM 自己的提示语)· GCA006 GC 吞吐过低 · GCA007 分配停顿(ZGC 把索要内存的那个线程停下来,日志里连线程名都带着)

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

退出码是接口的一部分:`0` 没有达到门禁线的结论,`1` 有,`2` 输入看不懂。门禁线由 `--fail-on` 决定:默认 `high`,CI 里建议 `--fail-on critical`(别让一条 MEDIUM 建议挂掉构建),只想看报告就 `--fail-on never`。

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
    # 0 = 干净,1 = 达到门禁线,2 = 没解析动;门禁线用 --fail-on 调
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
# 一条命令:构建靶子镜像、起容器、埋一个死锁、把四种产物抓进 corpus/incident-deadlock/
docker compose -f server/docker-compose.yml --profile repro run --build --rm incident deadlock

# 然后在宿主机上分析(或者挂进容器里分析,见上面「安装」)
jia analyze corpus/incident-deadlock
```

靶子应用**完全不占用宿主机端口**:触发脚本走 compose 网络直连容器 `http://victim:8080`。
这是特意改的——它以前默认发布 8081,于是一台 8081 已被占用的机器上,那条一键命令会直接死在
`Bind for 0.0.0.0:8081 failed: port is already allocated`,而这条流程根本不需要那个端口。
想在浏览器里点 `/victim/*`,自己指定端口跑:`docker compose -f server/docker-compose.yml run --publish 8081:8080 victim`。
事故种类:`deadlock`、`heap-leak`、`gc-storm`、`thread-leak`、`exceptions`、`healthy`。不想用 Docker,在宿主机上直接跑:

```bash
cd demo-victim && ../mvnw -q -DskipTests package && cd ..
mkdir -p live
java -Xmx256m -Xms256m -XX:+UseG1GC \
     -Xlog:gc*:file=live/gc.log:time,uptime,level,tags \
     -jar demo-victim/target/demo-victim.jar > live/app.log 2>&1 &
scripts/capture.sh -o /tmp/incident -d 6 --gc-log live/gc.log --app-log live/app.log
```

`capture.sh` 是去抄 JVM 自己的 `-Xlog` 输出,它变不出一份 GC 日志,所以那个参数才是第四件产物存在的前提。
趁 JVM 还在跑,用上面那张表里的触发接口埋个事故,再 analyze。

[`corpus/`](corpus) 里的每个字节都是活 JVM 的真产物,没有一份是手写的。七个场景,每个配一份
`TRUTH.md` 写清埋了什么、应该触发哪些规则。`CorpusTest` 断言的就是这些;`TruthDocTest` 再把
那份 markdown 本身对着引擎核一遍——19 条规则在每个场景里都必须被点名。所以不管是"改了一条规则,
结果分不清堆泄漏和分配风暴",还是"文档说的和工具报的对不上",构建都会直接红。

## 误报才是真问题

一个总喊狼来了的排障工具会被卸载。三条写在代码里、由测试强制执行的规矩:

1. **健康输入必须零结论。** `corpus/healthy` 是一个真实运行、有负载的 Spring Boot 应用,10 个
   servlet 线程、直方图榜首就是 `[B`,测试断言它一条都不报。启动期的 `Metadata GC Threshold`
   ——我们自己的 Spring Boot 启动日志里就有——被明确判定为不算事故;0.3.1 起这句话终于在真正要命
   的地方也成立了:算 Full GC **频率**的规则和活集合指纹规则,会先把这类回收(以及外部动作逼出来的,
   比如 `jmap -histo:live` 造成的 `Heap Inspection Initiated GC`)在 `--gc-settle-sec` 窗口里丢掉再计数。
   在此之前只有"配置异味"那一条规则知道这个区分,而一份健康服务启动日志的 78 行切片会被报成
   "Full GC 风暴,HIGH"。
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
- **GC 规则读三种词汇。** G1/Parallel/Serial/CMS 里"能看清活集合"的那次回收叫 `Pause Full`;ZGC 里
  它换成整堆并发周期,压力信号换成 `Allocation Stall`(GCA007),`corpus/incident-zgc-leak` 就是为
  这两条存在的;Shenandoah 的停世界是 `Pause Init Mark` / `Pause Final Mark` 成对出现,而且它确实会跑
  真正的 Full GC——一份真实 1 GB Shenandoah 现场的切片放在
  `src/test/resources/fixtures/gc-jdk17-shenandoah.log`,下面两条 bug 就是它抓出来的。
  ZGC 这部分**只在非分代 ZGC(JDK 17)上验证过**:分代 ZGC(21+)会给周期打上
  `(Minor)`/`(Major)`,目前工具不区分,把 minor 也算成 major——只会多报,不会漏报。
- **虚拟线程看不见。** JDK 21 dump 里的 `-- virtual thread … mounted on carrier` 尾巴是有真实信息
  的(pinning 尤其),这个工具既不解析也不推理它。一个重度用 Loom 的服务,在这里只会表现成"线程数
  少得可疑"。
- **一次快照只看一个 JVM。** 不做跨服务、跨进程的关联。
- GC 吞吐只统计 `-Xlog:gc*` 打出来的停顿;不是 GC 停顿的 safepoint 停顿看不见,所以真实值只会
  比报告更差,不会更好。ZGC 的分配停顿只停住一个线程而不是整个世界,所以不计在这里,由 GCA007 单独报。
- 墙钟与 uptime 对齐需要日志同时带两种装饰器;不带时,报告里会明说。

## 开发

```bash
./mvnw test                       # 全部测试,约 4 秒
./mvnw -q -DskipTests package     # target/jia.jar
jia rules --format json           # 机器可读的规则目录
```

结构:`parse/`(四个解析器 + 类型嗅探)、`analyze/`(图、Tarjan、19 条规则、假设排序)、
`llm/`、`report/`、`mcp/`、`Cli.java`。`docs/PLAN.md` 是本次施工遵循的设计原件。

新增规则的贡献必须同时带上它的反例——这是真正的硬性要求。

## 这个项目是怎么真做出来的

说点不加修饰的。现在这些规则看着合理,是因为语料一直在以很具体的方式抓到它们错:

- 异常解析器在真实 Spring Boot 日志里**一个栈都找不到**,而我手写的那些用例全都通过。原因是我的帧正则要求行尾
  收在 `)` 上,而 logback 在后面还加了 `~[spring-web-6.2.8.jar:6.2.8]`。我自己手写的所有用例都是过的。
- `[Metaspace: 3072K->3072K(1056768K)]` 和堆变化的形状一模一样,又在同一行末尾,于是"取最后一个
  变化"的规则把一份 JDK 8 的堆读成了 1032 MB。
- `Found one Java-level deadlock:` 结尾是有冒号的,我用了要求整行匹配的 `matches()`,这个判断从来没
  成立过。结果尾巴里的 `- locked` 被算到了当时最后一个线程身上——它"持有"了自己从没锁过的监视器,
  死锁环反而没了。
- 同一个 `matches()` 错误让四个内容签名判定全都是死的,文件识别其实一直只靠文件名。没有任何报错,
  就是 quietly wrong。
- jstack 的捕获日期打在 `Full thread dump` 的**前一行**,而那个分支会无条件重建解析状态。结果时间轴
  上每一条都是 `—`。
- 健康样本一直触发 GCA005,因为我们自己的 Spring Boot 启动日志里就有一两次 `Metadata GC Threshold`。
  现在的判定是"3 次以上,且至少一次发生在 JVM 起来一分钟之后"。
- Docker 那条路 0.1.0 是**没验证就发了**,因为当时没有守护进程。真跑一遍就发现:`capture.sh` 被 `sh`
  起会死在 `set -o pipefail`;8080 端口被占;一键 thread-leak 把两次 burst 都放在第一次 dump 之前,
  抓出来是 140/140,根本演示不了"只增不减"这条规则。

`docs/PLAN.md` 是我照着做的设计原件,里面同时记了构建时偏离它的地方。`CHANGELOG.md` 记了什么变了、
为什么变。上面这些错我都写进去了,没删——如果我在看别人的仓库,这正是我希望作者写的部分。

## 许可

MIT,见 [LICENSE](LICENSE)。
