package com.ir.audit.store;

import com.ir.audit.config.AuditProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import static com.ir.audit.store.AuditRecordStore.ACTOR;
import static com.ir.audit.store.AuditRecordStore.ACTOR_INDEX;
import static com.ir.audit.store.AuditRecordStore.PK;
import static com.ir.audit.store.AuditRecordStore.SK;

/** Local-dev convenience; production provisions the same schema via CloudFormation. */
@Slf4j
@Component
@ConditionalOnProperty(name = "ir.audit.create-table", havingValue = "true")
public class AuditTableInitializer implements ApplicationRunner {

    private final DynamoDbClient dynamo;
    private final AuditProperties props;

    public AuditTableInitializer(DynamoDbClient dynamo, AuditProperties props) {
        this.dynamo = dynamo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            dynamo.createTable(b -> b
                    .tableName(props.tableName())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            attr(PK), attr(SK), attr(ACTOR))
                    .keySchema(key(PK, KeyType.HASH), key(SK, KeyType.RANGE))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName(ACTOR_INDEX)
                            .keySchema(key(ACTOR, KeyType.HASH), key(SK, KeyType.RANGE))
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build()));
            dynamo.waiter().waitUntilTableExists(b -> b.tableName(props.tableName()));
            log.info("Created DynamoDB table {}", props.tableName());
        } catch (ResourceInUseException exists) {
            log.info("DynamoDB table {} already exists", props.tableName());
        }
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
