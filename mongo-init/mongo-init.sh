#!/usr/bin/env bash
# =====================================================================================
# mongo-init.sh
#
# One-shot cluster bootstrap for the local sharded MongoDB topology used by the
# nosql-partitioning-sharding-demo project.
#
# Steps performed:
#   1. Initiate the config-server replica set (cfgrs)
#   2. Initiate shard01's replica set (shard01rs)
#   3. Initiate shard02's replica set (shard02rs)
#   4. Through mongos: addShard for both shard replica sets
#   5. Enable sharding on the `partitioning_demo` database
#   6. Shard each collection with its designated shard key strategy:
#        - orders        -> HASHED shard key on customerId (hash-based, avoids hotspots)
#        - orders_compound -> COMPOUND shard key {region:1, customerId:1}
#        - audit_logs    -> RANGE shard key on {createdAt:1} (time-series / range)
#        - tenant_data   -> RANGE shard key on {tenantRegion:1, tenantId:1} (directory/list style)
# =====================================================================================

set -e

echo ">>> [1/6] Waiting for mongod/mongos processes to accept connections..."
until mongosh --host configsvr --port 27019 --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done
until mongosh --host shard01 --port 27018 --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done
until mongosh --host shard02 --port 27020 --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done
until mongosh --host mongos --port 27017 --eval "db.adminCommand('ping')" >/dev/null 2>&1; do sleep 2; done
echo ">>> All nodes reachable."

echo ">>> [2/6] Initiating config server replica set (cfgrs)..."
mongosh --host configsvr --port 27019 --eval '
  try {
    rs.status();
    print("cfgrs already initiated");
  } catch (e) {
    rs.initiate({
      _id: "cfgrs",
      configsvr: true,
      members: [{ _id: 0, host: "configsvr:27019" }]
    });
    print("cfgrs initiated");
  }
'

echo ">>> [3/6] Initiating shard01 replica set (shard01rs)..."
mongosh --host shard01 --port 27018 --eval '
  try {
    rs.status();
    print("shard01rs already initiated");
  } catch (e) {
    rs.initiate({
      _id: "shard01rs",
      members: [{ _id: 0, host: "shard01:27018" }]
    });
    print("shard01rs initiated");
  }
'

echo ">>> [4/6] Initiating shard02 replica set (shard02rs)..."
mongosh --host shard02 --port 27020 --eval '
  try {
    rs.status();
    print("shard02rs already initiated");
  } catch (e) {
    rs.initiate({
      _id: "shard02rs",
      members: [{ _id: 0, host: "shard02:27020" }]
    });
    print("shard02rs initiated");
  }
'

echo ">>> Waiting 10s for replica sets to elect PRIMARY nodes..."
sleep 10

echo ">>> [5/6] Adding shards to the cluster via mongos and enabling sharding on the DB..."
mongosh --host mongos --port 27017 --eval '
  // Register both shard replica sets with the router
  try { sh.addShard("shard01rs/shard01:27018"); } catch (e) { print("shard01 add: " + e); }
  try { sh.addShard("shard02rs/shard02:27020"); } catch (e) { print("shard02 add: " + e); }

  // Enable sharding at the database level
  sh.enableSharding("partitioning_demo");
  print("Sharding enabled on partitioning_demo");
'

echo ">>> [6/6] Creating collections and applying shard keys / indexes..."
mongosh --host mongos --port 27017 partitioning_demo --eval '
  db = db.getSiblingDB("partitioning_demo");

  // ---------------------------------------------------------------------
  // 1) orders : HASH-BASED shard key on customerId
  //    Prevents write hotspots for monotonically-created OrderIds by
  //    hashing the customerId so writes fan out uniformly across shards.
  // ---------------------------------------------------------------------
  db.createCollection("orders");
  db.orders.createIndex({ customerId: "hashed" });
  sh.shardCollection("partitioning_demo.orders", { customerId: "hashed" });

  // ---------------------------------------------------------------------
  // 2) orders_compound : COMPOUND shard key {region:1, customerId:1}
  //    Balances query isolation (region-scoped reads target one shard)
  //    with write distribution (customerId adds cardinality within region).
  // ---------------------------------------------------------------------
  db.createCollection("orders_compound");
  db.orders_compound.createIndex({ region: 1, customerId: 1 });
  sh.shardCollection("partitioning_demo.orders_compound", { region: 1, customerId: 1 });

  // ---------------------------------------------------------------------
  // 3) audit_logs : RANGE shard key on createdAt (time-series style)
  //    Range partitioning groups chronologically adjacent documents,
  //    which is ideal for time-window audit queries but requires
  //    pre-splitting to avoid the "last chunk" hotspot on ingestion.
  // ---------------------------------------------------------------------
  db.createCollection("audit_logs");
  db.audit_logs.createIndex({ createdAt: 1 });
  db.audit_logs.createIndex({ eventType: 1, createdAt: -1 }); // compound secondary index
  sh.shardCollection("partitioning_demo.audit_logs", { createdAt: 1 });

  // ---------------------------------------------------------------------
  // 4) tenant_data : RANGE/LIST shard key on {tenantRegion:1, tenantId:1}
  //    Emulates directory-based / multi-tenant partitioning: all documents
  //    for a given (region, tenant) combination live on the same shard,
  //    giving predictable tenant isolation and single-shard tenant reads.
  // ---------------------------------------------------------------------
  db.createCollection("tenant_data");
  db.tenant_data.createIndex({ tenantRegion: 1, tenantId: 1 });
  db.tenant_data.createIndex({ tenantId: 1, dataKey: 1 }, { unique: false });
  sh.shardCollection("partitioning_demo.tenant_data", { tenantRegion: 1, tenantId: 1 });

  // Multi-key secondary index example (array field) for tenant tags
  db.tenant_data.createIndex({ tags: 1 });

  print("All collections created and sharded successfully.");
  printjson(sh.status());
'

echo ">>> Mongo cluster initialization complete."
