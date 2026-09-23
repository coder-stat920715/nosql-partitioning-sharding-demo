# NoSQL Partitioning & Sharding Demo — Spring Boot 3.x + MongoDB Sharded Cluster

A hands-on reference project built for a **Senior Java / Microservices interview at Publicis Sapient**,
covering NoSQL partitioning, database sharding, shard-key design, and indexing strategy — backed by a
**real local MongoDB sharded cluster** (not a mocked one).

---

## 1. Architecture

```
                         ┌─────────────────────┐
                         │   Spring Boot App    │
                         │  (talks ONLY to      │
                         │   mongos, port 27017)│
                         └──────────┬───────────┘
                                    │
                              ┌─────▼──────┐
                              │   mongos   │  (query router, stateless)
                              └─────┬──────┘
                     ┌──────────────┼──────────────┐
                     │              │              │
              ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼──────┐
              │  configsvr │ │  shard01   │ │  shard02    │
              │  (cfgrs)   │ │ (shard01rs)│ │ (shard02rs) │
              │ metadata + │ │  data      │ │  data       │
              │ chunk map  │ │            │ │             │
              └────────────┘ └────────────┘ └─────────────┘
```

The app **never** connects directly to a shard. All reads/writes go through `mongos`, which consults the
config server's chunk map to decide whether a query is *targeted* (single shard) or must be
*scatter-gathered* (broadcast + merge across shards).

## 2. Collections & Partitioning Strategy

| Collection          | Shard Key                          | Strategy            | Why                                                        |
|----------------------|-------------------------------------|----------------------|--------------------------------------------------------------|
| `orders`             | `{ customerId: "hashed" }`         | Hash-based           | Uniform write distribution; avoids hotspots on opaque IDs   |
| `orders_compound`    | `{ region: 1, customerId: 1 }`     | Compound / Range     | Region-scoped query isolation + intra-region write spread   |
| `audit_logs`         | `{ createdAt: 1 }`                 | Range (time-series)  | Chronological locality for time-window audit queries        |
| `tenant_data`        | `{ tenantRegion: 1, tenantId: 1 }` | Range / Directory    | Native Mongo sharding by tenant + app-level physical routing |

`tenant_data` also demonstrates **application-level directory partitioning**: `DynamicPartitionRouter`
resolves a distinct *physical collection* (`tenant_data_us`, `tenant_data_eu`, `tenant_data_apac`) per
region on top of the database-level shard key — the pattern used when data-residency rules require
physically separate storage, not just logical shard placement.

## 3. Running Locally

```bash
docker-compose up --build
```

This will:
1. Start `configsvr`, `shard01`, `shard02`, and `mongos`.
2. Run `mongo-init/mongo-init.sh`, which initiates each replica set, registers both shards with the
   router, enables sharding on `partitioning_demo`, and shards every collection with its key.
3. Build and start the Spring Boot app on `http://localhost:8080`.

Swagger UI: `http://localhost:8080/swagger-ui.html`
Health: `http://localhost:8080/actuator/health`

### Key Endpoints

| Method | Path                                                | Behaviour                                    |
|--------|------------------------------------------------------|-----------------------------------------------|
| POST   | `/api/v1/partitioning/orders`                        | Targeted write (hashed shard key)             |
| GET    | `/api/v1/partitioning/orders/targeted?customerId=`    | Targeted read — single shard                  |
| GET    | `/api/v1/partitioning/orders/scatter-gather`          | Cross-shard parallel scan (`CompletableFuture`)|
| GET    | `/api/v1/partitioning/orders/explain?customerId=&status=` | Runs `.explain("executionStats")`        |
| POST   | `/api/v1/partitioning/tenants/route`                  | Dynamic tenant/region collection router       |
| GET    | `/api/v1/partitioning/audit-logs/window?from=&to=`    | Targeted range scan (time window)             |
| GET    | `/api/v1/partitioning/audit-logs/by-event-type`       | Scatter-gather (no shard key)                 |

---

## 4. Interview Question Bank

### Q1. How do you select an optimal shard key? Why avoid monotonically increasing keys (auto-increment IDs, timestamps) as a *sole* shard key?

A good shard key needs three properties:

- **High cardinality** — enough distinct values that chunks can be split finely. A boolean or
  low-cardinality enum makes a poor sole shard key because MongoDB can never split a chunk of identical
  key values ("jumbo chunks").
- **Low frequency (even distribution)** — no single value should dominate the dataset, or that value's
  chunk becomes a hotspot.
- **Non-monotonic write pattern** — this is the classic trap. If the shard key is an auto-increment ID or
  a timestamp, every new document has a *higher* key than every existing document. Because chunk ranges
  are ordered, **100% of inserts land in the single chunk that currently owns the top of the range** —
  pinning all write traffic onto one shard regardless of cluster size, and forcing the balancer into a
  constant game of catch-up moving freshly-written chunks off that shard.

Mitigations demonstrated in this project:
- `orders` hashes `customerId` specifically so a natively high-cardinality-but-otherwise-fine field
  doesn't cause range clustering; hashing scrambles insertion order.
- `audit_logs` intentionally uses a monotonic key (`createdAt`) **because** the query pattern demands
  range locality — the trade-off is called out in `AuditLogEntity`'s Javadoc, and in production this
  collection would be **pre-split** (`sh.splitAt`) ahead of the write curve, or use **zoned sharding** to
  route today's time-bucket to a dedicated shard.
- `orders_compound` and `tenant_data` use compound keys specifically to combine a low-cardinality,
  query-friendly prefix (`region` / `tenantRegion`) with a high-cardinality suffix (`customerId` /
  `tenantId`) — getting isolation and distribution simultaneously.

### Q2. Local indexes vs. Global secondary indexes in NoSQL — what's the difference?

- **Local (shard-local) secondary index**: the index is built and maintained independently *on each
  shard*, covering only the documents that physically live there (this is what MongoDB does — every
  secondary index you create is local to its shard). A query using a local index but **not** the shard
  key must still be broadcast to all shards, each of which uses its own local index to answer its
  portion, and mongos merges the results. Writes stay fast (single-shard index update), but non-shard-key
  reads pay the fan-out cost.
- **Global secondary index**: a separate index structure (often itself a distinct sharded collection)
  that maps an alternate key to the primary partition location, letting a query on that alternate
  attribute route to the right shard(s) directly without a full scatter. DynamoDB Global Secondary
  Indexes are the textbook example. The cost is **write amplification and eventual consistency** — every
  write to the base table must also (usually asynchronously) update the global index, and the index may
  briefly lag the source of truth.
- **In this project**: every `@Indexed`/`@CompoundIndex` annotation creates a MongoDB *local* index.
  `QueryProfilingService.explainOrdersQuery` demonstrates the practical consequence — a query on
  `customerId` (shard key) is single-shard; a query on `status` alone still uses the local `status` index
  on *each* shard but requires visiting all of them.

### Q3. CAP theorem & eventual consistency trade-offs in partitioned systems — and PACELC

CAP says that under a **network partition (P)**, a distributed system must choose between
**Consistency (C)** and **Availability (A)** — it cannot guarantee both.

MongoDB's replica sets default to a **CP-leaning** posture: writes go to a primary and, depending on the
configured **write concern** (`w: majority` vs `w: 1`) and **read concern/read preference**
(`readConcern: majority`, reading from primary vs. secondaries), you can dial consistency vs. latency.
A `w: majority` write with `readConcern: majority` gives you strong, linearizable-ish guarantees at the
cost of latency; `w: 1` with reads from secondaries favors availability/latency but risks reading stale
or (during a failover) since-reverted data.

**PACELC** extends CAP to the common case — normal operation, no partition — and says: even then (**E**lse),
you must choose between **L**atency and **C**onsistency. A sharded MongoDB cluster is a good PACELC
example:
- **During a partition (P)**: choose **A**vailability (allow reads/writes to continue against a minority
  side, risking staleness) or **C**onsistency (refuse operations that can't reach a majority).
- **Else, normally (E)**: choose **L**atency (read from nearest secondary, accept some replication lag) or
  **C**onsistency (always read from / write to primary with majority concern, accept the extra round trip).

Interview framing: *"MongoDB is PC/EC by default (majority write/read concern favors consistency in both
regimes), but it's tunable toward PA/EL per-operation via write/read concern and read preference —
which is exactly the kind of trade-off you'd tune differently for an audit-log write (favor consistency)
versus a product-catalog read (favor latency)."*

### Q4. How do chunk splitting, balancing, and resharding work?

- **Chunk splitting**: MongoDB (historically `mongos`, now the shard primaries themselves as of newer
  versions) monitors chunk size. When a chunk exceeds the configured threshold (default 128MB, tunable),
  it is split at a calculated midpoint into two chunks, *without moving any data* — a split is purely a
  metadata operation in the config server's chunk map.
- **Balancing**: a background process (the *balancer*, running as part of the config server's primary)
  periodically compares the number of chunks owned by each shard. If the difference exceeds a migration
  threshold, it schedules **chunk migrations**: the donor shard streams the chunk's documents to the
  recipient shard, the config server atomically updates the chunk's ownership once the copy is
  consistent, and the donor deletes its local copy. This is why an ill-chosen monotonic shard key hurts:
  the balancer is constantly migrating freshly written chunks off the "hot" shard just to keep count
  balanced, generating unnecessary I/O and replication traffic.
- **Resharding** (MongoDB 5.0+ `reshardCollection`): allows changing a collection's shard key *in place*
  without full application downtime. Internally it clones the collection under the new shard key into a
  temporary collection, uses a change-stream-based catch-up phase to replay ongoing writes, and performs
  an atomic cutover once the temp collection is caught up. Before this feature, a shard-key mistake
  required a full manual migration (export/re-import into a new, correctly-sharded collection) — a very
  expensive operation, which is why the up-front shard-key design questions above matter so much.

### Q5. Cross-partition (scatter-gather) vs. single-shard targeted queries — performance impact

| Aspect                | Targeted (shard key present)              | Scatter-Gather (shard key absent)                     |
|------------------------|--------------------------------------------|----------------------------------------------------------|
| Routing cost           | O(1) — mongos hashes/ranges directly to 1 shard | O(N) — broadcast to all N shards                     |
| Network fan-out        | Single shard round-trip                    | N parallel round-trips + a merge-sort/merge stage on mongos |
| Tail latency           | Bounded by the slowest response of 1 shard | Bounded by the **slowest of N shards** (tail-latency amplification) |
| Scalability             | Improves as you add shards (more parallel targeted capacity) | Gets **worse** as you add shards (more nodes to fan out to, same merge cost) |
| Resource cost           | Minimal — one shard does the work          | Every shard burns CPU/IO even if it holds zero matching docs |

This project's `QueryProfilingService.explainOrdersQuery` makes this concrete: it inspects
`queryPlanner.winningPlan.shards[]` from the real explain output and reports `shardsTouched` and an
`executionType` of `TARGETED` vs `SCATTER_GATHER`, plus `totalKeysExamined` / `totalDocsExamined` /
`executionTimeMillis` from `executionStats` — the same numbers you'd pull up live in an interview to
prove the difference, not just assert it.

**Practical guidance to give in an interview**: design shard keys around your **dominant query pattern**
first, not your write pattern in isolation — a key that distributes writes perfectly but forces every
read into scatter-gather is usually the wrong trade for a read-heavy service, and vice versa for a
write-heavy ingestion service. Compound keys (as used for `orders_compound` and `tenant_data` here) are
frequently the pragmatic middle ground: a coarse, query-friendly prefix plus a fine-grained,
write-friendly suffix.

---

## 5. Project Layout

```
src/main/java/com/publicissapient/partitioning/
├── PartitioningDemoApplication.java
├── config/AsyncConfig.java                 # scatter-gather thread pool
├── domain/                                 # sharded entities (@Sharded annotations)
│   ├── OrderEntity.java                    # hashed shard key
│   ├── OrderCompoundEntity.java            # compound shard key
│   ├── AuditLogEntity.java                 # range shard key
│   └── MultiTenantDataEntity.java          # directory/list shard key
├── repository/
│   ├── OrderRepository.java / CustomOrderRepository(+Impl)
│   ├── OrderCompoundRepository.java
│   ├── AuditLogRepository.java
│   └── MultiTenantDataRepository.java
├── router/
│   ├── RoutingContext.java                 # request-scoped routing key
│   └── DynamicPartitionRouter.java         # multi-collection resolver
├── service/
│   ├── PartitionedOrderService.java        # targeted / scatter-gather / aggregate
│   ├── QueryProfilingService.java          # .explain("executionStats") wrapper
│   ├── AuditLogService.java
│   └── TenantRoutingService.java
└── controller/                             # /api/v1/partitioning/*
mongo-init/mongo-init.sh                    # cluster bootstrap script
docker-compose.yml                          # configsvr + shard01 + shard02 + mongos + app
```
