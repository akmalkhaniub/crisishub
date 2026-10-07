package com.ir.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint    DynamoDB endpoint override (DynamoDB-local / LocalStack); blank → real AWS
 * @param createTable create the table + GSI at startup if missing (local dev only; in AWS the
 *                    table comes from CloudFormation and this service has no CreateTable permission)
 */
@ConfigurationProperties("ir.audit")
public record AuditProperties(String tableName, String endpoint, String region, boolean createTable) {
}
