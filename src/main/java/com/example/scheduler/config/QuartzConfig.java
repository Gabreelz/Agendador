package com.example.scheduler.config;

import com.example.scheduler.quartz.AutowiringSpringBeanJobFactory;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

    @Bean
    SchedulerFactoryBeanCustomizer springJobFactoryCustomizer(ApplicationContext context) {
        return factory -> factory.setJobFactory(
                new AutowiringSpringBeanJobFactory(context.getAutowireCapableBeanFactory()));
    }
}