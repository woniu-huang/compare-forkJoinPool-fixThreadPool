# 关于对比不同调度器（FixedThreadPool，ForkJoinPool）的性能表现的说明

## 任务实现

**任务描述**

编写JMH测试用例，在常见应用场景下（将mysql的同步操作提交到独立线程池，让协程异步等待独立线程池执行完毕 ，可以利用CompletableFuture实现），对比不同调度器（FixedThreadPool，ForkJoinPool）的性能表现。


**代码实现**

```java
package org.example;

import org.apache.commons.dbcp2.BasicDataSource;
import org.openjdk.jmh.annotations.*;

import java.sql.*;
import java.util.concurrent.*;


@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class DatabaseSyncPerformanceComparison {

    private static BasicDataSource dataSource;

    private static Thread.Builder.OfVirtual builder;

    /**
     * 初始化数据库连接池，根据参数选择协程的调度方式：FixedThreadPool、ForkJoinPool
     */
    @Setup(Level.Trial)
    public void setup() {
        if( testOption == 0){
            ThreadFactory factory = Thread.ofPlatform().factory();
            builder = Thread.ofVirtual().scheduler(Executors.newFixedThreadPool(threadCount,factory));
        } else if( testOption == 1){
            builder = Thread.ofVirtual().scheduler(new ForkJoinPool(threadCount));
        }

        dataSource = new BasicDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUsername("root");
        dataSource.setPassword("123456");
        dataSource.setUrl("jdbc:mysql://localhost:3306/hsb");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        try {
            dataSource.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

	@Param({"0", "1"})
    public int testOption;

    @Param({"1000", "5000", "10000"})
    public int requestCount;

    @Param({"100", "500",  "1000"})
    public int threadCount;



    /**
     * 执行mysql同步操作
     */
    public static String execQuery(String sql) throws SQLException {
        String queryResult = "";
        Statement statement = null;
        try (Connection connection = dataSource.getConnection()) {

            statement = connection.createStatement();

            ResultSet rs = statement.executeQuery(sql);

            while (rs.next()) {
                int id = rs.getInt("id");
                String username = rs.getString("username");
                String email = rs.getString("email");
                queryResult = "id: " + id + " username: " + username + " email: " + email + "\n";
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }finally {
            statement.close();
        }
        return queryResult;
    }

    /**
     * 将mysql的同步操作提交到独立线程池中,并异步等待线程池完成任务
     */
    @Benchmark
    public void testDB() throws Exception {
        Thread thread = builder.start(() -> {
            CompletableFuture[] futures = new CompletableFuture[requestCount];
            String sql = "SELECT * FROM users ";
            for (int i = 0; i < requestCount; i++) {
                futures[i] = CompletableFuture.runAsync(()->{
                    try {
                        execQuery(sql);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            try {
                CompletableFuture.allOf(futures).join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.join();
    }
}
```



## **JMH测试结果**

| Benchmark                                | requestCount | testOption | threadCount | Mode | Cnt  | Score      | Error        | Units |
| ---------------------------------------- | ------------ | ---------- | ----------- | ---- | ---- | ---------- | ------------ | ----- |
| DatabaseSyncPerformanceComparison.testDB | 1000         | 0          | 100         | avgt | 5    | 58144.451  | ± 16626.714  | us/op |
| DatabaseSyncPerformanceComparison.testDB | 1000         | 1          | 100         | avgt | 5    | 57890.012  | ± 17924.946  | us/op |
| DatabaseSyncPerformanceComparison.testDB | 5000         | 0          | 500         | avgt | 5    | 283295.595 | ± 79832.856  | us/op |
| DatabaseSyncPerformanceComparison.testDB | 5000         | 1          | 500         | avgt | 5    | 279738.095 | ± 102059.602 | us/op |
| DatabaseSyncPerformanceComparison.testDB | 10000        | 0          | 1000        | avgt | 5    | 580367.689 | ± 163209.967 | us/op |
| DatabaseSyncPerformanceComparison.testDB | 10000        | 1          | 1000        | avgt | 5    | 538699.449 | ± 119536.033 | us/op |

参数说明：

1. **requestCount 和 threadCount**：
   - `requestCount` 表示每次测试运行时提交的请求数量。
   - `threadCount` 表示每次测试运行时使用的线程数量。
2. **testOption**
   - `0` 表示使用 `FixedThreadPool`。
   - `1` 表示使用 `ForkJoinPool`。
3. **Mode**：测试的模式，这里使用了 `avgt`，表示平均执行时间。
4. **Cnt**：测试运行的次数，这里是 5 次。
5. **Score**：测试的分数，表示每个操作的平均执行时间，以微秒（us）为单位。分数越低表示性能越好。
6. **Error**：误差范围，表示测试结果的标准偏差。
7. **Units**：性能指标的单位，这里是微秒（us）。

可以看到两者的性能其实差距不大，总体来说 `ForkJoinPool` 会略好于 `FixedThreadPool`，随着请求数的增加， `ForkJoinPool` 的优势会更加明显。

理论原因分析：

1. **任务分发机制**：`ForkJoinPool` 采用工作窃取（work-stealing）机制，允许空闲线程从其他线程的队列中窃取任务。这有助于均衡任务的负载，确保每个线程都能够充分利用。而 `FixedThreadPool` 则按照提交的顺序依次分配任务给线程，可能会导致某些线程的负载较重，而其他线程相对空闲。
2. **线程数量动态调整**：`ForkJoinPool` 具有自动线程数量调整的能力，可以根据负载动态创建和销毁线程，以适应不同的工作负载。而 `FixedThreadPool` 的线程数量是固定的，可能无法自动适应负载变化。



## 火焰图分析

调度器：FixedThreadPool   requestCount=10000  threadCount =1000

详细文件地址： `demo/fiber/database_sync_performance_comparison/FixedThreadPool_10000_1000.html`

![image-20230915102907926](./assets/image-20230915102907926.png)



调度器：FixedThreadPool   requestCount=10000  threadCount =1000

详细文件地址： `demo/fiber/database_sync_performance_comparison/ForkJoinPool_10000_1000.html`

![image-20230915102927170](./assets/image-20230915102927170.png)