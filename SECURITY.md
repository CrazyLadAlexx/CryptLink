# Security Policy

## Reporting a vulnerability

Do not open a public issue for an undisclosed vulnerability. Use GitHub's private vulnerability reporting feature on the CryptLink repository. Include the affected version, deployment assumptions, reproduction steps, and expected impact. If private reporting is unavailable, contact the repository owner through the contact method listed on the owner's GitHub profile and ask for a private reporting channel without sending exploit details publicly.

Reports concerning source impersonation, authentication bypass, nonce reuse, replay acceptance, client injection, key disclosure, parser denial of service, or rate-limit bypass are treated as security issues.

## Supported versions

CryptLink 2.x is the supported protocol. CryptLink 1.x lacks explicit protocol versioning and proxy-bound backend identity, and should be replaced. Security fixes are applied to the latest 2.x release rather than backported to 1.x.

## Security boundary

Velocity is trusted to identify the originating `ServerConnection`, stamp that identity on forwarded packets, enforce the backend allowlist, and prevent clients from entering the backend transport path. Paper and Leaf servers must be reachable only through the trusted Velocity deployment.

All participating backends possess the shared master-key set. CryptLink derives a different AES key for each source server and key version. A compromised backend can decrypt network messages if it obtains their encrypted envelopes, derive every sender key from the shared master keys, forge application content under its own Velocity-stamped identity, and disrupt traffic within its proxy permissions. Through an uncompromised Velocity relay it cannot make a receiver accept another backend's identity.

Velocity does not hold encryption keys and cannot decrypt topics or payloads. It can observe traffic metadata, drop, delay, duplicate, reorder, reroute, or modify packets. Modifications to authenticated data are rejected, and rerouting to a different destination is rejected by that receiver. A compromised Velocity combined with any holder of the shared master keys can impersonate any backend because Velocity controls the transport identity stamp.

CryptLink provides authenticated, encrypted, best-effort messages within these assumptions. It does not provide durable delivery, forward secrecy, compromise recovery, non-repudiation, plugin isolation inside one JVM, or protection for an exposed backend network endpoint.
