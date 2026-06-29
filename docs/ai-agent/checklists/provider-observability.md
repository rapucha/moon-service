# Provider Observability Checklist

Purpose
- Keep external provider integrations operationally visible before they are
  exposed to alpha users.
- Avoid ad hoc counters by requiring every provider operation to have the same
  minimum quota, outcome, latency, and privacy review.

Use this checklist when adding or materially changing an external provider,
provider plan, or provider-backed operation. Examples include geocoding,
weather, ephemeris, map tiles, email delivery, calendar delivery, and a later
LLM-backed fictional-location fallback.

Provider Identity
- [ ] Provider name is explicit and stable in operator output.
- [ ] Operation name is explicit and stable, such as `geocoding`, `weather`,
      `forecast`, `fictional-location-resolution`, or `email-delivery`.
- [ ] If the provider can perform multiple product jobs, each job has its own
      operation ID and quota plan.

Terms, Limits, And Plans
- [ ] Provider terms, attribution, privacy, and data-processing obligations are
      reviewed and documented in the relevant provider doc.
- [ ] Relevant quotas and rate limits are documented, including whether limits
      are per minute, hour, day, month, account, key, IP address, or billing
      plan.
- [ ] Known limits are configured through `moon.provider-quotas.operations.*`
      rather than hard-coded.
- [ ] Unknown limits are intentionally left unknown in `/admin/status`; do not
      invent percentages.
- [ ] Plan upgrades or local operator limits can be represented by configuration
      changes only.

Counters And Status
- [ ] Aggregate outbound provider calls are counted by provider operation.
- [ ] Retries count as provider calls when they hit the upstream provider.
- [ ] Cache hits do not count as provider calls.
- [ ] Success and failure outcome counters exist at the provider seam.
- [ ] Timeout, retry, and rate-limit counters exist where applicable.
- [ ] Latency summary or buckets exist for operator debugging.
- [ ] `/admin/status` exposes operation usage, limits, percent used when known,
      and warning state.
- [ ] At least one test covers provider counters.
- [ ] At least one test covers known quota warning behavior or unknown-limit
      representation.

Privacy And Storage
- [ ] Metrics are aggregate only.
- [ ] Raw user inputs, precise coordinates, query-bearing provider URLs, tokens,
      email addresses, and user-identifying data are not stored in metrics or
      logged by default.
- [ ] If counters remain in memory, the restart and multi-instance limitation is
      documented with the alpha deployment impact.
- [ ] If durable/shared counters are added, retention and deletion behavior are
      documented.

LLM-Backed Fictional Locations
- [ ] The LLM operation is separate from real geocoding and cannot return a real
      geocoding result.
- [ ] The operation has a kill switch before public exposure.
- [ ] The operation has configured cost/quota limits before alpha traffic.
- [ ] Accepted fictional mappings are cached or reviewed so repeated queries do
      not create uncontrolled provider spend.
- [ ] Output is clearly labeled entertainment and avoids raw copyrighted prose,
      character voices, or long franchise-specific text.
