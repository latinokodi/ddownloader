#!/usr/bin/env python3
"""
pcap_parse.py -- dependency-free packet-capture parser and flow extractor.

Owner: env-runner. Python 3.9+ standard library only (no tshark, no scapy).

WHAT IT DOES
  * Reads classic pcap (little/big endian, microsecond/nanosecond) and pcapng.
  * Decodes link layers: Ethernet (incl. one 802.1Q VLAN tag), Linux cooked SLL v1
    (linktype 113, what `tcpdump -i any` produces on Android), SLL2 (276),
    DLT_RAW (12/101), and explicit LINKTYPE_IPV4/IPV6 (228/229).
  * Decodes IPv4 (incl. fragment handling) and IPv6 (extension-header walk),
    then TCP and UDP.
  * Extracts, per packet: timestamp, src, dst, sport, dport, proto, payload bytes,
    frame bytes, TCP flags. Optionally TLS ClientHello SNI, DNS query names and
    DNS A/AAAA answers, and the first line of HTTP requests/responses.
  * Aggregates: unidirectional 5-tuple flows, per-destination totals, DNS map,
    TLS SNI list, HTTP request list.

OUTPUT
  Writes <pcap-basename>.flows.json next to the pcap (or --out PATH) and prints a
  short human summary.

USAGE
  python scripts/pcap_parse.py EVIDENCE/captures/guest-traffic-2026-10-26.pcap
  python scripts/pcap_parse.py cap.pcap --out cap.flows.json --max-packets 5000
"""

import argparse
import hashlib
import ipaddress
import json
import os
import struct
import sys
from collections import OrderedDict, defaultdict
from datetime import datetime, timezone

PARSER_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# pcap / pcapng container readers
# ---------------------------------------------------------------------------

PCAP_MAGIC = {
    b"\xd4\xc3\xb2\xa1": ("<", False),   # little endian, microseconds
    b"\xa1\xb2\xc3\xd4": (">", False),   # big endian, microseconds
    b"\x4d\x3c\xb2\xa1": ("<", True),    # little endian, nanoseconds
    b"\xa1\xb2\x3c\x4d": (">", True),    # big endian, nanoseconds
}
PCAPNG_MAGIC = b"\x0a\x0d\x0d\x0a"


def iter_pcap(data):
    """Yield (ts_seconds_float, linktype, packet_bytes) from classic pcap."""
    magic = data[:4]
    endian, nsec = PCAP_MAGIC[magic]
    (version_major, version_minor, thiszone, sigfigs, snaplen, linktype) = struct.unpack(
        endian + "HHiIII", data[4:24]
    )
    off = 24
    n = len(data)
    while off + 16 <= n:
        ts_sec, ts_frac, incl_len, orig_len = struct.unpack(endian + "IIII", data[off:off + 16])
        off += 16
        if incl_len > n - off:
            # truncated final record: stop rather than emit garbage
            break
        ts = ts_sec + (ts_frac / 1e9 if nsec else ts_frac / 1e6)
        yield ts, linktype, data[off:off + incl_len], orig_len
        off += incl_len


def iter_pcapng(data):
    """Yield (ts_seconds_float, linktype, packet_bytes) from pcapng."""
    off = 0
    n = len(data)
    endian = "<"
    interfaces = []          # list of (linktype, tsresol_seconds)
    while off + 12 <= n:
        if data[off:off + 4] == PCAPNG_MAGIC:
            # Section Header Block: byte-order magic at +8
            bom = data[off + 8:off + 12]
            if bom == b"\x1a\x2b\x3c\x4d":
                endian = "<"
            elif bom == b"\x4d\x3c\x2b\x1a":
                endian = ">"
            else:
                raise ValueError("pcapng: bad byte-order magic")
            block_len = struct.unpack(endian + "I", data[off + 4:off + 8])[0]
            interfaces = []
            off += block_len
            continue
        block_type, block_len = struct.unpack(endian + "II", data[off:off + 8])
        if block_len < 12 or off + block_len > n:
            break
        body = data[off + 8:off + block_len - 4]
        if block_type == 1:  # Interface Description Block
            linktype = struct.unpack(endian + "H", body[0:2])[0]
            tsresol = 1e-6
            # options start after 8 bytes (linktype, reserved, snaplen)
            opt_off = 8
            while opt_off + 4 <= len(body):
                code, olen = struct.unpack(endian + "HH", body[opt_off:opt_off + 4])
                if code == 0:
                    break
                val = body[opt_off + 4:opt_off + 4 + olen]
                if code == 9 and len(val) == 1:  # if_tsresol
                    exp = val[0] & 0x7F
                    tsresol = (2.0 ** -exp) if (val[0] & 0x80) else (10.0 ** -exp)
                opt_off += 4 + ((olen + 3) & ~3)
            interfaces.append((linktype, tsresol))
        elif block_type == 6:  # Enhanced Packet Block
            iface_id, ts_high, ts_low, cap_len, orig_len = struct.unpack(
                endian + "IIIII", body[0:20]
            )
            ts_units = (ts_high << 32) | ts_low
            linktype, tsresol = interfaces[iface_id] if iface_id < len(interfaces) else (1, 1e-6)
            yield ts_units * tsresol, linktype, body[20:20 + cap_len], orig_len
        off += block_len


def read_capture(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:4] == PCAPNG_MAGIC:
        return "pcapng", list(iter_pcapng(data))
    if data[:4] in PCAP_MAGIC:
        return "pcap", list(iter_pcap(data))
    raise ValueError("not a pcap/pcapng file (magic=%r)" % data[:4])


# ---------------------------------------------------------------------------
# Protocol decoding
# ---------------------------------------------------------------------------

def _mac(raw):
    return ":".join("%02x" % b for b in raw)


def decode_link(linktype, pkt):
    """Return (network_proto, l3_payload) or (None, b''). network_proto is 'ipv4'/'ipv6'."""
    try:
        if linktype == 1:  # Ethernet
            if len(pkt) < 14:
                return None, b""
            ethertype = struct.unpack(">H", pkt[12:14])[0]
            off = 14
            while ethertype == 0x8100 and len(pkt) >= off + 4:  # 802.1Q
                ethertype = struct.unpack(">H", pkt[off + 2:off + 4])[0]
                off += 4
            if ethertype == 0x0800:
                return "ipv4", pkt[off:]
            if ethertype == 0x86DD:
                return "ipv6", pkt[off:]
            return None, b""
        if linktype == 113:  # LINUX_SLL (tcpdump -i any on Android)
            if len(pkt) < 16:
                return None, b""
            proto = struct.unpack(">H", pkt[14:16])[0]
            if proto == 0x0800:
                return "ipv4", pkt[16:]
            if proto == 0x86DD:
                return "ipv6", pkt[16:]
            return None, b""
        if linktype == 276:  # LINUX_SLL2
            if len(pkt) < 20:
                return None, b""
            proto = struct.unpack(">H", pkt[0:2])[0]
            if proto == 0x0800:
                return "ipv4", pkt[20:]
            if proto == 0x86DD:
                return "ipv6", pkt[20:]
            return None, b""
        if linktype in (12, 101):  # DLT_RAW: starts with IP version nibble
            if not pkt:
                return None, b""
            ver = pkt[0] >> 4
            if ver == 4:
                return "ipv4", pkt
            if ver == 6:
                return "ipv6", pkt
            return None, b""
        if linktype == 228:
            return "ipv4", pkt
        if linktype == 229:
            return "ipv6", pkt
        return None, b""
    except (struct.error, IndexError):
        return None, b""


IPV6_EXT_HEADERS = {0, 43, 44, 60, 51, 50}


def decode_ipv4(pkt):
    """Return dict(proto, src, dst, payload) or None."""
    try:
        ver_ihl = pkt[0]
        if ver_ihl >> 4 != 4:
            return None
        ihl = (ver_ihl & 0x0F) * 4
        if ihl < 20 or len(pkt) < ihl:
            return None
        total_len = struct.unpack(">H", pkt[2:4])[0]
        proto = pkt[9]
        frag = struct.unpack(">H", pkt[6:8])[0]
        src = str(ipaddress.IPv4Address(pkt[12:16]))
        dst = str(ipaddress.IPv4Address(pkt[16:20]))
        payload = pkt[ihl:total_len] if 0 < total_len <= len(pkt) else pkt[ihl:]
        frag_offset = frag & 0x1FFF
        more_fragments = bool(frag & 0x2000)
        return {
            "proto": proto, "src": src, "dst": dst, "payload": payload,
            "frag_offset": frag_offset, "more_fragments": more_fragments,
        }
    except (struct.error, IndexError, ValueError):
        return None


def decode_ipv6(pkt):
    try:
        if not pkt or pkt[0] >> 4 != 6:
            return None
        payload_len = struct.unpack(">H", pkt[4:6])[0]
        next_header = pkt[6]
        src = str(ipaddress.IPv6Address(pkt[8:24]))
        dst = str(ipaddress.IPv6Address(pkt[24:40]))
        off = 40
        frag_offset = 0
        while next_header in IPV6_EXT_HEADERS and off + 2 <= len(pkt):
            if next_header == 44:  # fragment header
                frag_off = struct.unpack(">H", pkt[off + 2:off + 4])[0] >> 3
                frag_offset = frag_off
                next_header = pkt[off]
                off += 8
            else:
                hdr_len = (pkt[off + 1] + 1) * 8
                next_header = pkt[off]
                off += hdr_len
        end = min(len(pkt), 40 + payload_len)
        return {
            "proto": next_header, "src": src, "dst": dst, "payload": pkt[off:end],
            "frag_offset": frag_offset, "more_fragments": False,
        }
    except (struct.error, IndexError, ValueError):
        return None


TCP_FLAG_NAMES = [(0x01, "FIN"), (0x02, "SYN"), (0x04, "RST"), (0x08, "PSH"),
                  (0x10, "ACK"), (0x20, "URG"), (0x40, "ECE"), (0x80, "CWR")]


def decode_tcp(payload):
    try:
        if len(payload) < 20:
            return None
        sport, dport, seq, ack = struct.unpack(">HHII", payload[0:12])
        data_off = (payload[12] >> 4) * 4
        flags = payload[13]
        if data_off < 20 or data_off > len(payload):
            data_off = max(20, min(data_off, len(payload)))
        l7 = payload[data_off:]
        return {
            "sport": sport, "dport": dport, "seq": seq, "ack": ack,
            "flags": [name for bit, name in TCP_FLAG_NAMES if flags & bit],
            "flags_hex": flags, "l7": l7,
        }
    except (struct.error, IndexError):
        return None


def decode_udp(payload):
    try:
        if len(payload) < 8:
            return None
        sport, dport, length, _checksum = struct.unpack(">HHHH", payload[0:8])
        return {"sport": sport, "dport": dport, "l7": payload[8:length] if 8 <= length <= len(payload) else payload[8:]}
    except (struct.error, IndexError):
        return None


# ---------------------------------------------------------------------------
# TLS ClientHello SNI
# ---------------------------------------------------------------------------

def tls_sni(tcp_payload):
    """Extract SNI from a TLS ClientHello record, or None. Pure byte parsing."""
    try:
        p = tcp_payload
        if len(p) < 6 or p[0] != 0x16:          # handshake record
            return None
        if p[1] != 0x03:                         # TLS version major
            return None
        rec_len = (p[3] << 8) | p[4]
        if rec_len + 5 > len(p):
            return None
        hs = p[5:5 + rec_len]
        if not hs or hs[0] != 0x01:              # ClientHello
            return None
        i = 4                                    # skip handshake type + 3-byte length
        i += 2 + 32                              # client_version + random
        if i >= len(hs):
            return None
        sid_len = hs[i]
        i += 1 + sid_len
        cs_len = (hs[i] << 8) | hs[i + 1]
        i += 2 + cs_len
        comp_len = hs[i]
        i += 1 + comp_len
        if i + 2 > len(hs):
            return None
        ext_total = (hs[i] << 8) | hs[i + 1]
        i += 2
        end = min(len(hs), i + ext_total)
        while i + 4 <= end:
            ext_type = (hs[i] << 8) | hs[i + 1]
            ext_len = (hs[i + 2] << 8) | hs[i + 3]
            i += 4
            ext = hs[i:i + ext_len]
            i += ext_len
            if ext_type == 0x0000 and len(ext) >= 5:  # server_name
                # server_name_list(2) + name_type(1) + name_len(2) + name
                name_len = (ext[3] << 8) | ext[4]
                name = ext[5:5 + name_len]
                try:
                    return name.decode("idna")
                except (UnicodeDecodeError, UnicodeError):
                    return name.decode("ascii", "replace")
        return None
    except (IndexError, struct.error):
        return None


# ---------------------------------------------------------------------------
# DNS
# ---------------------------------------------------------------------------

DNS_TYPES = {1: "A", 2: "NS", 5: "CNAME", 6: "SOA", 12: "PTR", 15: "MX",
             16: "TXT", 28: "AAAA", 33: "SRV", 35: "NAPTR", 43: "DS",
             46: "RRSIG", 47: "NSEC", 48: "DNSKEY", 64: "SVCB", 65: "HTTPS"}


def _dns_name(buf, off):
    labels = []
    jumps = 0
    while off < len(buf):
        ln = buf[off]
        if ln == 0:
            off += 1
            break
        if ln & 0xC0 == 0xC0:  # compression pointer
            if off + 1 >= len(buf):
                return None, off + 2
            ptr = ((ln & 0x3F) << 8) | buf[off + 1]
            if jumps > 20:
                return None, off + 2
            sub, _ = _dns_name(buf, ptr)
            if sub:
                labels.append(sub)
            return ".".join(labels) if labels else None, off + 2
        if off + 1 + ln > len(buf):
            return None, off + 1 + ln
        labels.append(buf[off + 1:off + 1 + ln].decode("ascii", "replace"))
        off += 1 + ln
    return (".".join(labels) if labels else None), off


def parse_dns(payload, is_response):
    """Return dict(queries=[(name,qtype)], answers=[(name,type,value)]) or None."""
    try:
        if len(payload) < 12:
            return None
        _tid, flags, qd, an, _ns, _ar = struct.unpack(">HHHHHH", payload[0:12])
        off = 12
        queries, answers = [], []
        for _ in range(qd):
            name, off = _dns_name(payload, off)
            if name is None or off + 4 > len(payload):
                break
            qtype, _qclass = struct.unpack(">HH", payload[off:off + 4])
            off += 4
            queries.append((name, DNS_TYPES.get(qtype, str(qtype))))
        for _ in range(an):
            name, off = _dns_name(payload, off)
            if name is None or off + 10 > len(payload):
                break
            rtype, _rclass, _ttl, rdlen = struct.unpack(">HHIH", payload[off:off + 10])
            off += 10
            rdata = payload[off:off + rdlen]
            off += rdlen
            value = None
            if rtype == 1 and rdlen == 4:
                value = str(ipaddress.IPv4Address(rdata))
            elif rtype == 28 and rdlen == 16:
                value = str(ipaddress.IPv6Address(rdata))
            elif rtype in (5, 2, 12):
                value, _ = _dns_name(payload, off - rdlen)
            answers.append((name, DNS_TYPES.get(rtype, str(rtype)), value))
        return {"queries": queries, "answers": answers, "is_response": is_response}
    except (struct.error, IndexError, ValueError):
        return None


# ---------------------------------------------------------------------------
# HTTP first line
# ---------------------------------------------------------------------------

def http_first_line(tcp_payload):
    try:
        if len(tcp_payload) < 8:
            return None
        text = tcp_payload[:2048].decode("latin-1", "replace")
        first, _, rest = text.partition("\r\n")
        if first.startswith(("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ")):
            parts = first.split(" ")
            host = ""
            for line in rest.split("\r\n"):
                if line.lower().startswith("host:"):
                    host = line.split(":", 1)[1].strip()
                    break
            return {"direction": "request", "method": parts[0],
                    "target": parts[1] if len(parts) > 1 else "", "host": host,
                    "line": first}
        if first.startswith("HTTP/"):
            parts = first.split(" ", 2)
            return {"direction": "response", "version": parts[0],
                    "status": parts[1] if len(parts) > 1 else "", "line": first}
        return None
    except (IndexError, UnicodeDecodeError):
        return None


# ---------------------------------------------------------------------------
# Main extraction
# ---------------------------------------------------------------------------

def extract(path, max_packets=None):
    fmt, recs = read_capture(path)
    packets = []
    flows = OrderedDict()
    dest = OrderedDict()
    dns_queries = []
    ip_to_name = {}
    sni_list = []
    http_list = []
    tls_buffers = OrderedDict()
    decode_errors = 0
    linktypes = set()

    for ts, linktype, pkt, orig_len in recs:
        if max_packets and len(packets) >= max_packets:
            break
        linktypes.add(linktype)
        net, l3 = decode_link(linktype, pkt)
        if net is None:
            decode_errors += 1
            continue
        ip = decode_ipv4(l3) if net == "ipv4" else decode_ipv6(l3)
        if ip is None:
            decode_errors += 1
            continue
        if ip["frag_offset"]:
            # non-first fragment: do not attribute L4 ports
            continue
        rec = {
            "ts": round(ts, 6),
            "src": ip["src"],
            "dst": ip["dst"],
            "sport": None,
            "dport": None,
            "proto": None,
            "bytes": 0,
            "frame_bytes": orig_len if orig_len else len(pkt),
            "ip_bytes": orig_len if orig_len else len(pkt),
        }
        l4 = None
        if ip["proto"] == 6:
            rec["proto"] = "TCP"
            l4 = decode_tcp(ip["payload"])
            if l4:
                rec["sport"] = l4["sport"]
                rec["dport"] = l4["dport"]
                rec["flags"] = l4["flags"]
                rec["bytes"] = len(l4["l7"])
                sni = tls_sni(l4["l7"])
                if sni:
                    rec["sni"] = sni
                    sni_list.append({"ts": rec["ts"], "dst": rec["dst"],
                                     "dport": rec["dport"], "sni": sni})
                http = http_first_line(l4["l7"])
                if http:
                    rec["http"] = http
                    http_list.append({"ts": rec["ts"], "src": rec["src"],
                                      "dst": rec["dst"], "sport": rec["sport"],
                                      "dport": rec["dport"], **http})
                # Buffer TLS-bearing streams: a ClientHello is routinely split
                # across TCP segments, so SNI must be parsed from the reassembled
                # stream rather than a single segment.
                if l4["l7"] and (rec["dport"] in (443, 8443) or rec["sport"] in (443, 8443)):
                    bkey = (rec["src"], rec["sport"], rec["dst"], rec["dport"])
                    buf = tls_buffers.get(bkey)
                    if buf is None:
                        buf = tls_buffers[bkey] = bytearray()
                    if len(buf) < 65536:
                        buf.extend(l4["l7"])
        elif ip["proto"] == 17:
            rec["proto"] = "UDP"
            l4 = decode_udp(ip["payload"])
            if l4:
                rec["sport"] = l4["sport"]
                rec["dport"] = l4["dport"]
                rec["bytes"] = len(l4["l7"])
                if 53 in (l4["sport"], l4["dport"]):
                    dns = parse_dns(l4["l7"], is_response=(l4["sport"] == 53))
                    if dns:
                        rec["dns"] = {"queries": dns["queries"],
                                      "answers": dns["answers"]}
                        for name, qtype in dns["queries"]:
                            dns_queries.append({"ts": rec["ts"], "name": name,
                                                "qtype": qtype,
                                                "server": "%s:%s" % (rec["dst"], rec["dport"])})
                        for name, rtype, value in dns["answers"]:
                            if value and rtype in ("A", "AAAA"):
                                ip_to_name.setdefault(value, name)
        elif ip["proto"] == 1:
            rec["proto"] = "ICMP"
        elif ip["proto"] == 58:
            rec["proto"] = "ICMPv6"
        else:
            rec["proto"] = "IP(%d)" % ip["proto"]

        packets.append(rec)

        key = (rec["proto"], rec["src"], rec["sport"], rec["dst"], rec["dport"])
        fl = flows.get(key)
        if fl is None:
            fl = flows[key] = {
                "proto": rec["proto"], "src": rec["src"], "sport": rec["sport"],
                "dst": rec["dst"], "dport": rec["dport"],
                "first_ts": rec["ts"], "last_ts": rec["ts"],
                "packets": 0, "bytes": 0, "frame_bytes": 0,
            }
        fl["packets"] += 1
        fl["bytes"] += rec["bytes"]
        fl["frame_bytes"] += rec["frame_bytes"]
        fl["last_ts"] = rec["ts"]
        if "sni" in rec:
            fl.setdefault("sni", rec["sni"])

        dkey = "%s:%s/%s" % (rec["dst"], rec["dport"], rec["proto"])
        d = dest.get(dkey)
        if d is None:
            d = dest[dkey] = {"dst": rec["dst"], "dport": rec["dport"],
                              "proto": rec["proto"], "packets": 0, "bytes": 0,
                              "first_ts": rec["ts"], "last_ts": rec["ts"]}
        d["packets"] += 1
        d["bytes"] += rec["bytes"]
        d["last_ts"] = rec["ts"]

    flow_list = sorted(flows.values(), key=lambda f: (-f["packets"], f["dst"], f["dport"] or 0))

    # Second pass: TLS SNI from reassembled TCP streams (ClientHello may span segments).
    for (bsrc, bsport, bdst, bdport), buf in tls_buffers.items():
        if len(buf) < 6 or buf[0] != 0x16:
            continue
        sni = tls_sni(bytes(buf))
        if not sni:
            continue
        if not any(s.get("sni") == sni and s.get("dst") == bdst and s.get("dport") == bdport for s in sni_list):
            sni_list.append({"ts": None, "src": bsrc, "sport": bsport, "dst": bdst,
                             "dport": bdport, "sni": sni,
                             "source": "reassembled-tcp-stream"})
        for f in flow_list:
            if (f["src"], f["sport"], f["dst"], f["dport"]) == (bsrc, bsport, bdst, bdport):
                f.setdefault("sni", sni)

    for f in flow_list:
        if f["dst"] in ip_to_name:
            f["dst_name"] = ip_to_name[f["dst"]]

    dest_list = sorted(dest.values(), key=lambda d: -d["bytes"])
    for d in dest_list:
        if d["dst"] in ip_to_name:
            d["dst_name"] = ip_to_name[d["dst"]]

    return {
        "parser": "scripts/pcap_parse.py",
        "parser_version": PARSER_VERSION,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
        "source_pcap": os.path.abspath(path),
        "source_sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
        "source_bytes": os.path.getsize(path),
        "capture_format": fmt,
        "linktypes": sorted(linktypes),
        "packet_count": len(packets),
        "decode_errors": decode_errors,
        "packets": packets,
        "flows": flow_list,
        "destinations": dest_list,
        "dns": {"queries": dns_queries, "ip_to_name": ip_to_name},
        "tls_sni": sni_list,
        "http": http_list,
        "summary": {
            "tcp_packets": sum(1 for p in packets if p["proto"] == "TCP"),
            "udp_packets": sum(1 for p in packets if p["proto"] == "UDP"),
            "unique_flows": len(flow_list),
            "unique_destinations": len(dest_list),
            "dns_query_count": len(dns_queries),
            "unique_dns_names": sorted({q["name"] for q in dns_queries if q["name"]}),
            "tls_sni_names": sorted({s["sni"] for s in sni_list}),
            "http_exchanges": len(http_list),
        },
    }


def main():
    ap = argparse.ArgumentParser(description="Dependency-free pcap/pcapng flow extractor")
    ap.add_argument("pcap", help="input .pcap/.pcapng")
    ap.add_argument("--out", help="output JSON (default: <pcap-basename>.flows.json)")
    ap.add_argument("--max-packets", type=int, default=None,
                    help="stop after N packets (for huge captures)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    result = extract(args.pcap, max_packets=args.max_packets)
    out = args.out
    if not out:
        base, _ext = os.path.splitext(args.pcap)
        out = base + ".flows.json"
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=2)

    if not args.quiet:
        s = result["summary"]
        print("capture      : %s (%s, %d bytes)" % (result["source_pcap"], result["capture_format"], result["source_bytes"]))
        print("sha256       : %s" % result["source_sha256"])
        print("linktypes    : %s" % result["linktypes"])
        print("packets      : %d (decode errors: %d)" % (result["packet_count"], result["decode_errors"]))
        print("flows        : %d   destinations: %d" % (s["unique_flows"], s["unique_destinations"]))
        print("dns names    : %s" % (", ".join(s["unique_dns_names"]) or "-"))
        print("tls sni      : %s" % (", ".join(s["tls_sni_names"]) or "-"))
        print("http         : %d" % s["http_exchanges"])
        print("--- top destinations by payload bytes ---")
        for d in result["destinations"][:15]:
            name = (" (%s)" % d["dst_name"]) if d.get("dst_name") else ""
            print("  %-15s :%-5s %-5s packets=%-5d bytes=%-8d%s" % (
                d["dst"], d["dport"], d["proto"], d["packets"], d["bytes"], name))
        print("wrote         : %s" % os.path.abspath(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
