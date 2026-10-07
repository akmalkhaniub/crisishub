package com.ir.audit.store;

import com.ir.audit.config.AuditProperties;
import com.ir.common.event.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only access to the {@code audit_log} table. There is deliberately no update or delete
 * method; in AWS the service role also has an explicit Deny on UpdateItem/DeleteItem.
 */
@Slf4j
@Component
public class AuditRecordStore {

    public static final String PK = "entityId";
    public static final String SK = "timestampId";
    public static final String ACTOR = "actor";
    public static final String ACTOR_INDEX = "actor-index";

    private final DynamoDbClient dynamo;
    private final String table;

    public AuditRecordStore(DynamoDbClient dynamo, AuditProperties props) {
        this.dynamo = dynamo;
        this.table = props.tableName();
    }

    public record AuditRecord(String entityId, String timestampId, String eventId, String entityType, String action,
                              String outcome, String actor, String serviceName, Instant at,
                              Map<String, String> details) {
    }

    public record AuditPage(List<AuditRecord> items, String nextToken) {
    }

    /** @return false when the record already existed (redelivery) — treated as success. */
    public boolean append(AuditEvent e) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(e.entityId()));
        item.put(SK, s(AuditKeys.sortKey(e.at(), e.eventId())));
        item.put("eventId", s(e.eventId()));
        item.put("entityType", s(e.entityType()));
        item.put("action", s(e.action()));
        item.put("outcome", s(e.outcome()));
        item.put(ACTOR, s(e.actor()));
        item.put("serviceName", s(e.serviceName()));
        item.put("at", s(e.at().toString()));
        if (e.details() != null && !e.details().isEmpty()) {
            Map<String, AttributeValue> details = new HashMap<>();
            e.details().forEach((k, v) -> details.put(k, s(v)));
            item.put("details", AttributeValue.fromM(details));
        }
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException duplicate) {
            log.debug("Audit event {} already stored", e.eventId());
            return false;
        }
    }

    public AuditPage byEntity(String entityId, Instant from, Instant to, int limit, String nextToken) {
        return query(null, PK, entityId, from, to, limit, nextToken);
    }

    public AuditPage byActor(String actor, Instant from, Instant to, int limit, String nextToken) {
        return query(ACTOR_INDEX, ACTOR, actor, from, to, limit, nextToken);
    }

    private AuditPage query(String index, String hashAttr, String hashValue, Instant from, Instant to,
                            int limit, String nextToken) {
        QueryRequest.Builder q = QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("#h = :h AND #sk BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#h", hashAttr, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":h", s(hashValue),
                        ":from", s(AuditKeys.lowerBound(from)),
                        ":to", s(AuditKeys.upperBound(to))))
                .scanIndexForward(false) // newest first
                .limit(limit)
                .exclusiveStartKey(PageTokens.decode(nextToken));
        if (index != null) {
            q.indexName(index);
        }
        QueryResponse res = dynamo.query(q.build());
        List<AuditRecord> items = res.items().stream().map(AuditRecordStore::toRecord).toList();
        return new AuditPage(items, PageTokens.encode(res.lastEvaluatedKey()));
    }

    private static AuditRecord toRecord(Map<String, AttributeValue> i) {
        Map<String, String> details = new HashMap<>();
        if (i.containsKey("details")) {
            i.get("details").m().forEach((k, v) -> details.put(k, v.s()));
        }
        return new AuditRecord(str(i, PK), str(i, SK), str(i, "eventId"), str(i, "entityType"), str(i, "action"),
                str(i, "outcome"), str(i, ACTOR), str(i, "serviceName"), Instant.parse(str(i, "at")), details);
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null ? null : v.s();
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v == null ? "" : v);
    }
}
