# CryptLink

CryptLink lets plugins on Paper and Leaf backends exchange encrypted messages through a Velocity proxy. A message contains a source server, destination, topic, and binary payload. Plugins can send to one backend or broadcast to every allowlisted backend except the sender.

CryptLink uses the `cryptlink:secure` plugin channel. It has no commands, player permissions, database, or separate network service. Your plugins decide what each topic and payload means.

## Modules

| Module | Purpose |
| --- | --- |
| `cryptlink-api` | Public interface, message records, encryption, replay protection, and binary codecs. |
| `cryptlink-paper` | Registers the API with Bukkit, sends encrypted messages, and dispatches received messages to topic handlers. |
| `cryptlink-velocity` | Checks the relay allowlist, reads the destination prefix, and forwards the encrypted envelope unchanged. |

Both plugin JARs include the shared API classes. The API JAR is a compile-time dependency for other plugins; it is not installed as a server plugin.

## Build

Use JDK 21 and Gradle 9.2.1. There is no Gradle wrapper in this repository.

```sh
gradle clean build
```

The build produces:

```text
cryptlink-api/build/libs/cryptlink-api-1.0.0.jar
cryptlink-paper/build/libs/cryptlink-paper-1.0.0.jar
cryptlink-velocity/build/libs/cryptlink-velocity-1.0.0.jar
```

The project compiles against Paper `1.21.11-R0.1-SNAPSHOT` and Velocity `3.4.0-SNAPSHOT`. The Paper descriptor requires API version `1.21.11`; use a Leaf build compatible with that API. Folia is not supported.

Only the Paper and Velocity APIs are declared as external dependencies, and neither is bundled. Velocity's injection annotation comes from its API dependency. Encryption uses the JDK's `javax.crypto` implementation.

## Installation

1. Put the Velocity JAR in the proxy's `plugins` directory.
2. Put the Paper JAR in each participating backend's `plugins` directory.
3. Start the servers once to create their configuration files, then stop them before editing.
4. Set each backend's `server-name` to its exact registered name in Velocity.
5. Securely copy one backend's generated `keys` section to every participating backend. Keep the server names distinct.
6. Add the participating server names to Velocity's CryptLink allowlist.
7. Restart the proxy and backends.

Each backend generates its own random key on first startup. Those independent keys must be replaced with the same shared key set before messages can be decrypted across servers. Keys are stored in the backend configuration files; the proxy does not need them.

Sending and receiving require active player connections through the proxy. Empty backends cannot exchange messages over this transport. This follows the underlying [Paper plugin-messaging transport](https://docs.papermc.io/paper/dev/plugin-messaging/).

## Configuration

### Paper and Leaf

File: `plugins/CryptLink/config.yml`.

```yaml
server-name: "survival"
keys:
  1: "BASE64_32_BYTE_KEY"
current-key-version: 1
replay-window-seconds: 30
clock-skew-seconds: 10
```

The bundled `BASE64_32_BYTE_KEY` value is replaced with a random 32-byte key on first startup, provided it is the only key and its version is `1`.

| Setting | Meaning |
| --- | --- |
| `server-name` | Exact, case-sensitive name registered in Velocity. Names contain 1–64 ASCII letters, digits, dots, underscores, or hyphens. |
| `keys` | Key versions from `0` to `255`, each containing a base64-encoded 32-byte AES key. At least one key is required. |
| `current-key-version` | Must equal the highest configured version, which is always used for outgoing messages. If omitted, the highest version is used. |
| `replay-window-seconds` | Nonce retention time, default `30`. Must be at least twice the clock skew plus one second. |
| `clock-skew-seconds` | Accepted timestamp difference in either direction, default `10`. Must be nonnegative. |

The channel is fixed and cannot be configured. Invalid configuration prevents CryptLink from enabling on that backend.

### Velocity

File: `plugins/cryptlink/config.yml`.

```yaml
allowed-servers:
  - "survival"
  - "lobby"
```

Both the originating backend and each destination must appear in this list. An empty file or `allowed-servers: []` denies all relaying. The bundled configuration explicitly allows `survival` and `lobby`.

The Velocity configuration reader accepts this single list, with plain, single-quoted, or double-quoted names. It does not implement general YAML: comments, extra properties, aliases, and nonempty inline lists are unsupported. Invalid configuration disables relaying while the channel remains blocked from ordinary forwarding.

Configuration is loaded at startup. There is no reload command.

## Integrating another plugin

Copy `cryptlink-api-1.0.0.jar` into your plugin project's `libs` directory and add it as a compile-only dependency:

```kotlin
dependencies {
    compileOnly(files("libs/cryptlink-api-1.0.0.jar"))
}
```

For a Bukkit-style plugin, add the following to its `plugin.yml` so CryptLink loads first and its classes are available through the dependency relationship described in [Paper's plugin metadata documentation](https://docs.papermc.io/paper/dev/plugin-yml/):

```yaml
depend: [CryptLink]
```

Keep the API out of your plugin's shaded JAR. At runtime, use the classes provided by CryptLink. Plugins using `paper-plugin.yml` need the equivalent server dependency on CryptLink with classpath access enabled.

The interface is `me.alex.cryptlink.api.SecureMessagingApi`:

```java
public interface SecureMessagingApi {
    void send(String targetServer, String topic, byte[] payload);
    void broadcast(String topic, byte[] payload);
    void subscribe(String topic, java.util.function.BiConsumer<String, byte[]> handler);
}
```

Obtain and retain the registered service during your plugin's `onEnable`, using an import for `me.alex.cryptlink.api.SecureMessagingApi`:

```java
SecureMessagingApi messaging = getServer().getServicesManager().load(SecureMessagingApi.class);
if (messaging == null) {
    throw new IllegalStateException("CryptLink is unavailable");
}
```

Register handlers with `subscribe(topic, handler)`. A handler receives the source server name and a copy of the payload. Topics are case-sensitive, nonblank strings of at most 256 Java UTF-16 code units. Use a plugin-specific topic prefix to avoid collisions. Payload serialization is your plugin's responsibility.

Call `send(targetServer, topic, payload)` or `broadcast(topic, payload)` later, on the backend's main server thread, while a player connection is available. Calling from an asynchronous task, after CryptLink is disabled, or without an online player throws `IllegalStateException`. Invalid names, topics, and oversized messages throw `IllegalArgumentException`.

Handlers run on the backend's main server thread. Keep them short; move slow work to your own asynchronous tasks. Multiple handlers can subscribe to the same topic, and a runtime exception in one handler is logged without preventing the others from running. A topic with no subscribers is ignored.

Subscriptions last until CryptLink shuts down. There is no unsubscribe method or automatic cleanup when an individual consuming plugin is disabled. Use a full restart when replacing plugins that register handlers.

## Message flow

1. The sending backend serializes the source, destination, topic, and payload.
2. It encrypts that data with AES-256-GCM, using the highest configured key version and a fresh 12-byte nonce.
3. It prefixes the encrypted envelope with the destination and sends the packet to Velocity.
4. Velocity consumes the channel event, rejects client-originated messages, checks the allowlist, and removes the destination prefix.
5. Velocity forwards the envelope to the named backend, or to each allowlisted backend other than the sender for a broadcast. It never decrypts the envelope.
6. The receiving backend checks the timestamp, selects the stated key version, authenticates and decrypts the message, checks its encrypted destination, and records the nonce before dispatching the topic.

The proxy handles messages before checking their source, following [Velocity's plugin-messaging guidance](https://docs.papermc.io/velocity/dev/plugin-messaging/), so rejected packets are not forwarded to players or backends by the normal channel path.

## Wire format

All integer fields use big-endian byte order. String lengths count bytes.

Paper to Velocity:

```text
[2-byte destination length][ASCII destination][encrypted envelope]
```

The destination `*` means broadcast. The public `send` method accepts a server name; use `broadcast` to send to all other allowed servers.

Velocity to Paper:

```text
[1-byte key version][12-byte nonce][4-byte epoch seconds][ciphertext][16-byte GCM tag]
```

The version and timestamp are interpreted as unsigned values. The entire 17-byte envelope header is authenticated as GCM additional data.

Decrypted content:

```text
[2-byte source length][UTF-8 source]
[2-byte destination length][UTF-8 destination]
[2-byte topic length][UTF-8 topic]
[remaining bytes: payload]
```

The outer destination is visible to Velocity. The encrypted copy lets a backend reject messages addressed to another server. Payloads, topics, and the claimed source are encrypted; destination names, timestamps, key versions, message sizes, and traffic timing remain visible.

The complete outgoing packet is limited to 32,766 bytes. The payload allowance is smaller by `41 + sourceBytes + 2 × destinationBytes + topicBytes`. CryptLink does not split large messages into multiple packets.

## Replay protection and trust

Timestamps outside the configured clock window are rejected before the nonce cache is consulted. Nonces are recorded only after authentication, decoding, and destination checks succeed. The cache holds at most 65,536 nonces per backend and removes expired entries when another nonce is checked. If it is full, new messages are rejected until entries expire; live entries are retained.

Keep backend clocks synchronized. The nonce cache is held in memory, so a restart clears it and can allow a previously accepted packet to be accepted again while its timestamp is still valid.

All backends with the shared keys belong to the same trust group. Any holder of a key can create an authenticated message, including one claiming another source server. The source string received by a handler is not a cryptographically distinct server identity. Topic names do not provide access control between plugins on the same backend.

## Key rotation

Add a fresh 32-byte key under a higher unused version on every participating backend and set `current-key-version` to that version. Retain the previous keys so delayed messages can still be decrypted. Never replace an existing version with different key bytes while it remains in use elsewhere.

The highest key becomes active immediately when a backend starts; this version has no separate staging setting for a decrypt-only future key. Pause message-producing plugins while distributing the updated configuration and restarting the backends. Resume sending once every receiver has loaded the new version, then remove old keys during a later coordinated restart after their messages have expired.

Versions are limited to one byte. Rotation after version `255` requires a coordinated reset of the configured version set.

## Delivery limits

Messages are best effort. There are no acknowledgements, retries, persistent queues, delivery receipts, or cross-server transactions. A successful `send` return means the local plugin message was submitted; it does not prove a remote handler ran. Unknown or disallowed destinations, unavailable backend connections, failed authentication, and expired or replayed messages are dropped.

Broadcasts reach only available, allowlisted backends connected to the same proxy. Multiple Velocity instances do not exchange CryptLink messages with one another. If your application needs delivery guarantees, it must implement its own application-level acknowledgements, timeouts, and duplicate handling.
