package com.publicissapient.partitioning.repository;
 
import com.publicissapient.partitioning.domain.OrderEntity;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
 
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
 
import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
// NOTE: there is no static Aggregation.sum(...) — sum(...) is only called as an
// instance method on GroupOperation, e.g. group("status").sum("orderAmount").
// That bad static import was the actual cause of the "cannot find symbol: sum" error.
 
@Repository
@RequiredArgsConstructor
public class CustomOrderRepositoryImpl implements CustomOrderRepository {
 
    private final MongoTemplate mongoTemplate;
 
    @Override
    public List<Map<String, Object>> aggregateOrderTotalsByStatus() {
        // No shard key in the match stage -> this aggregation is executed by
        // mongos as a merge-sort/scatter across every shard, with each shard
        // performing its own partial group stage before the router merges results.
        Aggregation aggregation = newAggregation(
                group("status")
                        .sum("orderAmount").as("totalAmount")
                        .count().as("orderCount")
        );
 
        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, "orders", Document.class);
 
        return results.getMappedResults().stream()
                .map(doc -> Map.<String, Object>of(
                        "status", doc.get("_id"),
                        "totalAmount", doc.get("totalAmount"),
                        "orderCount", doc.get("orderCount")))
                .toList();
    }
 
    @Override
    public BigDecimal aggregateTotalSpendForCustomer(String customerId) {
        // Match stage includes the shard key (customerId) as its very first
        // criteria -> mongos can push this $match down and target a single
        // shard for the entire pipeline instead of merging partial results.
        Aggregation aggregation = newAggregation(
                match(Criteria.where("customerId").is(customerId)),
                group().sum("orderAmount").as("total")
        );
 
        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, "orders", Document.class);
 
        Document first = results.getUniqueMappedResult();
        if (first == null || first.get("total") == null) {
            return BigDecimal.ZERO;
        }
        Object total = first.get("total");
        return new BigDecimal(total.toString());
    }
 
    @Override
    public List<OrderEntity> findOrdersWithAmountGreaterThan(BigDecimal threshold) {
        // orderAmount is not the shard key on `orders` (customerId is), so
        // this is a genuine cross-partition (scatter-gather) query.
        Query query = Query.query(Criteria.where("orderAmount").gt(threshold));
        return mongoTemplate.find(query, OrderEntity.class);
    }
}
 