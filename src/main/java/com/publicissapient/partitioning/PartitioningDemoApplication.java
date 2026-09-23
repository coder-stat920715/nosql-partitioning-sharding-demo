package com.publicissapient.partitioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the NoSQL Partitioning & Sharding reference application.
 *
 * This service connects exclusively through a MongoDB `mongos` query router
 * sitting in front of a real sharded cluster (config server replica set +
 * two shard replica sets). It demonstrates hash, range and directory/list
 * based partitioning strategies, compound shard keys, targeted vs
 * scatter-gather query execution, and explain-plan based query profiling.
 */
@SpringBootApplication
@EnableAsync
public class PartitioningDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartitioningDemoApplication.class, args);
    }
}
