#!/usr/bin/env python3
"""Minimal dependency-free PE inspector: imports, delay-imports, exports, version
resources and string extraction. Used as a fallback probe while the REA native
provider (Ghidra) is being provisioned.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from pathlib import Path


class PE:
    def __init__(self, data: bytes):
        self.d = data
        if data[:2] != b"MZ":
            raise ValueError("not MZ")
        e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
        if data[e_lfanew : e_lfanew + 4] != b"PE\0\0":
            raise ValueError("not PE")
        self.off = e_lfanew + 4
        machine, nsec, _, _, _, opt_size, chars = struct.unpack_from("<HHIIIHH", data, self.off)
        self.machine = machine
        self.nsec = nsec
        self.opt_off = self.off + 20
        self.opt_size = opt_size
        magic = struct.unpack_from("<H", data, self.opt_off)[0]
        self.pe32plus = magic == 0x20B
        if self.pe32plus:
            self.image_base = struct.unpack_from("<Q", data, self.opt_off + 24)[0]
            self.dd_off = self.opt_off + 112
        else:
            self.image_base = struct.unpack_from("<I", data, self.opt_off + 28)[0]
            self.dd_off = self.opt_off + 96
        self.ndirs = struct.unpack_from("<I", data, self.dd_off - 4)[0]
        self.dirs = []
        for i in range(min(self.ndirs, 16)):
            rva, size = struct.unpack_from("<II", data, self.dd_off + i * 8)
            self.dirs.append((rva, size))
        self.sec_off = self.opt_off + opt_size
        self.sections = []
        for i in range(nsec):
            base = self.sec_off + i * 40
            name = data[base : base + 8].rstrip(b"\0").decode("latin-1")
            vsize, vaddr, rawsize, rawptr = struct.unpack_from("<IIII", data, base + 8)
            self.sections.append(
                {"name": name, "vaddr": vaddr, "vsize": vsize, "rawsize": rawsize, "rawptr": rawptr}
            )

    def rva_to_off(self, rva: int) -> int | None:
        for s in self.sections:
            span = max(s["vsize"], s["rawsize"])
            if s["vaddr"] <= rva < s["vaddr"] + span:
                delta = rva - s["vaddr"]
                if delta >= s["rawsize"]:
                    return None
                return s["rawptr"] + delta
        return None

    def cstr(self, rva: int) -> str:
        off = self.rva_to_off(rva)
        if off is None:
            return ""
        end = self.d.find(b"\0", off)
        return self.d[off:end].decode("latin-1", "replace")

    def _thunks(self, rva: int):
        out = []
        off = self.rva_to_off(rva)
        if off is None:
            return out
        step = 8 if self.pe32plus else 4
        flag = 1 << (63 if self.pe32plus else 31)
        while True:
            val = struct.unpack_from("<Q" if self.pe32plus else "<I", self.d, off)[0]
            if val == 0:
                break
            if val & flag:
                out.append(f"#{val & 0xFFFF}")
            else:
                out.append(self.cstr((val & 0x7FFFFFFF) + 2))
            off += step
        return out

    def imports(self):
        res = {}
        if len(self.dirs) < 2 or not self.dirs[1][0]:
            return res
        off = self.rva_to_off(self.dirs[1][0])
        if off is None:
            return res
        while True:
            ilt, ts, fc, name_rva, iat = struct.unpack_from("<IIIII", self.d, off)
            if name_rva == 0:
                break
            dll = self.cstr(name_rva)
            res.setdefault(dll, [])
            for fn in self._thunks(ilt or iat):
                if fn not in res[dll]:
                    res[dll].append(fn)
            off += 20
        return res

    def delay_imports(self):
        res = {}
        if len(self.dirs) < 14 or not self.dirs[13][0]:
            return res
        off = self.rva_to_off(self.dirs[13][0])
        if off is None:
            return res
        while True:
            attrs, name_rva, hmod, iat, int_ = struct.unpack_from("<IIIII", self.d, off)
            if name_rva == 0 and iat == 0:
                break
            dll = self.cstr(name_rva)
            res.setdefault(dll, [])
            for fn in self._thunks(int_ or iat):
                if fn not in res[dll]:
                    res[dll].append(fn)
            off += 32
        return res

    def exports(self):
        res = []
        if len(self.dirs) < 1 or not self.dirs[0][0]:
            return res
        off = self.rva_to_off(self.dirs[0][0])
        if off is None:
            return res
        nfun, nname = struct.unpack_from("<II", self.d, off + 20)
        addr_rva, name_rva = struct.unpack_from("<II", self.d, off + 28)
        ao, no, oo = self.rva_to_off(addr_rva), self.rva_to_off(name_rva), None
        if no is None or ao is None:
            return res
        for i in range(nname):
            nr = struct.unpack_from("<I", self.d, no + i * 4)[0]
            ordinal = struct.unpack_from("<H", self.d, None) if False else None
            res.append(self.cstr(nr))
        return res


def strings(data: bytes, min_len: int = 5):
    out = []
    for m in re.finditer(rb"[\x20-\x7e]{%d,}" % min_len, data):
        out.append((m.start(), "a", m.group().decode("latin-1")))
    for m in re.finditer(rb"(?:[\x20-\x7e]\x00){%d,}" % min_len, data):
        out.append((m.start(), "w", m.group().decode("utf-16-le", "replace")))
    out.sort()
    return out


KEYWORDS = [
    "onnx", "embedded", "model", "vokab", "vocab", "config.json", "manifest",
    "translat", "recogni", "caption", "audio", "loopback", "sample", "16000",
    "language", "locale", "WaveFormat", "Speech", "segment", "stabil", "hypoth",
    "partial", "region", "offline", "model.json", "dictionary", "encoder", "decoder",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--imports", action="store_true")
    ap.add_argument("--exports", action="store_true")
    ap.add_argument("--strings", action="store_true")
    ap.add_argument("--grep", default=None, help="filter strings by regex")
    ap.add_argument("--min-len", type=int, default=6)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    p = Path(a.path)
    data = p.read_bytes()
    pe = PE(data)
    out = {"file": str(p), "size": len(data), "machine": hex(pe.machine), "sections": pe.sections}
    if a.imports:
        out["imports"] = pe.imports()
        out["delay_imports"] = pe.delay_imports()
    if a.exports:
        out["exports"] = pe.exports()
    if a.strings:
        s = strings(data, a.min_len)
        if a.grep:
            rx = re.compile(a.grep, re.I)
            s = [t for t in s if rx.search(t[2])]
        out["strings"] = s
    if a.json:
        json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
    else:
        print(f"== {p.name} size={len(data)} machine={hex(pe.machine)} pe32plus={pe.pe32plus}")
        for s in pe.sections:
            print(f"  {s['name']:8s} va={s['vaddr']:#010x} vs={s['vsize']:#010x} raw={s['rawptr']:#010x}/{s['rawsize']:#010x}")
        if a.imports:
            print("-- imports --")
            for dll, fns in sorted(out["imports"].items()):
                print(f"  {dll} ({len(fns)})")
                for fn in fns[:400]:
                    print(f"     {fn}")
            if out["delay_imports"]:
                print("-- delay imports --")
                for dll, fns in sorted(out["delay_imports"].items()):
                    print(f"  {dll} ({len(fns)})")
                    for fn in fns[:400]:
                        print(f"     {fn}")
        if a.exports:
            print(f"-- exports ({len(out['exports'])}) --")
            for e in out["exports"][:600]:
                print("  " + e)
        if a.strings:
            print(f"-- strings ({len(out['strings'])}) --")
            for _, kind, val in out["strings"]:
                print(f"  {kind} {val}")


if __name__ == "__main__":
    main()
