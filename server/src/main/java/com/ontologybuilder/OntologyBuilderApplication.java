package com.ontologybuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OntoMind 智能本体平台启动类
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@EnableScheduling
public class OntologyBuilderApplication {
    private static final Logger log = LoggerFactory.getLogger(OntologyBuilderApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OntologyBuilderApplication.class, args);
    }

    @Bean
    public CommandLineRunner fixBusinessRuleTypeCheck(DataSource dataSource) {
        return args -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // 检查约束是否存在且不包含 SWRL（使用 pg_get_constraintdef 兼容 PG12+）
                String check = "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint WHERE conname = 'tm_business_rule_type_check' AND pg_get_constraintdef(oid) NOT LIKE '%SWRL%'";
                var rs = stmt.executeQuery(check);
                if (rs.next()) {
                    log.info("检测到 tm_business_rule_type_check 约束缺少 SWRL 类型，正在修复...");
                    stmt.execute("ALTER TABLE tm_business_rule DROP CONSTRAINT IF EXISTS tm_business_rule_type_check");
                    stmt.execute("ALTER TABLE tm_business_rule ADD CONSTRAINT tm_business_rule_type_check CHECK (type IN ('CONDITION', 'EVENT', 'TEMPORAL', 'COMPOSITE', 'SWRL'))");
                    log.info("tm_business_rule_type_check 约束修复完成，已添加 SWRL 类型支持");
                }
                rs.close();
            } catch (Exception e) {
                log.warn("修复 tm_business_rule_type_check 约束时出错（不影响启动）: {}", e.getMessage());
            }
        };
    }
}
