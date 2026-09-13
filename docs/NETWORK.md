# The PdaNet network, and peer cross-talk

Notes on the network this app runs on, gathered by probing a live setup. Relevant
because the unit's uplink is a phone hotspot, not real Wi-Fi, and because peers on that
hotspot can reach each other — which makes relaying state between them possible.

The setup probed here is **PdaNet+ itself**, the real app, running on the phone. No
ZConnect+/ZRemote software was involved; see the last section for why that matters.

Addresses below are the fixed PdaNet ones. Per-device DHCP leases are written `.x`,
since they change every session and identify nothing useful.

## Topology

```
   phone — PdaNet+ (Wi-Fi Direct group owner)
   192.168.49.1
        |  :8000  PAC file + HTTP proxy — the internet for everyone else
        |
   ~~~~ 192.168.49.0/24 ~~~~  AP isolation OFF — peers reach each other
        |                     |                      |
    peer .x               peer .x                peer .x
```

The group owner address is fixed at `192.168.49.1` by Wi-Fi Direct, which is why
`NetState.PDANET_GATEWAY` can be a constant rather than something to discover. Everyone
else gets a DHCP lease in the same `/24`.

## What is actually listening

Exactly one thing, on the phone:

| Where | Port | What | Status |
|---|---|---|---|
| phone `.1` | TCP 8000 | PdaNet+ — serves the PAC file *and* is the proxy itself | **confirmed live** |

Every other port probed on every host was closed. That is the whole network: a gateway
offering a proxy, and peers using it.

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

## Cross-talk

**Peers can reach each other directly.** ICMP between two non-gateway hosts on the
subnet succeeds, so PdaNet+ does not do client isolation. Latency is uneven — observed
48–393 ms round trip between two peers — so anything built on this wants generous
timeouts, not LAN-grade assumptions.

That makes peer-to-peer relaying viable without going through the phone. Two things to
know before building on it:

1. **Nothing announces itself.** With PdaNet+ alone there is no discovery protocol on
   this network at all — no broadcast, no beacon, nothing addressed to the subnet at
   large. A peer wanting to know who else is here has to sweep the `/24` itself, or be
   told out of band.
2. **Leases move.** Only `.1` is stable. Anything that caches a peer's address needs to
   re-resolve it rather than assume it survives a reconnect.

## What this means for this app

Today it uses exactly one fact from all of the above: the default-route gateway being
`192.168.49.1` means the link is the PdaNet one, so ZLauncher will raise a tunnel on it.
See the handoff section in the main README.

The cross-talk finding is recorded here because it is the groundwork for a peer relay —
a unit that knows it is online could tell the others, rather than each one discovering
the state independently. Nothing in this app does that yet, and with PdaNet+ alone there
is no existing protocol to hook into.

## What is *not* on this network

The ZConnect+ Remote / ZRemote-Receiver pair is a **separate project** and was not
running. Its ports (`:8001` for remote-control commands host → client, UDP `:8002` for
the client's `ZREMOTE_HELLO` announcements) belong to that design, not to PdaNet+.
Probing found both closed, which is the expected result when those apps are not
installed or not started — not evidence of anything being broken.

Worth keeping straight when reading ZLauncher's source too: its
`KeepAliveService.PROXY_PORT = 8080` is a loopback-only server, bound explicitly to the
IPv4 literal `127.0.0.1` on the head unit. It never appears on the subnet and no peer
can reach it.

## Not verified

- A second host was present on the subnet during probing, but with nothing listening it
  could only be identified by its MAC vendor prefix (a MediaTek Wi-Fi part, consistent
  with the head unit). Not confirmed.
