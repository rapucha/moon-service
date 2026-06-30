# Provider Quota Monitoring

This package tracks aggregate outbound provider usage for operator visibility.
It is intentionally generic: Open-Meteo geocoding, Open-Meteo weather, a future
LLM-backed fictional-location fallback, email delivery, calendar delivery, or
another provider-backed operation should all register as provider operations.

## Runtime Shape

`ProviderQuotaMonitor` is created from `ProviderOperationDefinition` values.
Each definition has:

- a stable operation ID used as the `/admin/status` map key;
- a provider name, such as `open-meteo` or a future LLM provider;
- an operation name, such as `geocoding`, `weather`, or
  `fictional-location-resolution`;
- optional hourly, daily, and monthly limits.

The Spring configuration always registers the current Open-Meteo operations:

```text
open-meteo-geocoding
open-meteo-weather
```

Operators can override their limits with
`moon.provider-quotas.operations.<id>.*`. Additional provider operations can be
configured with the same property family as long as provider and operation names
are supplied.

## Counting Model

The monitor uses in-memory UTC calendar windows:

- hourly windows start at the UTC hour;
- daily windows start at UTC midnight;
- monthly windows start at UTC midnight on the first day of the month.

Each `recordCall()` increments the current hour, day, and month counters for
one provider operation. Window rollover happens lazily on the next increment or
snapshot. This keeps the implementation small and avoids background jobs.

Open-Meteo quota counting happens at the observing transport boundary. That
means a cache hit does not increment quota usage, while a retry that reaches the
upstream provider counts as another provider call.

## Status Output

`/admin/status` exposes `providers.operations.<id>.usage.hourly`, `.daily`, and
`.monthly` snapshots. Each snapshot includes:

- `windowStartedAt`
- `used`
- `limit`
- `knownLimit`
- `percentUsed`
- `warningState`

Unknown limits are explicit: `knownLimit=false`, `limit=null`,
`percentUsed=null`, and `warningState=unknown_limit`. Known limits produce
warning states at 50 percent (`watch`), 80 percent (`warning`), 95 percent
(`critical`), and 100 percent (`exhausted`).

## MVP Limitation

Counters are process-local. They reset on restart and are not shared between
backend instances. That is acceptable for single-process private alpha
visibility, but multi-instance hosting or larger traffic needs durable/shared
counters before this can be treated as a reliable quota guardrail.
