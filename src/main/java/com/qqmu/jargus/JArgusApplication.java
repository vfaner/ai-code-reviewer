package com.qqmu.jargus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 百目 JArgus 启动类
 *
 * 排除自动数据源配置，使用动态数据源
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableAsync
@EnableScheduling
@EnableCaching
public class JArgusApplication {

    public static void main(String[] args) {
        SpringApplication.run(JArgusApplication.class, args);
    }
}
