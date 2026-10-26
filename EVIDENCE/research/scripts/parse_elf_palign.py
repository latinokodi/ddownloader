#!/usr/bin/env python3
"""Parse ELF64 PT_LOAD p_align values directly from jni/<abi>/libtor.so inside a tor-android AAR.

No external dependencies: zipfile + struct from the stdlib.
Usage: python parse_elf_palign.py <path-to.aar>
"""
import json
import struct
import sys
import zipfile

PT_LOAD = 1
PT_DYNAMIC = 2
PT_GNU_RELRO = 0x6474E552


def parse_elf64(buf, name):
    if buf[:4] != b"\x7fELF":
        return {"name": name, "error": "not ELF"}
    ei_class = buf[4]
    ei_data = buf[5]
    if ei_class != 2:
        return {"name": name, "error": f"not ELF64 (ei_class={ei_class})"}
    if ei_data != 1:
        return {"name": name, "error": "not little-endian"}
    e_type, e_machine = struct.unpack_from("<HH", buf, 0x10)
    (e_phoff,) = struct.unpack_from("<Q", buf, 0x20)
    (e_phentsize,) = struct.unpack_from("<H", buf, 0x36)
    (e_phnum,) = struct.unpack_from("<H", buf, 0x38)
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", buf, off
        )
        if p_type == PT_LOAD:
            loads.append(
                {
                    "index": i,
                    "p_offset": p_offset,
                    "p_vaddr": p_vaddr,
                    "p_filesz": p_filesz,
                    "p_memsz": p_memsz,
                    "p_align": p_align,
                    "p_align_hex": hex(p_align),
                    "p_flags": p_flags,
                    "congruent_off_vaddr": (p_offset % p_align) == (p_vaddr % p_align) if p_align else None,
                }
            )
    aligns = [l["p_align"] for l in loads]
    return {
        "name": name,
        "e_type": e_type,
        "e_machine": e_machine,
        "e_phnum": e_phnum,
        "e_phentsize": e_phentsize,
        "load_segments": loads,
        "pt_load_count": len(loads),
        "min_p_align": min(aligns) if aligns else None,
        "min_p_align_hex": hex(min(aligns)) if aligns else None,
        "all_loads_16kb_aligned": all(a >= 0x4000 for a in aligns) if aligns else None,
        "all_congruent": all(l["congruent_off_vaddr"] for l in loads),
        "verdict_16kb": (
            "PASS: every PT_LOAD p_align >= 0x4000 (16 KiB)"
            if aligns and all(a >= 0x4000 for a in aligns)
            else "FAIL: at least one PT_LOAD p_align < 0x4000 (16 KiB)"
        ),
    }


def parse_elf32(buf, name):
    """ELF32 (32-bit ABIs: armeabi-v7a, x86). 16 KB page-size Play rule applies to
    64-bit ABIs, but the raw p_align is still reported for completeness."""
    if buf[:4] != b"\x7fELF":
        return {"name": name, "error": "not ELF"}
    ei_class = buf[4]
    ei_data = buf[5]
    if ei_class != 1:
        return {"name": name, "error": f"not ELF32 (ei_class={ei_class})"}
    if ei_data != 1:
        return {"name": name, "error": "not little-endian"}
    e_type, e_machine = struct.unpack_from("<HH", buf, 0x10)
    (e_phoff,) = struct.unpack_from("<I", buf, 0x1C)
    (e_phentsize,) = struct.unpack_from("<H", buf, 0x2A)
    (e_phnum,) = struct.unpack_from("<H", buf, 0x2C)
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = struct.unpack_from(
            "<IIIIIIII", buf, off
        )
        if p_type == PT_LOAD:
            loads.append(
                {
                    "index": i,
                    "p_offset": p_offset,
                    "p_vaddr": p_vaddr,
                    "p_filesz": p_filesz,
                    "p_memsz": p_memsz,
                    "p_align": p_align,
                    "p_align_hex": hex(p_align),
                    "p_flags": p_flags,
                    "congruent_off_vaddr": (p_offset % p_align) == (p_vaddr % p_align) if p_align else None,
                }
            )
    aligns = [l["p_align"] for l in loads]
    return {
        "name": name,
        "elf_class": 32,
        "e_type": e_type,
        "e_machine": e_machine,
        "e_phnum": e_phnum,
        "e_phentsize": e_phentsize,
        "load_segments": loads,
        "pt_load_count": len(loads),
        "min_p_align": min(aligns) if aligns else None,
        "min_p_align_hex": hex(min(aligns)) if aligns else None,
        "all_loads_16kb_aligned": all(a >= 0x4000 for a in aligns) if aligns else None,
        "all_congruent": all(l["congruent_off_vaddr"] for l in loads),
        "note": "32-bit ABI: Android/Play 16 KB page-size requirement targets 64-bit ABIs.",
    }


def main():
    aar = sys.argv[1]
    out = {"aar_path": aar, "aar_sha256": None, "abis": {}}
    import hashlib

    with open(aar, "rb") as f:
        out["aar_sha256"] = hashlib.sha256(f.read()).hexdigest()
        out["aar_size_bytes"] = __import__("os").path.getsize(aar)
    with zipfile.ZipFile(aar) as z:
        names = [n for n in z.namelist() if n.startswith("jni/") and n.endswith("libtor.so")]
        for n in sorted(names):
            abi = n.split("/")[1]
            buf = z.read(n)
            rec = parse_elf64(buf, n) if buf[4] == 2 else parse_elf32(buf, n)
            rec["libtor_size_bytes"] = len(buf)
            rec["libtor_sha256"] = hashlib.sha256(buf).hexdigest()
            out["abis"][abi] = rec
    print(json.dumps(out, indent=2))
    with open(aar + ".elf-scan.json", "w") as f:
        json.dump(out, f, indent=2)
    for abi, rec in out["abis"].items():
        if "error" in rec:
            print(f"{abi}: ERROR {rec['error']}")
            continue
        print(
            f"{abi}: PT_LOAD={rec['pt_load_count']} min_p_align={rec['min_p_align_hex']} "
            f"({rec['min_p_align']}) 16k_all={rec['all_loads_16kb_aligned']} congruent={rec['all_congruent']} "
            f"size={rec['libtor_size_bytes']}"
        )
        for l in rec["load_segments"]:
            print(f"    seg{l['index']:>2} off={l['p_offset']:#x} vaddr={l['p_vaddr']:#x} align={l['p_align_hex']}")


if __name__ == "__main__":
    main()
