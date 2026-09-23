package com.publicissapient.partitioning.service;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs MongoDB's native `.explain("executionStats")` against a query and
 * distills the response into an interview-friendly summary: was this query
 * a single-shard TARGETED operation, or did it trigger a scatter-gather
 * fan-out across multiple shards?
 *
 * Key fields inspected in the raw explain output:
 *   - queryPlanner.winningPlan.shards[]   -> one entry per shard the query touched
 *   - executionStats.executionStages.stage -> SINGLE_SHARD vs SHARD_MERGE
 *   - executionStats.totalDocsExamined / totalKeysExamined -> index efficiency
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryProfilingService {

    private final MongoTemplate mongoTemplate;

    public Map<String, Object> explainOrdersQuery(String customerId, String status) {
        Criteria criteria = new Criteria();
        if (customerId != null) {
            criteria = criteria.and("customerId").is(customerId);
        }
        if (status != null) {
            criteria = (customerId != null)
                    ? criteria.and("status").is(status)
                    : new Criteria().and("status").is(status);
        }

        Query query = Query.query(criteria);
        return runExplain("orders", query.getQueryObject());
    }

    private Map<String, Object> runExplain(String collectionName, Bson filter) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);

        Document explainResult = collection
                .find(filter)
                .explain(ExplainVerbosity.EXECUTION_STATS);

        return summarize(collectionName, filter, explainResult);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarize(String collectionName, Bson filter, Document explain) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("collection", collectionName);
        summary.put("filter", filter.toString());

        // On a sharded cluster, mongos returns a top-level "queryPlanner.winningPlan.shards"
        // array; on a single (unsharded) node it would instead be a flat winningPlan.
        Document queryPlanner = explain.get("queryPlanner", Document.class);
        Document executionStats = explain.get("executionStats", Document.class);

        boolean isShardedResponse = queryPlanner != null
                && queryPlanner.get("winningPlan", Document.class) != null
                && queryPlanner.get("winningPlan", Document.class).containsKey("shards");

        if (isShardedResponse) {
            java.util.List<Document> shards =
                    (java.util.List<Document>) queryPlanner.get("winningPlan", Document.class).get("shards");
            summary.put("shardsTouched", shards.size());
            summary.put("executionType", shards.size() <= 1 ? "TARGETED (single shard)" : "SCATTER_GATHER (multi-shard fan-out)");
            summary.put("shardNames", shards.stream().map(d -> d.getString("shardName")).toList());
        } else {
            summary.put("shardsTouched", 1);
            summary.put("executionType", "TARGETED (single shard / unsharded)");
        }

        if (executionStats != null) {
            summary.put("totalDocsExamined", executionStats.get("totalDocsExamined"));
            summary.put("totalKeysExamined", executionStats.get("totalKeysExamined"));
            summary.put("nReturned", executionStats.get("nReturned"));
            summary.put("executionTimeMillis", executionStats.get("executionTimeMillis"));

            // Simple index-efficiency heuristic for the demo: keys examined
            // should be close to docs returned; a large gap suggests a
            // collection scan or an under-selective index.
            Object keysExamined = executionStats.get("totalKeysExamined");
            Object nReturned = executionStats.get("nReturned");
            if (keysExamined instanceof Number k && nReturned instanceof Number n && n.longValue() > 0) {
                double ratio = k.doubleValue() / n.doubleValue();
                summary.put("indexSelectivityRatio", ratio);
                summary.put("indexEfficiencyVerdict",
                        ratio <= 2.0 ? "GOOD - index is selective" : "POOR - consider a more selective/compound index");
            }
        }

        log.info("Explain summary for collection={}: executionType={}, shardsTouched={}",
                collectionName, summary.get("executionType"), summary.get("shardsTouched"));

        return summary;
    }
}
