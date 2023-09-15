package org.example;

import org.apache.commons.dbcp2.BasicDataSource;
import org.openjdk.jmh.annotations.*;

import java.sql.*;
import java.util.concurrent.*;


@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class DatabaseSyncPerformanceComparison {

    private static BasicDataSource dataSource;

    private static Thread.Builder.OfVirtual builder;


    /**
     * 初始化数据库连接池，根据参数选择协程的调度方式：FixedThreadPool、ForkJoinPool
     */
    @Setup(Level.Invocation)
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
        dataSource.setUrl("jdbc:mysql://localhost:3306/testdb");
        dataSource.setPassword("q19723011");
//        dataSource.setPassword("123456");
//        dataSource.setUrl("jdbc:mysql://localhost:3306/hsb");
    }

    @TearDown(Level.Invocation)
    public void teardown() {
        try {
            dataSource.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

//    @Param({"0", "1"})
//    public int testOption;
//
//    @Param({"1000", "5000", "10000"})
//    public int requestCount;
//
//    @Param({"100", "500",  "1000"})
//    public int threadCount;

    @Param({"0","1"})
    public int testOption;

    @Param({"100"})
    public int requestCount;

    @Param({"1000","100","10"})
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

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}