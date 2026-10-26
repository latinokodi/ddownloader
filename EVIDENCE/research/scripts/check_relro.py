#!/usr/bin/env python3
"""Check GNU_RELRO 16 KB compliance for libtor.so inside a tor-android AAR.

Android docs (developer.android.com/guide/practices/page-sizes) warn that even when
PT_LOAD p_align == 0x4000, a GNU_RELRO whose (p_vaddr + p_memsz) is not 16 KB-aligned
can still SIGSEGV on 16 KB devices. Rule: (p_vaddr + p_memsz) % 0x4000 == 0, OR the
RELRO region is the whole content of its containing PT_LOAD with the next writable
LOAD starting on a 16 KB boundary.

stdlib only.  Usage: python check_relro.py <aar>
"""
import hashlib
import json
import struct
import sys
import zipfile

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def phdrs64(buf):
    (e_phoff,) = struct.unpack_from("<Q", buf, 0x20)
    (e_phentsize,) = struct.unpack_from("<H", buf, 0x36)
    (e_phnum,) = struct.unpack_from("<H", buf, 0x38)
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        t, fl, o, va, pa, fsz, msz, al = struct.unpack_from("<IIQQQQQQ", buf, off)
        out.append(dict(i=i, type=t, flags=fl, off=o, vaddr=va, filesz=fsz, memsz=msz, align=al))
    return out


def phdrs32(buf):
    (e_phoff,) = struct.unpack_from("<I", buf, 0x1C)
    (e_phentsize,) = struct.unpack_from("<H", buf, 0x2A)
    (e_phnum,) = struct.unpack_from("<H", buf, 0x2C)
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        t, o, va, pa, fsz, msz, fl, al = struct.unpack_from("<IIIIIIII", buf, off)
        out.append(dict(i=i, type=t, flags=fl, off=o, vaddr=va, filesz=fsz, memsz=msz, align=al))
    return out


def main():
    aar = sys.argv[1]
    report = {"aar": aar, "abis": {}}
    with zipfile.ZipFile(aar) as z:
        for n in sorted(x for x in z.namelist() if x.startswith("jni/") and x.endswith("libtor.so")):
            abi = n.split("/")[1]
            buf = z.read(n)
            is64 = buf[4] == 2
            ph = phdrs64(buf) if is64 else phdrs32(buf)
            loads = [p for p in ph if p["type"] == PT_LOAD]
            relro = [p for p in ph if p["type"] == PT_GNU_RELRO]
            rec = {"elf_class": 64 if is64 else 32, "loads": [], "relro": []}
            for r in relro:
                end = r["vaddr"] + r["memsz"]
                mod = end % 0x4000
                # containing LOAD segment
                cont = None
                for l in loads:
                    if l["vaddr"] <= r["vaddr"] < l["vaddr"] + l["memsz"]:
                        cont = l
                        break
                rec["relro"].append(
                    {
                        "vaddr": hex(r["vaddr"]),
                        "memsz": hex(r["memsz"]),
                        "end": hex(end),
                        "end_mod_0x4000": hex(mod),
                        "relro_16kb_rule_ok": mod == 0,
                        "containing_load_align": hex(cont["align"]) if cont else None,
                        "containing_load_vaddr": hex(cont["vaddr"]) if cont else None,
                        "containing_load_memsz": hex(cont["memsz"]) if cont else None,
                    }
                )
            rec["relro_present"] = bool(relro)
            # Full documented rule (developer.android.com/guide/practices/page-sizes):
            #  A) (p_vaddr + p_memsz) % 0x4000 == 0  -> aligned; OR
            #  B) GNU_RELRO is the ENTIRE content of its containing PT_LOAD and the next
            #     writable PT_LOAD starts on a separate 16 KB page boundary.
            def rule(l64):
                end = l64["vaddr"] + l64["memsz"]
                if end % 0x4000 == 0:
                    return True, "A: (vaddr+memsz) is 16 KB aligned"
                cont = [p for p in loads if p["vaddr"] <= l64["vaddr"] < p["vaddr"] + p["memsz"]]
                if not cont:
                    return False, "no containing LOAD"
                c = cont[0]
                is_own_load = c["vaddr"] == l64["vaddr"] and c["memsz"] == l64["memsz"]
                if not is_own_load:
                    return False, "B fails: RELRO shares LOAD with later writable data"
                page_lo = end // 0x4000 * 0x4000
                page_hi = page_lo + 0x4000
                overlap = [p for p in loads if p is not c and p["flags"] & 2
                           and p["vaddr"] < page_hi and p["vaddr"] + p["memsz"] > page_lo]
                if overlap:
                    return False, "B fails: writable LOAD overlaps RELRO-end 16 KB page"
                return True, "B: RELRO is its own LOAD; next writable LOAD starts on a separate 16 KB page"
            ok, why = rule(relro[0]) if relro else (True, "no GNU_RELRO")
            rec["compliant"] = ok
            rec["reason"] = why
            for x in rec["relro"]:
                x["conclusion"] = why
            report["abis"][abi] = rec
    print(json.dumps(report, indent=2))
    for abi, r in report["abis"].items():
        print(f"{abi}: elf{r['elf_class']} relro_present={r['relro_present']} compliant={r['compliant']}")
        for x in r["relro"]:
            print(f"    RELRO vaddr={x['vaddr']} memsz={x['memsz']} end%0x4000={x['end_mod_0x4000']} ok={x['relro_16kb_rule_ok']} load_align={x['containing_load_align']}")
    with open(aar + ".relro-scan.json", "w") as f:
        json.dump(report, f, indent=2)


if __name__ == "__main__":
    main()
