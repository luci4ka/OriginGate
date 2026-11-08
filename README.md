# OriginGate

**Это форк оригинального плагина OriginGate.**  
Оригинал: https://github.com/origingate/OriginGate (лицензия GNU GPLv3).  
Форк: Kaizen Network, luci4ka.  
Изменения — см. `NOTICE`. Лицензия — GNU GPLv3, см. `LICENSE`.

---

Плагин для Velocity, который проверяет, откуда пришло подключение (страна, VPN, хостинг), и применяет правила доступа до того, как игрок попадёт на сервер.

Работает локально: GeoIP-базы скачиваются с GitHub, lookup'ы не требуют API-ключей и не тратят лимиты.

## Возможности

- **Блокировка по стране** — allowlist или denylist, ISO-коды. По умолчанию — allowlist постсоветских стран и стран с официальным русским.
- **Блокировка по хостингу** — DigitalOcean, Hetzner, OVH, AWS, Azure, Contabo, Linode, Vultr, M247 и т.д.
- **Блокировка по VPN** — эвристика по названию ASN (NordVPN, SurfShark, ExpressVPN и т.д.).
- **Блокировка по ASN и организации** — списки номеров AS и подстрок в названии.
- **Блокировка по IP и CIDR** — вручную.
- **Bypass** — по праву, имени/UUID, адресу/CIDR. Плюс per-rule bypass.
- **Dry-run** — логировать и алертить, но не кикать.
- **Лог-файлы** — ежедневные `logs/yyyy-MM-dd.log`, автоочистка.
- **Алерты в staff-чат** — при киках, обходах, проблемах с провайдерами.
- **Команды** — `check`, `status`, `reload`, `cache clear`.
- **Кэш в памяти** — LRU, TTL 1 час, с расширением на IPv6 `/64`.
- **Бюджет lookup'ов** — не больше N в минуту, с `allow`/`deny` при исчерпании.
- **Автообновление GeoIP** — раз в 24 часа с GitHub Releases.

## Требования

- Java 25
- Velocity 3.4.0+
- Интернет при первом запуске (для скачивания GeoIP-баз)

## Установка

1. Скачайте `OriginGate-Velocity-2.0.0.jar` из раздела Releases.
2. Положите в `plugins/` на Velocity.
3. Запустите прокси. Плагин создаст `plugins/OriginGate/` с `config.yml` и `messages.yml`.
4. При первом старте скачаются `GeoLite2-Country.mmdb` и `GeoLite2-ASN.mmdb`.
5. Настройте `config.yml` и выполните `/origingate reload`.

## Быстрый старт

Минимальный `config.yml`, чтобы блокировать VPN и хостинги по ASN-эвристике:

```yaml
lookup:
  country-from:
    - maxmind
  vpn-from:
    - asn
  maxmind:
    file: "GeoLite2-Country.mmdb"
    edition: "GeoLite2-Country"
    auto-update: true

rules:
  hosting:
    enabled: true
  vpn:
    enabled: true
```

Этого достаточно, чтобы отсекать большинство VPN и ботов, которые сидят на хостингах.

По умолчанию `config.yml` идёт с allowlist постсоветских стран и стран с официальным русским:
`RU, BY, UA, KZ, KG, TJ, UZ, TM, AZ, AM, GE, MD, LV, LT, EE`.  
Измените `rules.country.countries` под свои нужды.

## Как это работает

При подключении игрока плагин:

1. Проверяет bypass (право, имя, адрес).
2. Проверяет `deny-addresses` и приватные адреса.
3. Делает lookup через `country-from` (maxmind) и `vpn-from` (asn).
4. Применяет правила: `deny-networks`, `vpn`, `hosting`, `country`.
5. Первое сработавшее правило решает: кик или пропуск (если есть per-rule bypass).

Результат логируется в консоль и/или файл, при кике и обходе отправляется алерт staff'у.

## Конфигурация

### `lookup`

| Ключ | Описание |
|---|---|
| `skip-private-addresses` | Не проверять loopback, приватные, link-local, unique local адреса. |
| `on-lookup-failure` | `allow` или `deny`, если lookup не удался. |
| `wait-millis` | Сколько ждать ответа провайдеров. |
| `max-lookups-per-minute` | Лимит lookup'ов в минуту. 0 — без лимита. |
| `on-budget-exceeded` | `allow` или `deny` при исчерпании лимита. По умолчанию = `on-lookup-failure`. |
| `country-from` | Провайдеры для страны, по порядку. `maxmind`. |
| `vpn-from` | Провайдеры для VPN/хостинга. `asn`. |

### `lookup.maxmind`

| Ключ | Описание |
|---|---|
| `file` | Путь к `.mmdb` относительно папки плагина. |
| `edition` | `GeoLite2-Country` или `GeoLite2-City`. |
| `auto-update` | Скачивать обновления с GitHub раз в 24 часа. |

### `bypass`

| Ключ | Описание |
|---|---|
| `permissions` | Права, дающие полный обход проверки. |
| `players` | Имена или UUID, в нижнем регистре. |
| `addresses` | IP и CIDR-диапазоны. |

### `rules`

Каждое правило имеет `enabled`, `bypass-permissions` и специфичные поля.

- `deny-addresses`: `list` — IP и CIDR.
- `deny-networks`: `asns` (`AS64496`), `organisations` (подстроки в нижнем регистре).
- `vpn`: блокировать VPN.
- `hosting`: блокировать хостинги и дата-центры.
- `country`: `mode` (`allowlist`/`denylist`), `countries` (ISO-коды).

### `alerts`

| Ключ | Описание |
|---|---|
| `permissions` | Права, дающие доступ к staff-алертам. |

## Команды

| Команда | Право | Описание |
|---|---|---|
| `/origingate check <player\|ip> [refresh]` | `origingate.command.check` | Проверить игрока или IP вручную, показать данные и результат правил. |
| `/origingate status` | `origingate.command.status` | Статус: lookup'ы, hit rate, бюджет, состояние провайдеров. |
| `/origingate reload` | `origingate.command.reload` | Перезагрузить конфиг и базы. |
| `/origingate cache clear <ip\|all>` | `origingate.command.cache` | Очистить кэш в памяти. |

## Права

| Право | Описание |
|---|---|
| `origingate.command.check` | Доступ к `/origingate check`. |
| `origingate.command.status` | Доступ к `/origingate status`. |
| `origingate.command.reload` | Доступ к `/origingate reload`. |
| `origingate.command.cache` | Доступ к `/origingate cache clear`. |

Per-rule bypass-права задаются в `rules.<rule>.bypass-permissions` и `bypass.permissions`. По умолчанию пусты — назначьте сами.

## Ограничения

- **VPN-детект — эвристика.** `asn` ловит VPN, у которых в названии организации есть `vpn`/`nordvpn`/`surfshark` и т.п., плюс VPN, арендующие IP у известных хостингов. VPN на чистых residential ASN пройдёт.
- **Прокси не детектится.** Провайдер `asn` не выставляет `proxy=true`, а других провайдеров в сборке нет. Правило `proxy` удалено из конфига.
- **Кэш не переживает рестарт.** После перезапуска прокси все IP проверяются заново. Для локальных `maxmind`/`asn` это не страшно (файлы читаются мгновенно), но lookup'ы в память сбрасываются.
- **Города и регионы** отображаются в логах, но правил по ним нет.

## Сборка из исходников

```bash
git clone https://github.com/yourname/OriginGate.git
cd OriginGate
./gradlew clean shadowJar
```

JAR: `build/libs/OriginGate-Velocity-2.0.0.jar`.

Требуется JDK 25.

## Структура проекта

```
src/main/java/io/github/origingate/
├── core/                    # ядро, не зависит от платформы
│   ├── config/              # загрузка config.yml и messages.yml
│   ├── lookup/              # ProviderChain, LookupService, IpInfo
│   │   ├── asn/             # AsnProvider, AsnUpdater
│   │   └── maxmind/         # MaxMindProvider, MaxMindUpdater
│   ├── net/                 # HTTP-транспорт, Addresses, AddressRange
│   ├── report/              # DecisionFile, DecisionLine
│   ├── rules/               # Gate, Decision, LoginAttempt, Rule
│   ├── storage/             # IpStorage (интерфейс, сейчас NONE)
│   └── util/                # Futures
├── presentation/            # MessagesRenderer (MiniMessage)
└── velocity/                # OriginGateVelocity, OriginGateCommand
```

## Лицензия

GNU General Public License v3.0. См. `LICENSE`.

Это форк. Список изменений относительно оригинала — в `NOTICE`.

## Ссылки

- [Velocity API](https://docs.papermc.io/velocity/)
- [MaxMind GeoLite2](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data)
- [GeoLite.mmdb на GitHub](https://github.com/P3TERX/GeoLite.mmdb)
- [MiniMessage](https://docs.advntr.dev/minimessage/format.html)