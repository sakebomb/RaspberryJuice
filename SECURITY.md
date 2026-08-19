# Security Policy

## ⚠️ RaspberryJuice is an unauthenticated network service

RaspberryJuice opens a plain, **unauthenticated and unencrypted** TCP socket (default
`localhost:4711`). Anyone who can reach that port can place and destroy blocks, spawn and
drive entities, control the world (time/weather), and affect players. This is by design — it's
a teaching/scripting bridge — but it means:

- **Keep it on `localhost` unless you know what you're doing.** The default `hostname:
  localhost` binds to loopback only. Only set `hostname: 0.0.0.0` on a trusted, firewalled
  network, and understand that *every* client on that network gets full control.
- On a shared/survival server, treat a socket connection as equivalent to operator access to
  the game world. The `max-blocks` cap guards against cuboid DoS; id-targeted `entity.*`
  mutators refuse to act on players; but the surface is still powerful.
- **Reactive event streams are scoped per connection and fail closed.** The reactive streams
  (`events.player.moves` / `block.breaks` / `block.places` / `player.deaths`) report only the
  session's own player by default (`allow-global-events: false`) so a client isn't handed a
  live feed of everyone's activity. A connection declares which player it is with
  `setPlayer(<name>)` (Python: `mc.set_player("Alice")`), and events are matched to that player
  by UUID. An **unbound** connection on a multi-player server receives **no** player's events
  at all (fail closed) — it can no longer fall back to observing an arbitrary player. The lone
  single-player / single-user case still works without binding (there's no other player to
  leak). Note that `setPlayer` takes any online player's name and performs **no ownership
  check** — `auth-token` gates *who may connect*, not *which player a connection may bind to or
  observe*. On a server with mutually-distrusting clients, any authenticated client can
  `setPlayer("<someone-else>")` and watch that player's event feed; do not rely on `setPlayer`
  for isolation between untrusted parties. Per-player authorization is tracked in issue #47.

## Hardening a networked deployment

If you need clients to connect from other machines, do **both** of these — they solve
different problems:

### 1. Require an auth token (who may connect)
Set a shared secret in `config.yml`:

```yaml
auth-token: 'some-long-random-string'
```

Clients must then authenticate before any other command by sending `auth(<token>)` — the
Python client does this for you: `Minecraft.connect(host, port, token="…")`. Until
authenticated, every command is refused, and the connection is dropped after a few bad
attempts. This stops *unauthorized connections*.

> **Note on upgrades:** Bukkit only writes new config keys to a fresh `config.yml`. If you
> upgraded from an older version, `auth-token` won't be in your file yet — add the line
> manually (any missing key falls back to its default), or delete `config.yml` to regenerate.

### 2. Encrypt the transport (who may read/tamper)
The socket itself is **plaintext**, so the auth token — and everything else — is visible to
anyone who can sniff the network. RaspberryJuice deliberately does *not* build in TLS
(certificate management is a poor fit for a teaching tool). Instead, tunnel the port with a
proven tool:

- **Tailscale / WireGuard (recommended):** put the server and clients on the same tailnet and
  keep `hostname: localhost` (or bind to the tailnet address). You get WireGuard encryption
  **and** device identity for free, with no certificates to manage.
- **SSH port-forward:** `ssh -L 4711:localhost:4711 user@server` — the client connects to its
  own `localhost:4711`, encrypted end to end.
- **stunnel** or a TLS-terminating reverse proxy in front of the port.

For a classroom LAN, the auth token alone is usually enough. For anything crossing an
untrusted network, add a tunnel.

## Supported versions

Security fixes land on `master` and ship in the next release. The `2.x` line (Paper 26.2 /
Java 25) is the supported line; older lines are not maintained.

## Reporting a vulnerability

Please **do not** open a public issue for a security vulnerability. Instead, use GitHub's
private vulnerability reporting:

1. Go to the repository's **Security** tab → **Report a vulnerability**.
2. Describe the issue, affected version, and a reproduction if possible.

We'll acknowledge the report, investigate, and coordinate a fix and disclosure. Thanks for
helping keep the project and its users safe.
