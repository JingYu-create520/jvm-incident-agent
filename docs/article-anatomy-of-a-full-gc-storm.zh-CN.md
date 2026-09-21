# 一次 Full GC 风暴的完整解剖

> 用一个可以 `docker compose up` 复现的事故,讲清"GC 很凶"和"内存漏了"为什么是两个不同的问题。
> 下面每一个数字都是真实跑出来的,数据在仓库的 `corpus/incident-gc-storm` 和
> `corpus/incident-heap-leak` 里,是活 JVM 的 `jstack`/`jmap`/`-Xlog:gc*` 原始输出。

## 现场

一个跑 G1 的 JVM,`-Xmx256m`,开始疯狂 Full GC。这是每个值班人都见过的画面:

```
$ grep -cE 'Pause Full .*ms' corpus/incident-gc-storm/gc.log
8
$ head -1 corpus/incident-gc-storm/gc.log
[2026-09-20T21:50:20.547+0800][0.010s][info][gc] Using G1
```

(注意用汇总行来数:`grep -c 'Pause Full'` 会给你 16,因为每次 Full GC 有 `gc,start` 和汇总两行。)

22 秒的日志里 2555 次回收、8 次 Full GC。第一反应,也是绝大多数人的反应,是"堆给小了,加
`-Xmx`"。这个反应在这起事故里是错的,而且错得很典型——**它把症状当成了原因**。

## 第 1 步:先算代价,而不是先看次数

`Full GC 8 次` 这句话本身没有信息量。有意义的问法是:这段时间里,应用有多少比例根本没在跑?

```
GCA006 CRITICAL  Over 22 s of log, 48.6% of wall time was spent in stop-the-world pauses
                 (10694 ms across 2555 pauses). Target is 3% or less.
```

48.6%。这台机器有一半时间在回收,不是在处理请求。所以真正的问题不是"Full GC 太多",
而是"分配速率高到收集器一半的精力都在搬运"。

次数少、占比高,和次数多、占比低,是两种完全不同的病。先把分母找出来。

## 第 2 步:谁在被回收

```
GCA001 CRITICAL  8 Full GC collections inside 1.0 minute(s) (8.0/min, threshold 1.0),
                 stopping the world for 99 ms in total, worst pause 17 ms.
HIS001 HIGH      [B holds 173.0 MB of 180.8 MB (95.7% of all bytes counted,
                 43,794 instances, 4143 bytes each).
GCA004 MEDIUM   1894 collection(s) triggered by humongous (direct-to-old) allocation;
                 1880 young collections reclaimed under 5% of the heap,
                 so most of what was copied was already old.
```

三条信息合起来讲了一个完整的故事:

1. `[B`(byte 数组)占了可统计字节的 **95.7%**,平均 **4143 字节**一个。
2. 触发回收的原因里,**1894 次是 humongous 分配**。G1 下,超过 region 大小一半的对象直接进老年代,
   不走 young。
3. 1880 次 young GC 的回收量低于堆的 5%——因为要回收的东西根本不在 young 区。

也就是说:**有人在按请求造 ~4 KB 的大数组,每个都直接落到老年代。** 收集器的所有 young 回收几乎
什么都做不了,压力一路堆到 Full GC。这是分配模式问题,不是堆大小问题。

`4143` 这个数字很关键。它说明不是单个巨型对象(那会是几 MB),而是"中大型、海量、短命"——
恰好是最伤 G1 的形状。

## 第 3 步:证明它*不是*内存泄漏

这是最容易被跳过、却最能决定下一步做什么的一步。看 Full GC **之后**还剩多少活对象:

```
incident-gc-storm :  1886 次 young、8 次 Full,GC 后堆中位数远低于容量,
                     GCA003 没有触发 —— 活集合没有单调上升,也没有钉在堆顶。
incident-heap-leak:  GCA003 CRITICAL  Across 13 major collections the old gen low-water mark is
                                       217M, sits at 11 of 13 collections with 88% of the heap
                                       still live after a Full GC — nothing is being reclaimed
                                       any more (capacity 256M)
```

同一个堆大小、同样满天飞的 Full GC,两份日志的结论完全不同:

| | GC 风暴 | 内存泄漏 |
|---|---|---|
| Full GC 后剩余 | 明显下降,还能回收 | 217M/256M,回收不动了 |
| `[B` 实例数 | 43,794 个,平均 4143 B | 2,144,188 个,平均 81 B |
| 一句话 | 垃圾造得太多 | 活对象放得太多 |
| 该做什么 | 停止按请求分配大数组、改成流式或分块 | 找持有者:MAT 的 dominator tree |
| 加 `-Xmx` 会怎样 | 延后发作,分配速率不变 | 延后 OOM,泄漏照旧 |

那 81 字节 × 214 万个 `byte[]`,加上业务类
`dev.jingyu.jia.victim.LeakyCache$CachedRow` 独占 22 MB,才是"谁在漏"的线索;
`jmap -histo` 只能到这里,再往下必须靠堆转储——这也是工具会明说的边界。

顺带一个诚实的细节:泄漏那份的 `heap.histo` 是 `jmap -histo`(**不带** `:live`),
所以报告里跟着提示"This histogram includes garbage"。带 `:live` 会强制一次 Full GC,
在抓现场时反而会污染 GC 日志——这是个取舍,不是谁忘了写。

## 第 4 步:那些"看起来像事故"的噪声

同一次分析里还有两条值得说:

```
GCA005 MEDIUM  The log itself says: "GCLocker Initiated GC" — JNI critical sections are
               forcing collections.
```

这条是 JVM 自己在日志里抱怨的,不是任何启发式猜的:某处进入 JNI 临界区(`GetPrimitiveArrayCritical`
一类调用),临界区里的 GC 请求会被推迟并累集成一次 `GCLocker Initiated GC`。它是真实存在的行为,
值得知道,但**不是** 48.6% 停顿时间的原因——所以它的严重度是 MEDIUM,并且没有成为第一名假设。

(想确认到底谁在 JNI 临界区里,需要 `-Xcheck:jni` 或 async-profiler;`jia` 只报"日志里说了这件事",
不做超出证据的推断。)

另一条更常见:我们那份健康服务的日志里,也有 `Metadata GC Threshold` 触发的 Full GC(Spring Boot
启动阶段产生一两次是很正常的)。它如果被当成事故报出来,这个工具就废了。判定条件因此是"3 次以上、且至少
一次发生在 JVM 稳定 60 秒之后"。启动噪声不是事故。

## 第 5 步:如果你只有一台机器,先做这三件事

1. **测分母**:GC 停顿时间 ÷ 墙钟时间。低于 97% 才值得继续;是 48% 就说明问题在分配速率,不在堆大小。
2. **看 Full GC 之后的低水位**,而不是之前的峰值。它在涨,是泄漏;它每次都能回到地板,是风暴。
   这一条比任何调参都值钱,因为它区分了两种看起来一样的故障。
3. **查大对象**:G1 下 `G1 Humongous Allocation` 作为 GC cause 大量出现,基本就是"按请求造大数组"。
   改分块/流式,收益通常立竿见影。

## 复现它

```bash
cd demo-victim && ../mvnw -q -DskipTests package
java -Xmx256m -Xms256m -XX:+UseG1GC -jar target/demo-victim.jar
curl 'http://localhost:8080/victim/gcstorm?rounds=40&workingSetMb=96&blocking=true'
scripts/capture.sh -o /tmp/incident -d 6
jia analyze /tmp/incident
```

`jia analyze corpus/incident-gc-storm` 和 `corpus/incident-heap-leak` 会分别把
`H-ALLOCATION-STORM` 和 `H-HEAP-LEAK` 排在第一名,`corpus/healthy` 输出零结论。
这些不是"我跑给你看",是 `CorpusTest` 里的断言:五个场景的第一名假设各一条,加上健康样本零结论一条,
共六条,改坏任何一条 CI 直接红。

> 本文所有结论由确定性规则产生,语言模型只参与把结论写成段落——它没有能力新增、删除或改写任何
> 一条结论(仓库里有一个测试专门锁死这件事)。所以你可以在凌晨三点相信这份报告。
