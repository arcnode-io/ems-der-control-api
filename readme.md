# ems-der-control-api ⚡📥

![](https://img.shields.io/gitlab/pipeline-status/arcnode-io/ems-der-control-api?branch=main&logo=gitlab)
![](https://gitlab.com/arcnode-io/ems-der-control-api/badges/main/coverage.svg)
![](https://img.shields.io/badge/21-gray?logo=openjdk)
![](https://img.shields.io/badge/4.1.1-gray?logo=springboot)
![](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven)
![](https://img.shields.io/badge/ORM-hibernate%2Fjpa-59666C)

The utility/aggregator-facing intake for grid-flexible dispatch. Receives **IEEE 2030.5 (SEP2)
`DERControl`** events over mutual-TLS HTTPS (terminated by the `der-control-ingress` gateway),
persists them, and republishes the `DERControlBase` setpoints onto the arcnode MQTT bus as
measurement samples on a `der_dispatch` singleton device — the same two-family topic contract
every other EMS telemetry feed uses.

The IP-native twin of the DNP3 path: `dlr-rtu-firmware` → `ems-industrial-gateway` →
`operating_envelope` already carries utility DOE limits over DNP3; this service carries the
2030.5/OpenADR half the arcnode site page lists as "Curtailment commands (DNP3/OpenADR)".

Instance of `~/engineering-with-ai/java-spring-jpa`.

## API

| verb | path | purpose |
|---|---|---|
| `POST` | `/der-events` | ingest a 2030.5 `DERControl` (mRID, EventStatus, interval, DERControlBase) |
| `GET` | `/der-events/{mRID}` | fetch one persisted event |
| `GET` | `/der-events?status=Active` | list by status |

`POST /der-events` requires `X-SSL-Client-Cert` (set by `der-control-ingress`, the nginx gateway
that terminates mTLS — see `platform-api`). Device identity (LFDI/SFDI) is derived from the
forwarded cert and persisted on each event for audit.

Each ingested event publishes to
`sites/{siteId}/devices/der_dispatch/measurements/{target_active_power|event_active|energize_enabled}/{unit}`.

## Diagrams

### Ingest Flow

```plantuml
participant utility
participant der_control_ingress
participant der_control_api
database dercontrol_db
participant broker

utility -> der_control_ingress: POST /der-events (DERControl, mTLS client cert)
der_control_ingress -> der_control_ingress: terminate TLS, verify client cert against truststore
der_control_ingress -> der_control_api: forward plain HTTP + X-SSL-Client-Cert header
der_control_api -> der_control_api: derive LFDI/SFDI from forwarded cert
der_control_api -> dercontrol_db: upsert by mRID (+ submittedByLfdi)
der_control_api -> broker: pub measurements/der_dispatch/*\n(target_active_power, event_active, energize_enabled)
der_control_api -> utility: 201 created
```

### Consumers

```plantuml
participant der_control_api
participant broker
database timeseries
participant ems_hmi

der_control_api -> broker: pub sites/{site}/devices/der_dispatch/measurements/*
broker -> timeseries: telemetry_writer persists (wildcard subscriber)
broker -> ems_hmi: Grid Events / DER Control panel (subscribed via AsyncAPI-generated topics)

note right of ems_hmi
  der_dispatch channels only exist in the generated
  spec once a DTM actually has a der_dispatch device
  (edp-api template + ems-device-api generator — both
  landed; no site has onboarded the device yet).
end note
```

## Layout

```
src/main/java/io/arcnode/dercontrol/
  Application.java          entry point + OpenAPI bean + Clock bean
  AppController.java        GET / health check
  Config.java               cfg.yml loader + validated Config record (nested LogLevel, Loader)
  DataSourceUrl.java        DER_CONTROL_URL (libpq) -> spring.datasource.* (nested Loader)
  ClientIdentity.java       X-SSL-Client-Cert -> LFDI/SFDI (IEEE 2030.5 §6.3.4)
  derevent/                 ingest resource — controller, service, JpaRepository, entity, dto/ records
  dispatch/                 DispatchPublisher (event -> MQTT sample(s)) + MqttConfig (Paho v5 client)
src/test/java/io/arcnode/dercontrol/
  <feature>/<Feature>ServiceTest.java   unit — Mockito, AAA (Surefire)
tests/java/io/arcnode/dercontrol/
  <Feature>ResourceIT.java              integration — @SpringBootTest + Testcontainers (Failsafe)
  DispatchPublishIT.java                POST -> real HiveMQ broker -> assert the sample
  AbstractBrokerIT.java                 shared HiveMQ singleton container for every *IT
```

## `make` verbs

| verb | runs |
|---|---|
| `dev` | `spring-boot:run` (devtools hot-reload) |
| `checks` | depcheck + format + lint + typecheck + security (SpotBugs/Semgrep + OWASP) |
| `unit` | `test` (`*Test`, Surefire) |
| `integration` | `failsafe:integration-test` (`*IT`/`*PublishIT`, Failsafe — needs Docker) |
| `cover` | unit tests w/ JaCoCo agent + report + 70% line gate |
| `commit` | `checks` + `test` + `review` + `git add -A && git cz && git push` |

`./mvnw verify` (format → compile → Surefire → SpotBugs → PMD → Failsafe → JaCoCo gate) still
works directly — see `CLAUDE.md` for the full verb table and why CI runs `make checks` + one
`mvnw clean verify` call rather than the verbs split into separate steps.

## Config & secrets

- Non-secrets: `cfg.yml`, `local` / `beta` blocks, selected by `$ENV` (default `local`).
- Secrets: environment only. Names tracked in `template-secrets.env`
  (`POSTGRES_PASSWORD`, `MQTT_DER_CONTROL_API_PASSWORD`, `DER_CONTROL_URL`, optional `NVD_API_KEY`).
- OpenAPI: `/swagger-ui`, `/v3/api-docs`.

## Toolchain

Build runs on JDK 21 via `maven-toolchains-plugin`; point it at a JDK 21 with `~/.m2/toolchains.xml`.
CI generates its own from `$JAVA_HOME`; the Dockerfile writes one against the temurin base image.

## Status

Rebuilt from scratch on `java-spring-jpa` (2026-09-10); see `CLAUDE.md` for the tooling/testing
conventions that came with that. Auth landed in this rebuild — mTLS termination + LFDI/SFDI audit
trail, via platform-engineer's `der-control-ingress` gateway.

Still genuinely open (not historical):

- **Per-mRID authorization** — nothing yet rejects an LFDI that isn't allowlisted for a given
  mRID/site. Needs an allowlist source that doesn't exist yet.
- **`ems-hmi` panel** — built against mock data (🖥️ frontend-engineer). Real wiring needs a site
  DTM to actually onboard a `der_dispatch` device — none has yet.
- **Broker RBAC merge** — last known status: the `arcnode_der_control_api` File-RBAC identity
  existed only on a `platform-api` feature branch, not `main`. Re-verify before relying on it.

Open design questions, none resolved yet:

- No EMS supervisory-control loop exists to *act* on the published setpoints — they're persisted
  and shown on the HMI, but nothing downstream reacts. Acceptable for MVP?
- `opModImpLimW` / `opModExpLimW` (import/export limit) are in the 2030.5 spec but were never
  wired up — `operating_envelope` already owns that quantity via DNP3. Confirm this service
  should stay scoped to `target_active_power` / `event_active` / `energize_enabled` only.
- No event-window scheduler — an event's `start`/`duration` is persisted but nothing flips
  `event_active` back to `false` at window close (or on broker restart, since measurements
  retain). Publishes only happen on receipt.
