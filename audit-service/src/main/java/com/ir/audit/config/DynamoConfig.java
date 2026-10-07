package com.ir.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

@Configuration
public class DynamoConfig {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(AuditProperties props) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(props.region()));
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            // DynamoDB-local accepts any credentials; real AWS uses the default provider chain (IAM role).
            builder.endpointOverride(URI.create(props.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
