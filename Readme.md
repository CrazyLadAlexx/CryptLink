# CryptLink

CryptLink carries encrypted plugin messages between Paper or Leaf backends through Velocity. Backend plugins send a destination, topic, and binary payload through a small public API. Velocity routes the packet without possessing the encryption keys or seeing the topic or payload.

CryptLink 2 uses a protocol that is intentionally incompatible with CryptLink 1. All proxy and backend installations must be upgraded together.

## Components

| Module | Role |
| --- | --- |
| `cryptlink-api` | Public API, protocol records and codecs, HKDF key derivation, AES-GCM, replay protection, and security telemetry. |
| `cryptlink-paper` | Paper/Leaf service registration, key-file handling, encryption, authentication, and topic dispatch. |
| `cryptlink-velocity` | Source identity binding, allowlist enforcement, per-source rate limiting, and opaque forwarding. |

The Paper and Velocity JARs include the shared API classes. The API JAR is used only as a compile-time dependency by plugins integrating with CryptLink.

## Requirements and build

CryptLink targets Java 21, Paper API `1.21.11-R0.1-SNAPSHOT`, and Velocity API `3.4.0-SNAPSHOT`. Use a Leaf release compatible with that Paper API. Folia is not supported.

The repository contains a Gradle 9.2.1 wrapper with the official distribution SHA-256 checksum configured:

```sh
bash ./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

The build runs the JUnit suite, creates reproducible JARs, and writes SHA-256 files to `build/checksums`. Dependency lock files are committed for every module. Paper and Velocity publish the selected API coordinates as changing snapshot modules, so Gradle warns that their repository content can change even while the declared coordinates are locked. CI validates the wrapper, builds on JDK 21, runs CodeQL, and uploads the JARs and checksums.

## Installation

1. Put `cryptlink-velocity-2.0.0.jar` in Velocity's `plugins` directory.
2. Put `cryptlink-paper-2.0.0.jar` in every participating Paper or Leaf backend's `plugins` directory.
3. Start each backend once, then stop it.
4. Give every backend a distinct `server-name` matching its exact Velocity registration.
5. Choose one backend's generated `keys.yml` as the shared master-key set and securely copy it to the other backends.
6. Put every participating name in Velocity's `allowed-servers` list.
7. Ensure backend ports accept connections only from the trusted Velocity deployment.
8. Start Velocity and all backends.

Minecraft plugin messages use player connections. A backend cannot send or receive through this transport unless it has an active player connection through Velocity. Messages are not queued while a backend is empty.

## Paper configuration

`plugins/CryptLink/config.yml` contains ordinary settings:

```yaml
server-name: "survival"
replay-window-seconds: 30
clock-skew-seconds: 10
replay-entries-per-source: 4096
replay-source-limit: 256
telemetry-log-interval-seconds: 30
```

| Setting | Valid values | Purpose |
| --- | --- | --- |
| `server-name` | 1–64 ASCII letters, digits, `.`, `_`, or `-` | Exact Velocity server name and encrypted sender identity. |
| `replay-window-seconds` | 1–86,400 | How long accepted nonce identities remain in memory. It must cover twice the clock skew plus one second. |
| `clock-skew-seconds` | 0–3,600 | Permitted difference between sender and receiver epoch time. |
| `replay-entries-per-source` | 1–65,536 | Maximum live replay identities retained for one authenticated source. |
| `replay-source-limit` | 1–4,096 | Maximum source partitions in the replay cache. |
| `telemetry-log-interval-seconds` | 1–3,600 | Minimum log interval for each rejection category. Counters continue increasing between log messages. |

Unknown properties, missing required values, invalid ranges, invalid names, and configuration files above 1 MiB prevent the Paper plugin from enabling.

## Key storage

`plugins/CryptLink/keys.yml` contains the master-key set and active outgoing version:

```yaml
current-key-version: 1
keys:
  1: "BASE64_32_BYTE_KEY"
```

On a new installation CryptLink creates `keys.yml` with a random 32-byte master key. It never regenerates or overwrites an existing key file. Invalid existing files and files above 64 KiB fail startup. CryptLink never logs key material.

On POSIX filesystems a generated file is created with mode `600`. Existing POSIX permissions are checked at startup, and CryptLink warns if group or other users have access. Filesystems without POSIX permissions, including normal Windows installations, continue without a permission-mode check; protect the file with the platform's ACLs.

Set `CRYPTLINK_KEYS_FILE` to an external key-file path to use a container secret or another protected location. The variable is optional. Relative values are resolved against the server process directory, although absolute paths are easier to audit.

### Migration from CryptLink 1

CryptLink 1 stored `keys` and `current-key-version` in `config.yml`. During the first CryptLink 2 startup, if `keys.yml` does not exist, CryptLink writes those exact values to `keys.yml`, loads them, and then removes the legacy secret fields from `config.yml`. If `keys.yml` already exists, it is authoritative and is never overwritten by legacy values.

Back up all configurations before upgrading. Upgrade Velocity and every backend while the network is stopped because protocol v1 is never accepted by v2. There is no legacy mode. After the first start, verify `keys.yml`, verify that the legacy fields were removed, and confirm its filesystem permissions before reopening the network.

## Key rotation

`current-key-version` explicitly selects the master key used for outgoing messages. It does not need to be the numerically highest version. Every configured version remains available for incoming decryption.

Use this sequence:

1. Add a new 32-byte random key under an unused version in every backend's `keys.yml` while leaving `current-key-version` unchanged.
2. Restart every backend and verify each one loads successfully.
3. Change `current-key-version` to the new version everywhere.
4. Restart the backends in a coordinated window.
5. Keep the old version through the replay and clock-skew window and until no sender can still use it.
6. Remove the old version during a later coordinated restart.

An unknown current version prevents startup. An unknown incoming version is counted and dropped. Versions range from `0` to `255`; replacing the bytes of an existing live version is unsafe.

## Velocity configuration

`plugins/cryptlink/config.yml` is deliberately parsed as a strict, small configuration format:

```yaml
allowed-servers:
  - "survival"
  - "lobby"
max-packet-bytes: 32766
messages-per-second: 100
message-burst: 200
bytes-per-second: 1048576
byte-burst: 2097152
broadcasts-per-second: 10
broadcast-burst: 20
rate-limit-idle-seconds: 300
telemetry-log-interval-seconds: 30
```

| Setting | Valid values | Purpose |
| --- | --- | --- |
| `allowed-servers` | Unique valid server names | Both senders and receivers must be listed. An empty list denies all traffic. |
| `max-packet-bytes` | Minimum v2 packet size through 32,766 | Rejects oversized input before parsing or forwarding. |
| `messages-per-second` | 1–100,000 | Per-source token refill rate for all messages. |
| `message-burst` | 1–200,000 | Per-source message-token capacity. |
| `bytes-per-second` | 1,024–67,108,864 | Per-source byte-token refill rate. |
| `byte-burst` | 1,024–134,217,728 | Per-source byte-token capacity. A packet larger than this capacity is always rejected. |
| `broadcasts-per-second` | 1–10,000 | Additional per-source broadcast refill rate. |
| `broadcast-burst` | 1–20,000 | Additional per-source broadcast capacity. |
| `rate-limit-idle-seconds` | 60–86,400 | Removes inactive limiter state after this period. |
| `telemetry-log-interval-seconds` | 1–3,600 | Minimum log interval for each proxy rejection category. |

Rate-limit state exists only for configured server names, and each source has independent buckets. A noisy backend therefore exhausts its own allowance. Malformed traffic consumes its source's general allowance. Oversized traffic is rejected before parsing. Broadcasts consume general and broadcast-specific tokens.

`allowed-servers` is required and limited to 4,096 entries. Omitted numeric properties use the defaults shown above, which allows a CryptLink 1 allowlist file to migrate safely. Duplicate names, duplicate properties, unknown properties, comments, aliases, nonempty inline lists, malformed numbers, out-of-range values, and files above 1 MiB are rejected. If loading fails, Velocity registers the channel with a deny-all listener so traffic cannot fall through to ordinary plugin-message forwarding. Configuration is read only at startup.

## Plugin integration

Compile your Paper plugin against `cryptlink-api-2.0.0.jar` without shading it:

```kotlin
dependencies {
    compileOnly(files("libs/cryptlink-api-2.0.0.jar"))
}
```

For `plugin.yml`, require CryptLink so its API classes and service are available first:

```yaml
depend: [CryptLink]
```

Load the service during `onEnable`:

```java
SecureMessagingApi messaging = getServer().getServicesManager().load(SecureMessagingApi.class);
if (messaging == null) {
    throw new IllegalStateException("CryptLink is unavailable");
}
```

The public interface is:

```java
public interface SecureMessagingApi {
    void send(String targetServer, String topic, byte[] payload);
    void broadcast(String topic, byte[] payload);
    Subscription subscribe(String topic, BiConsumer<String, byte[]> handler);
}
```

Keep the returned `Subscription` and close it in your plugin's `onDisable`:

```java
Subscription subscription = messaging.subscribe("myplugin:update", (sourceServer, payload) -> {
    handleUpdate(sourceServer, payload);
});

subscription.close();
```

Closing is thread-safe and idempotent and prevents later dispatches. A handler already running when another thread closes the subscription may finish. CryptLink closes all remaining subscriptions during its own shutdown. A plugin that discards its handle can still leave a handler active until CryptLink stops, so consumer plugins are responsible for their own lifecycle.

Topics are case-sensitive, nonblank, and limited to 256 Java characters and 1,024 UTF-8 bytes. Topic names are routing labels inside the local JVM, not an authorization boundary. Every plugin on the backend belongs to the same local trust boundary. Payload encoding and application-level authorization belong to the consuming plugins.

Send calls must run on the Paper main thread while a player is online. Handlers run on the Paper main thread and should return quickly. Payload arrays are defensively copied for messages and for each handler.

## Protocol v2

All integer fields are big-endian. `magic` is the four ASCII bytes `CRLK`. Every protocol prefix is `[magic:4][version:1]`, where version is `2`. Unknown magic, unknown versions, truncation, invalid lengths, and packets above the configured limit are rejected.

### Paper to Velocity

Unicast:

```text
[magic:4][version:1][route-kind:1 = 0][target-length:1][ASCII target][envelope]
```

Broadcast:

```text
[magic:4][version:1][route-kind:1 = 1][target-length:1 = 0][envelope]
```

Velocity obtains the real source from the event's `ServerConnection`. It ignores any backend claim about transport source and creates a new forwarded wrapper.

### Velocity to Paper

```text
[magic:4][version:1][source-length:1][ASCII source][envelope]
```

The source is the identity stamped by trusted Velocity. The receiver derives the sender-specific encryption key from this value, decrypts the message, and requires the encrypted source to equal the stamped source.

### Encrypted envelope

```text
[magic:4][version:1][key-version:1][nonce:12][epoch-seconds:4][ciphertext][GCM-tag:16]
```

The epoch field is interpreted as an unsigned 32-bit value. The complete 22-byte envelope header, including magic, protocol version, key version, nonce, and timestamp, is AES-GCM additional authenticated data. Any change causes structural rejection or authentication failure.

### Encrypted plaintext

```text
[source-length:2][UTF-8 source]
[destination-length:2][UTF-8 destination]
[topic-length:2][UTF-8 topic]
[remaining bytes: payload]
```

Source, destination, broadcast marker, topic, and payload are all authenticated because they are encrypted by AES-GCM. Velocity's outer destination is compared indirectly at the receiving backend: a packet rerouted to a server other than its encrypted destination is rejected. A broadcast has the authenticated destination `*` and a distinct outer route kind, so malformed broadcast/unicast conversions are rejected.

## Cryptography

Each configured secret is a 256-bit master key. For every source server and key version, CryptLink derives a 256-bit AES key using HKDF-SHA-256 with a fixed CryptLink v2 salt and a context containing the protocol purpose, key version, and validated source name. Messages use AES-256-GCM with a fresh 96-bit nonce from `SecureRandom` and a 128-bit authentication tag.

Sender-specific derivation separates GCM key and nonce domains. Random nonces generated by different backends are no longer used under the same AES key, so a collision between two servers does not repeat a GCM nonce/key pair. A collision from the same sender under the same version remains dangerous but has negligible probability with correctly functioning `SecureRandom`.

CryptLink uses standard JDK `Mac` and `Cipher` primitives. It does not implement a custom cipher. Authentication failures never produce plaintext for dispatch.

## Replay protection

The receiver checks timestamp freshness before key derivation and decryption. It records a replay identity only after successful authentication, message decoding, source binding, and destination validation, so unauthenticated input cannot fill the replay cache.

Replay identities contain authenticated source, key version, and nonce. A nonce reused under the same sender-derived key is rejected. The same random nonce under a different key version or sender domain is independent. Each source has a bounded partition, which prevents authenticated traffic from one backend filling every other backend's partition. A full partition rejects new traffic from that source until entries expire.

Replay state is in memory. Restarting a backend clears it, so a captured packet can be accepted again after restart if its timestamp remains inside the configured clock-skew window. Keep system clocks synchronized and make application handlers idempotent when duplicate side effects would be harmful.

## Security telemetry

Paper and Velocity count rejection categories including malformed packets, unauthorized backends, source mismatch, authentication failure, unknown key version, replay, stale timestamp, rate limit, and unknown destination. Logging is independently rate-limited per category. Logs contain counts and category names, never keys, plaintext, payloads, or ciphertext.

Counters are process-local and reset on restart. CryptLink does not currently export metrics to an external monitoring system.

## Security model

AES-GCM protects the encrypted source, destination, broadcast status, topic, and payload from disclosure and undetected modification. The envelope protocol version, key version, nonce, and timestamp are visible but authenticated as AAD.

Velocity can observe the real source connection, outer destination or broadcast status, protocol and key versions, nonce, timestamp, sizes, timing, and fan-out. It cannot read encrypted source claims, topics, or payloads without the master keys. It can drop, delay, duplicate, reorder, reroute, or corrupt traffic. Receivers reject corruption, stale duplicates, source-stamp mismatch, and destinations that do not match the authenticated plaintext. Availability still depends on Velocity.

Velocity is the trusted identity and routing authority. A backend possessing the shared master keys can derive all sender keys, but an uncompromised Velocity stamps its real `ServerConnection` identity. A packet encrypted for a forged sender fails under the stamped sender key; a packet encrypted under the attacker's sender key with another encrypted source fails the equality check. Therefore one compromised backend cannot impersonate another through an uncompromised Velocity relay.

Compromising one backend exposes the shared master-key set. The attacker can decrypt captured CryptLink envelopes, forge content as that backend, and derive other sender keys, but still needs Velocity to stamp a different transport identity to impersonate another backend. Rotate every master key after restoring the backend. This design does not provide forward secrecy.

Compromising Velocity exposes routing metadata and permits denial of service, traffic analysis, rerouting, and false transport stamps. Velocity still lacks plaintext unless it also obtains a master key. A compromised Velocity plus any compromised backend or stolen master key can impersonate any server.

Stealing a master key permits decryption of captured messages for that version and derivation of every sender key for that version. Without control of Velocity's source stamp, it does not by itself permit cross-server impersonation through the supported path. Remove the stolen version everywhere and replace it using a coordinated rotation.

CryptLink does not use per-server Ed25519 signatures. Signatures would preserve server identity even if Velocity stamped a false source and would limit private signing-key compromise to one sender, but they add per-server public-key distribution, private-key storage, rotation, and revocation. Protocol v2 uses the simpler transport-binding design because Velocity is already the trusted identity authority. Deployments that cannot trust Velocity for identity need a signature protocol rather than this one.

Backend ports must not be exposed to players or untrusted networks. Clients are blocked at Velocity, but CryptLink does not authenticate the physical connection between Velocity and Paper. Use Velocity modern forwarding and firewall rules appropriate to the deployment.

## Delivery and operational limits

Delivery is best effort. There are no acknowledgements, retries, durable queues, ordering guarantees, delivery receipts, or transactions. A local send only means the plugin message was submitted to the current player connection. Unknown, disconnected, disallowed, malformed, stale, replayed, or rate-limited traffic is dropped.

Broadcast reaches available, allowlisted backends on the same Velocity process except the sender. Separate Velocity instances do not exchange messages. Applications requiring confirmed delivery must implement authenticated application-level identifiers, acknowledgements, timeouts, duplicate handling, and authorization inside their encrypted payload protocol.

See [SECURITY.md](SECURITY.md) for private vulnerability-reporting guidance and the supported-version policy.
