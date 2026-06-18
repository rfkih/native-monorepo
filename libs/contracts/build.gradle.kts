// libs/contracts — the SINGLE source of truth for event Avro schemas (.avsc).
//
// Every domain event's schema lives here exactly ONCE, under src/main/resources/avro/, on the
// classpath at `avro/<EventName>.avsc` — the same path the per-feature `*Schema` loaders already
// read via getResourceAsStream. Producers AND consumers depend on this module, so a producer and
// its consumers can no longer drift apart (rule 7: event schema changes are backward-compatible
// only — now enforceable against one file, not N copy-pasted ones).
//
// History: each service used to keep its OWN copy of every schema it produced or consumed; all 18
// events were duplicated across 2–4 services and every duplicate had already drifted (doc text
// only — verified wire-identical at consolidation; see docs/adr/0003). This module removes that
// duplication. See docs/EVENT-CATALOG.md (the prose catalog) and docs/adr/0003.
//
// Resources-only: no Java, no Avro dependency needed to COMPILE (nothing here parses the schemas —
// libs/events AvroSerde does that at runtime in each service). The plain java-conventions plugin is
// enough to bundle the resources into a jar that lands on each consumer's classpath.
plugins {
    id("native.java-conventions")
}
