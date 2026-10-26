#!/usr/bin/env python3
"""Dump section + program header layout around GNU_RELRO for one ELF inside the AAR.

Purpose: decide whether any *writable* content (.data/.bss/.got) shares a 16 KB page
with the GNU_RELRO range, which is the condition Android documents as a SIGSEGV on
16 KB-page devices. stdlib only.

Usage: python inspect_relro_layout.py <aar> [abi]
"""
import json
import struct
import sys
import zipfile

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
SHT_NOBITS = 8
SHF_WRITE = 1


def main():
    aar = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else "arm64-v8a"
    with zipfile.ZipFile(aar) as z:
        name = f"jni/{want}/libtor.so"
        buf = z.read(name)
    struct.pack
    (e_shoff,) = struct.unpack_from("<Q", buf, 0x28)
    (e_shentsize,) = struct.unpack_from("<H", buf, 0x3A)
    (e_shnum,) = struct.unpack_from("<H", buf, 0x3C)
    (e_phoff,) = struct.unpack_from("<Q", buf, 0x20)
    (e_phentsize,) = struct.unpack_from("<H", buf, 0x36)
    (e_phnum,) = struct.unpack_from("<H", buf, 0x38)
    (e_shstrndx,) = struct.unpack_from("<H", buf, 0x3E)

    # shstrtab
    sh_str_off = e_shoff + e_shstrndx * e_shentsize
    (shstr_off,) = struct.unpack_from("<Q", buf, sh_str_off + 0x18)
    (shstr_size,) = struct.unpack_from("<Q", buf, sh_str_off + 0x20)
    shstr = buf[shstr_off : shstr_off + shstr_size]

    def sname(off):
        end = shstr.find(b"\x00", off)
        return shstr[off:end].decode("utf-8", "replace")

    sections = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        nm, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from(
            "<IIQQQQIIQQ", buf, o
        )
        sections.append(
            dict(name=sname(nm), type=typ, flags=flags, addr=addr, off=off, size=size,
                 align=align, write=bool(flags & SHF_WRITE), nobits=(typ == SHT_NOBITS))
        )

    phdrs = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        t, fl, po, va, pa, fsz, msz, al = struct.unpack_from("<IIQQQQQQ", buf, o)
        phdrs.append(dict(i=i, type=t, flags=fl, off=po, vaddr=va, filesz=fsz, memsz=msz, align=al))

    relro = [p for p in phdrs if p["type"] == PT_GNU_RELRO][0]
    r_start, r_end = relro["vaddr"], relro["vaddr"] + relro["memsz"]
    loads = [p for p in phdrs if p["type"] == PT_LOAD]
    cont = [l for l in loads if l["vaddr"] <= relro["vaddr"] < l["vaddr"] + l["memsz"]][0]

    writable = [s for s in sections if s["write"] and s["addr"] and s["name"]]
    writable.sort(key=lambda s: s["addr"])
    # sections overlapping or after the RELRO end
    after = [s for s in writable if s["addr"] + s["size"] > r_end]
    page_of_end = r_end // 0x4000 * 0x4000
    # writable bytes inside the 16 KB page that contains RELRO end
    in_page = []
    for s in writable:
        lo = max(s["addr"], page_of_end)
        hi = min(s["addr"] + s["size"], page_of_end + 0x4000)
        if hi > lo:
            in_page.append(dict(name=s["name"], overlap_start=hex(lo), overlap_end=hex(hi),
                                overlap_bytes=hi - lo))

    print(f"=== {name} ===")
    print(f"PT_GNU_RELRO: vaddr={r_start:#x} memsz={relro['memsz']:#x} end={r_end:#x} end%0x4000={r_end % 0x4000:#x}")
    print(f"containing PT_LOAD: vaddr={cont['vaddr']:#x} filesz={cont['filesz']:#x} memsz={cont['memsz']:#x} "
          f"align={cont['align']:#x} ends={cont['vaddr'] + cont['memsz']:#x} flags={cont['flags']}")
    print(f"containing LOAD == RELRO range only? {cont['vaddr'] == r_start and cont['memsz'] == relro['memsz']}")
    print(f"8 KB page of RELRO end: [{page_of_end:#x}, {page_of_end + 0x4000:#x})")
    print("writable sections AFTER relro end:")
    for s in after[:12]:
        print(f"  {s['name']:<20} addr={s['addr']:#x} size={s['size']:#x} nobits={s['nobits']} "
              f"first_page={s['addr'] // 0x4000 * 0x4000:#x}")
    print("writable bytes inside the 16 KB page holding RELRO end:")
    for s in in_page:
        print(f"  {s['name']}: {s['overlap_start']}..{s['overlap_end']} ({s['overlap_bytes']} bytes)")
    print(f"VERDICT: writable data shares RELRO-end page? {bool(in_page)}")
    json.dump(dict(relro=relro, containing_load=cont, writable_in_end_page=in_page,
                   writable_after=after[:20], page_of_end=hex(page_of_end)),
              open(aar + f".relro-layout-{want}.json", "w"), indent=2)


if __name__ == "__main__":
    main()
