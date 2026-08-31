# Mercury Exchange

[![CI](https://github.com/shuai-zz/Mercury-Exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/shuai-zz/Mercury-Exchange/actions/workflows/ci.yml)

A simple, super fast, 7x24 in-memory exchange built from scratch.

Mercury — the Roman god of commerce and speed — is exactly what this project aims for:
a trading exchange with 100% in-memory matching and event-sourced architecture.

## Technology

- Java 17 + Spring Boot 3.x + Spring 6.x + Spring Cloud 2022.x + Maven
- Kafka for event streaming, Redis for caching, MySQL for persistence
- Vert.x based WebSocket push
- 100% in-memory order matching

## Architecture

```
        ┌─────────────┐
        │     UI      │
        └──────┬──────┘
               │ REST
        ┌──────▼──────┐     ┌───────────────┐     ┌────────────────┐
        │ trading-api ├────►│   sequencer   ├────►│ trading-engine │
        └─────────────┘     └───────────────┘     └───────┬────────┘
                 │ Kafka            Kafka                 │ Kafka
               ┌─▼─────────────┬───────────────┬─────────▼───────┐
               │  quotation    │     push      │  MySQL / Redis  │
               └───────────────┴───────────────┴─────────────────┘
```

All trading events are sequenced first, then processed by the matching engine,
which makes the whole system replayable and recoverable.

## Modules

| Module             | Description                                   |
| ------------------ | --------------------------------------------- |
| `parent`           | Maven parent POM                              |
| `build`            | docker-compose (Kafka / Redis / MySQL) + DDL  |
| `config`           | Spring Cloud Config server                    |
| `config-repo`      | Externalized configuration files              |
| `common`           | Shared code                                   |
| `trading-api`      | User-facing REST API (order / cancel / query) |
| `trading-sequencer`| Global event sequencer                        |
| `trading-engine`   | In-memory order book & matching engine        |
| `quotation`        | Market data (tickers, bars, order book)       |
| `push`             | WebSocket push service (Vert.x)               |
| `ui`               | Web UI                                        |

## Quick Start

```bash
# start infrastructure: Kafka, Redis, MySQL
cd build
docker-compose up -d
```

Then start the services in order: `config` → `trading-api` / `trading-sequencer` / `trading-engine` / `quotation` / `push` → `ui`.

## Roadmap

- [x] **v0.1** — Project skeleton, infrastructure and database schema
- [ ] **v0.2** — Common module & asset service
- [ ] **v0.3** — Trading API
- [ ] **v0.4** — Event sequencing & event sourcing
- [ ] **v0.5** — Matching engine
- [ ] **v0.6** — Clearing & settlement
- [ ] **v0.7** — Quotation service & WebSocket push
- [ ] **v0.8** — Web UI
- [ ] **v1.0** — Crash recovery, integration and 7x24 operation (MVP)
- [ ] **v2.0** — Enhancements: trading fees, market orders, open API, multi-symbol, risk control, benchmark

## Credits

Inspired by the tutorial
[从零开始搭建一个7x24小时运行的证券交易所](https://liaoxuefeng.com/books/java/springcloud/)
and the original [warpexchange](https://github.com/michaelliao/warpexchange) project.
