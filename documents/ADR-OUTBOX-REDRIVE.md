# ADR: Recovering parked inventory events

**Status:** Accepted

## Context

An inventory event can be parked after its fenced V014 delivery reaches a
terminal state. That row and its event payload are historical evidence. A
parked aggregate version must continue to block later versions until the same
logical event has a confirmed Kafka acknowledgement.

## Decision

Add a forward-only V015 redrive-attempt table. Each operator request creates a
new generation with immutable event, actor, request fingerprint, source
fingerprint, reason and evidence. Its delivery lease, claim generation, retry
schedule, terminal block and broker-ack timestamp are guarded by monotonic
database rules. The original event and V014 delivery row remain unchanged.

Only a current organization administrator can request recovery. Reads and writes
are scoped by organization and store. A request is bound to the parked row's
claim version, attempt count and SHA-256 source fingerprint; request IDs are
idempotent per administrator and organization. The original accepted response is
stored with the request so a retry returns the same result after delivery state
changes. Acceptance and its response are recorded in immutable audit history in
the same transaction.

Before enqueueing or publishing, the application validates the stored event
envelope and matches its movement deltas, position version and reconstructed
position buckets to immutable ledger evidence. The broker key, event ID,
aggregate version, causation ID and encoded logical event remain the original
values. Delivery is at-least-once; the existing inbox makes duplicate delivery
idempotent.

The effective delivery state is published only when the original delivery or a
redrive generation has a persisted broker acknowledgement. Queued, leased,
retrying or blocked generations continue to hold the aggregate ordering gap.
Claims use expiring leases, `SKIP LOCKED` and monotonically increasing fencing
generations so concurrent relays and stale workers cannot advance the marker.

## Migration compatibility

The local Compose verification database records Flyway V010 checksum
`151669554`. The PostgreSQL upgrade test preserves the V001–V014 history,
including that checksum, while applying V015 and restarting. Deployments must
compare their stored checksum before upgrade; a different value needs
compatibility review and must not be hidden with `flyway repair`.

## Unrecoverable source data

If the immutable event cannot be reconstructed and validated against its source
movement, the attempt is recorded as blocked, the original delivery remains
parked, and no Kafka acknowledgement is fabricated. Later aggregate versions
remain blocked. An operator must escalate the source-data integrity issue for
engineering review; the event payload is never edited or replaced to force
progress.

## Consequences

The operator can inspect payload-free, bounded diagnostics and make an audited,
idempotent recovery request. Duplicate Kafka delivery remains possible after a
crash between broker acknowledgement and the database marker; replay uses the
same logical event and is deduplicated by the inbox. A permanently invalid
event can still block its aggregate and requires explicit source-data
investigation.
