# The PdaNet network, and peer cross-talk

Notes on the network this app runs on, gathered by probing a live setup. Relevant
because the unit's uplink is a phone hotspot, not real Wi-Fi, and because peers on that
hotspot can reach each other — which makes relaying state between them possible.

Addresses below are the fixed PdaNet ones. Per-device DHCP leases are written `.x`,
since they change every session and identify nothing useful.

## Topology

```
   phone (PdaNet+ / Wi-Fi Direct group owner)
   192.168.49.1
        |  :8000  HTTP proxy — this is the internet for everyone else
        |  :8001  sends remote-control commands out to the head unit
        |  UDP :8002  receives ZREMOTE_HELLO announcements
        |
   ~~~~ 192.168.49.0/24 ~~~~  AP isolation OFF — peers reach each other
        |                     |                      |
   head unit .x          Linux PC .x            other peers .x
   :8001 remote control  (several similar machines)
```

The group owner address is fixed at `192.168.49.1` by Wi-Fi Direct, which is why
`NetState.PDANET_GATEWAY` can be a constant rather than something to discover. Everyone
else gets a DHCP lease in the same `/24`.

## Ports

| Where | Port | What | Verified |
|---|---|---|---|
| phone `.1` | TCP 8000 | PdaNet — serves the PAC file *and* is the proxy itself | **confirmed live** |
| phone `.1` | UDP 8002 | receives `ZREMOTE_HELLO` from the head unit every 3s | by design |
| head unit | TCP 8001 | **remote control** — ZRemote-Receiver takes input commands here | not listening when probed |

### :8000 does both jobs

It answers a plain `GET /` with `Content-Type: application/x-ns-proxy-autoconfig` and
this body:

```javascript
function FindProxyForURL(url, host){
  if (host=='192.168.49.1') return 'DIRECT';
  else return "PROXY 192.168.49.1:8000; DIRECT";
}
```

So the auto-config server and the proxy it points at are the same port — there is no
second port to find. Traffic aimed at the gateway itself goes `DIRECT`, everything else
is proxied.

`:8000` is load-bearing for every device on the subnet. Nothing should ever disturb it.

The remote control runs host → client: the phone sends on `:8001` and the head unit
listens on `:8001`, injecting the input through its accessibility service. That direction
is why discovery matters at all — the host is the group owner and cannot know the
client's DHCP lease, so the client has to announce itself first or every command is
dropped with "No client IP set".

## Cross-talk

**Peers can reach each other directly.** ICMP between two non-gateway hosts on the
subnet succeeds, so the hotspot does not do client isolation. Latency is uneven —
observed 48–393 ms round trip between two peers — so anything built on this wants
generous timeouts, not LAN-grade assumptions.

That makes peer-to-peer relaying viable without going through the phone. Two things to
know before building on it:

1. **Discovery is unicast, not broadcast.** `ZREMOTE_HELLO` goes to `192.168.49.1`
   specifically, so a peer cannot find other peers by passively listening — nothing is
   ever addressed to the subnet at large. A peer wanting to know who else is here has to
   either ask the host (which sees everyone, both from the hellos and from the `:8000`
   proxy) or sweep the `/24` itself.
2. **Leases move.** Only `.1` is stable. Anything that caches a peer's address needs to
   re-resolve it rather than assume it survives a reconnect.

### Not a peer port: 8080

ZLauncher's `KeepAliveService.PROXY_PORT = 8080` is a loopback-only server, bound
explicitly to the IPv4 literal `127.0.0.1` on the head unit. It never appears on the
subnet and is not something another peer can reach. Easy to mistake for a network port
when reading that source.

## What this means for this app

Today it uses exactly one fact from all of the above: the default-route gateway being
`192.168.49.1` means the link is the PdaNet one, so ZLauncher will raise a tunnel on it.
See the handoff section in the main README.

The cross-talk finding is recorded here because it is the groundwork for a peer relay —
a unit that knows it is online could tell the others, rather than each one discovering
the state independently. Nothing in this app does that yet.

## Not verified

- The head unit had no TCP port open when probed, so ZRemote-Receiver was not running at
  the time. The `:8001` row above is from its design, not from observation.
- No UDP 8002 traffic was observable from a third peer, which is consistent with the
  hellos being unicast to the host, but is not positive proof they were being sent.
