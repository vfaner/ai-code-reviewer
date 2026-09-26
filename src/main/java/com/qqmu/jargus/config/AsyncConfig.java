package com.qqmu.jargus.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 *
 * 代码扫描（含编译/单测）属于 CPU/IO 密集任务，默认 SimpleAsyncTaskExecutor
 * 每次新建线程、无上限，多项目同时推送会把机器压垮。这里统一使用有界线程池：
 * 超出并发上限的任务进入队列，队列满后由调用方按拒绝处理。
 */
@Configuration
public class AsyncConfig {

    @Bean("scanTaskExecutor")
    public Executor scanTaskExecutor(
            @Value("${app.scan.max-concurrent:3}") int maxConcurrent,
            @Value("${app.scan.queue-capacity:200}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, maxConcurrent));
        executor.setMaxPoolSize(Math.max(1, maxConcurrent));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("scan-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * AI 调用专用线程池：深度评审逐条增强与扫描期逐文件 AI 评审共用。
     *
     * LLM 调用是 IO 密集且单次数秒到数十秒，混进 scanTaskExecutor（仅 3 槽）
     * 会长时间占用扫描并发位，因此独立成池。并发默认 3，是对厂商限流的保守值，
     * 触发限流（429）可通过 app.ai.review-concurrency 调低；队列打满退化为
     * 调用者自跑（CallerRuns），只降吞吐不丢任务。
     */
    @Bean("aiReviewExecutor")
    public Executor aiReviewExecutor(
            @Value("${app.ai.review-concurrency:3}") int concurrency
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int n = Math.max(1, concurrency);
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ai-review-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
