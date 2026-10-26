#!/usr/bin/env python3
"""
verify_egress.py -- "no unexpected egress" checker for the TorBox/Real-Debrid client.

Owner: env-runner. Python 3.9+ standard library only.

SCOPE (corrected by the project owner): "tor" in this project means **TorBox**, the
debrid service. There is no Tor/onion routing in the product, so relay/oracle
classification was removed. The privacy claim that remains is:

    the client only talks to the APIs it is supposed to, over TLS

WHAT IT DOES
  Decodes an observed capture (pcap/pcapng or a flows.json from pcap_parse.py) and
  classifies EVERY outbound destination by the names actually observed on the wire:

    * TLS SNI (ClientHello, reassembled across TCP segments)
    * HTTP Host header (plaintext HTTP)
    * DNS names (answers mapped to the destination IP, and query names for :53)

  against an allowlist. A destination is ALLOWED only when an observed name matches
  an allowlist entry (exact or subdomain). Loopback and the local fixture server are
  allowed separately. Everything else is UNEXPECTED and the process exits 2.

ALLOWLIST (default: TorBox / Real-Debrid / TMDb / Stremio-Comet)
    api.torbox.app, api.real-debrid.com, api.themoviedb.org, image.tmdb.org,
    comet.stremio.ru
  Override with --allowed "a.example,b.example".

SOURCES
  --capture <pcap|pcapng>      decode in-process (imports scripts/pcap_parse.py)
  --flows <capture.flows.json> reuse a previous decode
  --source endpoints --input "ip:port,..."   (no names -> only loopback/fixture allowed)

OUTPUT
  JSON report + human table + one verdict line.
  Exit: 0 = no unexpected egress, 2 = UNEXPECTED egress found, 1 = error.

USAGE
  python scripts/verify_egress.py --capture EVIDENCE/captures/guest-traffic-2026-10-26.pcap \
      --out EVIDENCE/captures/egress-allowlist.json
  python scripts/verify_egress.py --capture ... --allowed "check.torproject.org"
"""

import argparse
import hashlib
import ipaddress
import json
import os
import sys
from datetime import datetime, timezone

TOOL_VERSION = "2.0.0"

DEFAULT_ALLOWLIST = [
    "api.torbox.app",
    "api.real-debrid.com",
    "api.themoviedb.org",
    "image.tmdb.org",
    "comet.stremio.ru",
]

# The local test fixture the debug build is allowed to use (cleartext, debug only).
FIXTURE_HOST = "10.0.2.2"
FIXTURE_PORT = 8787

# Emulator guest addresses; flows *from* these are outbound.
GUEST_IPS = {"10.0.2.15", "10.0.2.16"}


def utcnow():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def is_loopback(ip):
    try:
        return ipaddress.ip_address(ip).is_loopback
    except ValueError:
        return False


def name_matches(name, entry):
    """Exact match, or a subdomain of the allowed entry."""
    if not name:
        return False
    n = name.strip().lower().rstrip(".")
    e = entry.strip().lower().rstrip(".")
    return n == e or n.endswith("." + e)


def allowed_by(names, allowlist):
    """Return (is_allowed, matched_entry|None, matched_name|None)."""
    for item in names:
        for entry in allowlist:
            if name_matches(item["name"], entry):
                return True, entry, item["name"]
    return False, None, None


# ---------------------------------------------------------------------------
# Input adapters
# ---------------------------------------------------------------------------

def load_from_pcap(path):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import pcap_parse  # local module, stdlib only
    data = pcap_parse.extract(path)
    return {
        "kind": "pcap",
        "path": path,
        "sha256": data["source_sha256"],
        "bytes": data["source_bytes"],
        "flow_list": data["flows"],
        "dns": data.get("dns", {}),
        "http": data.get("http", []),
        "summary": data.get("summary", {}),
    }


def load_from_flows(path):
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return {
        "kind": "flows-json",
        "path": path,
        "sha256": sha,
        "bytes": os.path.getsize(path),
        "flow_list": data.get("flows", []),
        "dns": data.get("dns", {}),
        "http": data.get("http", []),
        "summary": data.get("summary", {}),
    }


def build_endpoints(src):
    """Aggregate outbound flows and attach every decoded name."""
    flows = src["flow_list"]
    dns = src.get("dns") or {}
    ip_to_name = dns.get("ip_to_name") or {}
    queries = dns.get("queries") or []
    http_entries = src.get("http") or []

    guest = {f.get("src") for f in flows if f.get("src") in GUEST_IPS}
    if not guest:
        guest = set(GUEST_IPS)

    # DNS query names grouped by the resolver endpoint the client talked to.
    queries_by_server = {}
    for q in queries:
        server = q.get("server")
        if not server or not q.get("name"):
            continue
        queries_by_server.setdefault(server, set()).add(q["name"])

    endpoints = {}
    for f in flows:
        if f.get("src") not in guest:
            continue
        if f.get("dst") in guest:
            continue                      # guest -> itself is not egress
        dst, dport, proto = f.get("dst"), f.get("dport"), f.get("proto")
        if not dst or dport is None:
            continue
        key = (dst, dport, proto)
        ep = endpoints.get(key)
        if ep is None:
            ep = endpoints[key] = {
                "dst": dst, "port": dport, "proto": proto,
                "packets": 0, "bytes": 0, "names": [],
            }
        ep["packets"] += f.get("packets", 0)
        ep["bytes"] += f.get("bytes", 0)

        sni = f.get("sni")
        if sni:
            ep["names"].append({"name": sni, "source": "tls-sni"})

    # HTTP Host headers (plaintext HTTP only) map to the TCP peer.
    for h in http_entries:
        if h.get("direction") != "request":
            continue
        host = h.get("host")
        if not host:
            continue
        hostname = host.split(":")[0]
        key = (h.get("dst"), h.get("dport"), "TCP")
        if key in endpoints:
            endpoints[key]["names"].append({"name": hostname, "source": "http-host"})

    # DNS answer names for the destination IP + DNS query names for :53.
    for ep in endpoints.values():
        answered = ip_to_name.get(ep["dst"])
        if answered:
            ep["names"].append({"name": answered, "source": "dns-answer"})
        if ep["port"] == 53:
            for qname in sorted(queries_by_server.get("%s:53" % ep["dst"], set())):
                ep["names"].append({"name": qname, "source": "dns-query"})

    # De-duplicate names while keeping provenance sources.
    for ep in endpoints.values():
        seen = {}
        for n in ep["names"]:
            seen.setdefault((n["name"].lower(), n["source"]), n)
        ep["names"] = sorted(seen.values(), key=lambda n: (n["name"], n["source"]))
    return list(endpoints.values())


def load_endpoints_source(input_text):
    """--source endpoints "ip:port,..." with no decoded names."""
    out = []
    for item in input_text.split(","):
        item = item.strip()
        if not item:
            continue
        if item.startswith("["):
            close = item.find("]")
            ip, rest = item[1:close], item[close + 1:]
            port = int(rest[1:]) if rest.startswith(":") and rest[1:].isdigit() else None
        elif item.count(":") == 1:
            ip, port_s = item.split(":")
            port = int(port_s) if port_s.isdigit() else None
        else:
            ip, port = item, None
        out.append({"dst": ip, "port": port, "proto": None, "packets": 0,
                    "bytes": 0, "names": []})
    return out


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------

def classify(ep, allowlist):
    dst, port = ep["dst"], ep["port"]
    names = ep.get("names") or []
    name_list = [n["name"] for n in names]

    if is_loopback(dst):
        return "loopback", {"reason": "loopback address"}, None, None

    if dst == FIXTURE_HOST and port == FIXTURE_PORT:
        return "fixture", {"reason": "local fixture server (debug-only allowance)"}, None, None

    if port == 53:
        if not name_list:
            return "UNEXPECTED", {"reason": "DNS resolver contact with no decoded query names"}, None, None
        if all(allowed_by([n], allowlist)[0] for n in names):
            return "allowed", {"reason": "all decoded DNS names are allowlisted"}, None, None
        bad = sorted({n for n in name_list if not allowed_by([{"name": n}], allowlist)[0]})
        return "UNEXPECTED", {"reason": "DNS query names not allowlisted",
                              "not_allowed": bad}, None, None

    if not name_list:
        return "UNEXPECTED", {"reason": "no name observed for this destination "
                                        "(no TLS SNI / HTTP Host / DNS name)"}, None, None

    ok, entry, matched = allowed_by(names, allowlist)
    if ok:
        extra = [n for n in name_list if not allowed_by([{"name": n}], allowlist)[0]]
        ev = {"reason": "observed name matches allowlist entry", "matched_entry": entry}
        if extra:
            ev["other_names_not_allowlisted"] = sorted(set(extra))
        return "allowed", ev, entry, matched

    return "UNEXPECTED", {"reason": "observed names not in allowlist",
                          "names": sorted(set(name_list))}, None, None


def main():
    ap = argparse.ArgumentParser(description="No-unexpected-egress checker (allowlist mode)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--capture", help="pcap/pcapng to decode and check")
    src.add_argument("--flows", help="flows.json produced by pcap_parse.py")
    src.add_argument("--source", choices=["endpoints"],
                     help="endpoints mode: --input 'ip:port,...'")
    ap.add_argument("--input", help="endpoint list for --source endpoints")
    ap.add_argument("--allowed", help="comma-separated allowlist override")
    ap.add_argument("--out", help="JSON report path")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    allowlist = ([a.strip() for a in args.allowed.split(",") if a.strip()]
                 if args.allowed else list(DEFAULT_ALLOWLIST))

    if args.capture:
        source = load_from_pcap(args.capture)
        endpoints = build_endpoints(source)
        source_desc = "pcap decode: %s" % args.capture
    elif args.flows:
        source = load_from_flows(args.flows)
        endpoints = build_endpoints(source)
        source_desc = "flows.json: %s" % args.flows
    else:
        if not args.input:
            raise SystemExit("--source endpoints requires --input 'ip:port,...'")
        source = {"kind": "endpoints", "path": None, "sha256": None, "bytes": None,
                  "flow_list": [], "dns": {}, "http": [], "summary": {}}
        endpoints = load_endpoints_source(args.input)
        source_desc = "endpoint list: %s" % args.input

    results = []
    for ep in endpoints:
        clazz, evidence, entry, matched = classify(ep, allowlist)
        results.append({
            "dst": ep["dst"], "port": ep["port"], "proto": ep.get("proto"),
            "packets": ep.get("packets", 0), "bytes": ep.get("bytes", 0),
            "names": ep.get("names", []),
            "classification": clazz,
            "evidence": evidence,
            "allowed_by": entry,
            "matched_name": matched,
        })

    unexpected = [r for r in results if r["classification"] == "UNEXPECTED"]
    counts = {}
    for r in results:
        counts[r["classification"]] = counts.get(r["classification"], 0) + 1
    if unexpected:
        verdict = "UNEXPECTED EGRESS: %d destination(s) not covered by the allowlist" % len(unexpected)
        code = 2
    else:
        verdict = "NO UNEXPECTED EGRESS: %d destination(s) all allowlisted/loopback/fixture" % len(results)
        code = 0

    report = {
        "generated_at": utcnow(),
        "tool": "scripts/verify_egress.py",
        "tool_version": TOOL_VERSION,
        "purpose": "no-unexpected-egress check; 'tor' here means TorBox (debrid), NOT the Tor network",
        "source": {
            "kind": source["kind"],
            "description": source_desc,
            "path": source.get("path"),
            "sha256": source.get("sha256"),
            "bytes": source.get("bytes"),
            "decoded_summary": source.get("summary"),
        },
        "allowlist": allowlist,
        "fixture": "%s:%d" % (FIXTURE_HOST, FIXTURE_PORT),
        "endpoint_count": len(results),
        "counts": counts,
        "endpoints": sorted(results, key=lambda r: (r["classification"] != "UNEXPECTED",
                                                    r["dst"], r["port"] or 0)),
        "unexpected": [{"dst": r["dst"], "port": r["port"], "proto": r["proto"],
                        "names": [n["name"] for n in r["names"]],
                        "reason": r["evidence"].get("reason")} for r in unexpected],
        "verdict": verdict,
        "exit_code": code,
    }
    out = args.out
    if not out:
        base = os.path.splitext((args.capture or args.flows or "endpoints"))[0]
        out = base + ".egress.json"
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)

    if not args.quiet:
        print("source    : %s" % source_desc)
        if source.get("sha256"):
            print("sha256    : %s" % source["sha256"])
        print("allowlist : %s" % ", ".join(allowlist))
        print("fixture   : %s:%d (allowed)" % (FIXTURE_HOST, FIXTURE_PORT))
        print("%-18s %-5s %-6s %-11s %s" % ("DEST", "PORT", "PROTO", "CLASS", "NAMES / NOTE"))
        for r in report["endpoints"]:
            names = ",".join(sorted({n["name"] for n in r["names"]})) or "-"
            note = r["evidence"].get("reason", "")
            print("%-18s %-5s %-6s %-11s %s | %s" % (
                r["dst"], r["port"], r["proto"] or "-", r["classification"], names, note))
        print("counts    : %s" % json.dumps(counts, sort_keys=True))
        print("VERDICT   : %s" % verdict)
        print("wrote     : %s" % os.path.abspath(out))
    return code


if __name__ == "__main__":
    sys.exit(main())
