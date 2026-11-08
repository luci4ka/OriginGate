OriginGate (modified fork)
==========================

This project is a modified version of the original OriginGate plugin.

Original work:
  Name:    OriginGate
  License: GNU General Public License v3.0
  Source:  https://github.com/origingate/OriginGate (original repository)

This fork is distributed under the same license, GNU General Public License v3.0.
See the LICENSE file for the full text.

Copyright (C) 2026 Kaizen Network
Copyright (C) 2026 luci4ka (fork modifications)
Modifications made in 2026.

Summary of changes
------------------

The following changes were made to the original codebase. The list is
non-exhaustive; see the commit history for details.

Project structure
  - Merged the multi-module Gradle project (core, presentation,
    platform-velocity, platform-paper, platform-bungeecord,
    maxmind-modern, maxmind-legacy) into a single Gradle module.
  - Migrated all build scripts from Kotlin DSL (build.gradle.kts)
    to Groovy DSL (build.gradle).
  - Removed the Paper and BungeeCord platform modules. Only Velocity
    is supported.

Java and toolchain
  - Raised the Java target from Java 8 to Java 25.
  - Removed the maxmind-legacy module and all reflection-based
    compatibility code in MaxMindDatabase. The MaxMind reader is now
    loaded directly from com.maxmind.db.
  - Removed the Compat utility class and replaced every call with
    the standard Java API (List.of, Set.of, Map.of, List.copyOf,
    Set.copyOf, Map.copyOf, readAllBytes, isBlank, skipNBytes,
    readNBytes, Files.writeString, DateTimeFormatter).
  - Removed dependency locking and dependency verification metadata
    from the build.

Providers and lookups
  - Removed the proxycheck, iphub, ip-api and ipinfo providers.
  - Added a new AsnProvider and AsnUpdater that read
    GeoLite2-ASN.mmdb and flag VPN/hosting networks by organisation
    name (heuristic).
  - Changed MaxMindUpdater to download GeoLite2 files from GitHub
    Releases (P3TERX/GeoLite.mmdb) instead of the MaxMind download
    service. No MaxMind account or license key is required.
  - Added automatic 24-hour updates for both GeoLite2-Country and
    GeoLite2-ASN.
  - country-from now defaults to maxmind, vpn-from defaults to asn.

Storage
  - Removed SQLite and MariaDB storage (SqlIpStorage, BundledSqlite,
    IsolatedDrivers, DriverAccess, SqliteUnavailableException).
  - Removed the storage section from config.yml.
  - Lookups are now cached only in memory (MemoryCache). The cache
    does not survive restarts.
  - Removed the sqlite-jdbc and mariadb-java-client dependencies and
    the Gson dependency.

Configuration
  - Removed the storage, proxycheck, iphub, ip-api and ipinfo
    sections from config.yml.
  - Removed rules.proxy, because no bundled provider sets
    proxy = true.
  - Changed the default config.yml to an allowlist of post-Soviet
    countries and countries where Russian has official status:
    RU, BY, UA, KZ, KG, TJ, UZ, TM, AZ, AM, GE, MD, LV, LT, EE.
  - Translated all comments in config.yml and messages.yml to
    Russian.

Build
  - Downgraded the com.gradleup.shadow plugin to a version that does
    not fail on the bundled JARs.
  - Removed all test source sets and test dependencies.
  - Added a generateBuildInfo task that writes BuildInfo.java from
    gradle.properties.

Removed code
  - ProxyCheckProvider, IpHubProvider, IpApiProvider, IpInfoProvider,
    Json, KeyRotation, KeyRejectedException, HttpLookup.
  - SqlIpStorage, BundledSqlite, IsolatedDrivers, DriverAccess,
    SqliteUnavailableException.
  - TarGz (no longer needed, .mmdb is downloaded directly).
  - All unit tests under src/test.

What the plugin does
--------------------

OriginGate checks each Velocity login and applies rules before the
player reaches a backend server:

  - Country allowlist or denylist (GeoLite2-Country via maxmind).
  - Hosting and datacenter networks (GeoLite2-ASN via asn, heuristic
    based on the organisation name).
  - VPN networks (GeoLite2-ASN via asn, heuristic based on the
    organisation name).
  - Deny list of IP addresses and CIDR ranges.
  - Deny list of ASNs and organisation substrings.
  - Per-rule and global bypass by permission, player name, or address.
  - Staff alerts for kicks, bypasses and provider problems.
  - Daily log files with configurable retention.
  - In-memory LRU cache with an optional per-IPv6-/64 extension.
  - Lookup budget per minute with allow/deny on exhaustion.

Known limitations
-----------------

  - VPN detection is heuristic. It catches VPNs that name themselves
    in their ASN organisation (NordVPN, SurfShark, ExpressVPN, and
    similar) and VPNs hosted on known hosting providers, but it does
    not catch VPNs that use clean residential ASNs.
  - Proxy detection is not implemented. The asn provider never sets
    proxy = true, so the proxy rule was removed.
  - The lookup cache is not persistent. After a restart every address
    is looked up again.
  - GeoIP city and region are shown in logs but not used by any rule.