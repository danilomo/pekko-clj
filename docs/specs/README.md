# pekko-clj API Parity Specifications

This directory contains specifications for completing pekko-clj's feature parity with the Apache Pekko Scala/Java API.

> **Active work is tracked in [`../ROADMAP.md`](../ROADMAP.md)** — the epic tracker covering
> bug fixes, hardening, and new-module parity, with per-story status. The parity specs here
> remain the detailed per-module references for cluster, routing, sharding, and singleton.

## Overview

| Module | Current Parity | Spec File |
|--------|----------------|-----------|
| Cluster | ~95% | [cluster-parity-spec.md](./cluster-parity-spec.md) |
| Routing | ~95% | [routing-parity-spec.md](./routing-parity-spec.md) |
| Cluster Sharding | ~85% | [sharding-parity-spec.md](./sharding-parity-spec.md) |
| Cluster Singleton | ~95% | [singleton-parity-spec.md](./singleton-parity-spec.md) |

## Priority Implementation Roadmap

### Phase 1: High-Value Additions

| Feature | Module | Effort | Impact |
|---------|--------|--------|--------|
| ~~ConsistentHashingPool/Group~~ | Routing | ~~Medium~~ | ✅ Implemented |
| ~~Cluster-aware routers~~ | Routing | ~~Medium~~ | ✅ Implemented |
| ~~EntityRef API~~ | Sharding | ~~Medium~~ | ✅ Implemented |
| ~~join-seed-nodes~~ | Cluster | ~~Low~~ | ✅ Implemented |

### Phase 2: Medium Priority

| Feature | Module | Effort | Impact |
|---------|--------|--------|--------|
| ~~BalancingPool~~ | Routing | ~~Low~~ | ✅ Implemented |
| ~~Pool Resizers~~ | Routing | ~~Medium~~ | ✅ Implemented |
| ~~Supervision for Singletons~~ | Singleton | ~~Medium~~ | ✅ Implemented |
| ~~cluster-sharding-stats~~ | Sharding | ~~Low~~ | ✅ Implemented |
| ~~passivate-entity~~ | Sharding | ~~Medium~~ | ✅ Implemented |
| Advanced passivation | Sharding | Medium | Memory management |

### Phase 3: Low Priority / Advanced

| Feature | Module | Effort | Impact |
|---------|--------|--------|--------|
| ~~ScatterGatherPool~~ | Routing | ~~Low~~ | ✅ Implemented |
| ~~TailChoppingPool~~ | Routing | ~~Low~~ | ✅ Implemented |
| Multi-DC support | Cluster | High | Geo-distribution |
| External shard allocation | Sharding | High | Kafka co-location |
| Lease integration | Singleton | High | Split-brain safety |

## Implementation Guidelines

### Code Style

Follow existing pekko-clj patterns:
- Functions return Clojure data structures (maps, vectors)
- Options passed as maps with keyword keys
- Async operations return `CompletionStage` or Scala `Future`
- Use `defactor` macro for actor definitions

### Testing

Each new feature should include:
1. Unit tests in corresponding `*_test.clj` file
2. Integration tests if cluster-dependent
3. Docstrings with examples

### Documentation

Update the following when adding features:
1. Function docstrings
2. Namespace docstring
3. README.md examples (if significant feature)

## File Locations

```
src/main/clj/pekko_clj/
├── cluster.clj              # Cluster membership & events
├── routing.clj              # Actor routing (pool/group)
└── cluster/
    ├── sharding.clj         # Cluster sharding
    └── singleton.clj        # Cluster singletons

test/clj/pekko_clj/
├── cluster_test.clj
├── routing_test.clj
└── cluster/
    ├── sharding_test.clj
    └── singleton_test.clj
```

## Dependencies

Current dependencies in `project.clj`:
```clojure
[org.apache.pekko/pekko-actor_3 "1.6.0"]
[org.apache.pekko/pekko-cluster_3 "1.6.0"]
[org.apache.pekko/pekko-cluster-sharding_3 "1.6.0"]
[org.apache.pekko/pekko-cluster-sharding-typed_3 "1.6.0"]  ;; ShardedDaemonProcess only
[org.apache.pekko/pekko-cluster-tools_3 "1.6.0"]
[org.apache.pekko/pekko-distributed-data_3 "1.6.0"]  ;; CRDTs (pekko-clj.cluster.ddata)
[org.apache.pekko/pekko-stream_3 "1.6.0"]
[org.apache.pekko/pekko-persistence_3 "1.6.0"]
[org.apache.pekko/pekko-persistence-query_3 "1.6.0"]
[org.apache.pekko/pekko-http_3 "1.4.0"]
[org.apache.pekko/pekko-testkit_3 "1.6.0"]
[org.apache.pekko/pekko-stream-testkit_3 "1.6.0"]
[com.cognitect/transit-clj "1.0.333"]  ;; Transit serializer (pekko-clj.serialization)
[cheshire "5.13.0"]                    ;; JSON marshalling (pekko-clj.http)
```

For cluster-aware routing, no additional dependencies needed (included in pekko-cluster).

## References

- [Apache Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- [Pekko API Reference](https://pekko.apache.org/api/pekko/current/)
- [Pekko HTTP Documentation](https://pekko.apache.org/docs/pekko-http/current/)
