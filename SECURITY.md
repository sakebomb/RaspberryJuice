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
