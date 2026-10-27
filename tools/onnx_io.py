#!/usr/bin/env python3
"""Minimal ONNX (protobuf) graph I/O reader: tensor names, elem types and dims.

Reads only the graph input/output ValueInfoProto fields and skips initializers /
nodes, so it stays cheap even for large models.
"""
from __future__ import annotations

import sys
from pathlib import Path

ELEM = {
    1: "float32", 2: "uint8", 3: "int8", 4: "uint16", 5: "int16", 6: "int32",
    7: "int64", 8: "string", 9: "bool", 10: "float16", 11: "float64",
    12: "uint32", 13: "uint64", 14: "complex64", 15: "complex128",
    16: "bfloat16",
}


def read_varint(buf: bytes, i: int):
    shift = 0
    val = 0
    while True:
        b = buf[i]
        i += 1
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val, i
        shift += 7


def fields(buf: bytes, start: int, end: int):
    i = start
    while i < end:
        key, i = read_varint(buf, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = read_varint(buf, i)
            yield fn, wt, v
        elif wt == 1:
            yield fn, wt, buf[i : i + 8]
            i += 8
        elif wt == 2:
            ln, i = read_varint(buf, i)
            yield fn, wt, (i, i + ln)
            i += ln
        elif wt == 5:
            yield fn, wt, buf[i : i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wt} at {i}")


def parse_dim(buf: bytes, s: int, e: int):
    for fn, wt, v in fields(buf, s, e):
        if fn == 1 and wt == 0:
            return v
        if fn == 2 and wt == 2:
            return buf[v[0] : v[1]].decode("utf-8", "replace")
    return None


def parse_shape(buf: bytes, s: int, e: int):
    dims = []
    for fn, wt, v in fields(buf, s, e):
        if fn == 1 and wt == 2:
            dims.append(parse_dim(buf, v[0], v[1]))
    return dims


def parse_tensor_type(buf: bytes, s: int, e: int):
    elem = None
    dims = []
    for fn, wt, v in fields(buf, s, e):
        if fn == 1 and wt == 0:
            elem = v
        elif fn == 2 and wt == 2:
            dims = parse_shape(buf, v[0], v[1])
    return ELEM.get(elem, elem), dims


def parse_type(buf: bytes, s: int, e: int):
    for fn, wt, v in fields(buf, s, e):
        if fn == 1 and wt == 2:  # tensor_type
            return parse_tensor_type(buf, v[0], v[1])
    return None, []


def parse_value_info(buf: bytes, s: int, e: int):
    name = ""
    typ = (None, [])
    for fn, wt, v in fields(buf, s, e):
        if fn == 1 and wt == 2:
            name = buf[v[0] : v[1]].decode("utf-8", "replace")
        elif fn == 2 and wt == 2:
            typ = parse_type(buf, v[0], v[1])
    return {"name": name, "elem_type": typ[0], "dims": typ[1]}


def find_start(data: bytes) -> int:
    """Embedded models are prefixed with a license/signature banner; locate the
    first offset from which the bytes parse as a ModelProto carrying field 7."""
    for i in range(0, min(8192, len(data) - 16)):
        if data[i] != 0x08:
            continue
        try:
            for fn, wt, v in fields(data, i, len(data)):
                if fn == 7 and wt == 2:
                    return i
                if fn > 8:
                    break
        except Exception:
            continue
    return 0


def inspect(path: Path):
    data = path.read_bytes()
    start = find_start(data)
    graph = None
    for fn, wt, v in fields(data, start, len(data)):
        if fn == 7 and wt == 2:
            graph = v
            break
    if graph is None:
        return {"error": "no graph field", "start": start}
    gs, ge = graph
    info = {"file": path.name, "size": len(data), "inputs": [], "outputs": [], "opset": None, "nodes": 0}
    for fn, wt, v in fields(data, gs, ge):
        if fn == 11 and wt == 2:
            info["inputs"].append(parse_value_info(data, v[0], v[1]))
        elif fn == 12 and wt == 2:
            info["outputs"].append(parse_value_info(data, v[0], v[1]))
        elif fn == 1 and wt == 2:
            info["nodes"] += 1
    return info


if __name__ == "__main__":
    for p in sys.argv[1:]:
        r = inspect(Path(p))
        print(f"===== {r.get('file')} ({r.get('size', 0)/1e6:.1f} MB, nodes~{r.get('nodes')}) =====")
        for kind in ("inputs", "outputs"):
            print(f"  {kind}:")
            for vi in r.get(kind, []):
                print(f"    {vi['name']:<34} {str(vi['elem_type']):<9} {vi['dims']}")
