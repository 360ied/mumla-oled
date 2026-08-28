#!/usr/bin/env python3
# Copyright (C) 2026 Mumla Developers
# SPDX-License-Identifier: GPL-3.0-or-later

import sys
import os
import struct
import re

def dump_blob(c_file_path, out_bin_path):
    with open(c_file_path, 'r', encoding='utf-8') as f:
        c_code = f.read()

    float_skip = {
        'conv2_weights_float',
        'gru1_input_weights_float',
        'gru1_recurrent_weights_float',
        'gru2_input_weights_float',
        'gru2_recurrent_weights_float',
        'gru3_input_weights_float',
        'gru3_recurrent_weights_float'
    }

    arrays = {}

    # Match float arrays
    for m in re.finditer(r'static const float (\w+)\[\d+\] = \{(.*?)\};', c_code, re.DOTALL):
        name = m.group(1)
        if name in float_skip:
            continue
        values = [float(x.strip()) for x in m.group(2).split(',') if x.strip()]
        data = struct.pack(f'<{len(values)}f', *values)
        arrays[name] = (0, data) # TYPE 0 = float

    # Match int8 arrays
    for m in re.finditer(r'static const opus_int8 (\w+)\[\d+\] = \{(.*?)\};', c_code, re.DOTALL):
        name = m.group(1)
        values = [int(x.strip()) for x in m.group(2).split(',') if x.strip()]
        data = struct.pack(f'<{len(values)}b', *values)
        arrays[name] = (3, data) # TYPE 3 = int8

    # Match int arrays (indices)
    for m in re.finditer(r'static const int (\w+)\[\d+\] = \{(.*?)\};', c_code, re.DOTALL):
        name = m.group(1)
        values = [int(x.strip()) for x in m.group(2).split(',') if x.strip()]
        data = struct.pack(f'<{len(values)}i', *values)
        arrays[name] = (1, data) # TYPE 1 = int

    BLOCK_SIZE = 64
    HEAD_MAGIC = b'DNNw'
    if not arrays:
        raise ValueError(f"No weight arrays parsed from {c_file_path}")

    os.makedirs(os.path.dirname(os.path.abspath(out_bin_path)), exist_ok=True)
    with open(out_bin_path, 'wb') as f:
        for name, (wtype, data) in arrays.items():
            size = len(data)
            block_size = (size + BLOCK_SIZE - 1) // BLOCK_SIZE * BLOCK_SIZE
            name_bytes = name.encode('utf-8')[:43].ljust(44, b'\x00')
            header = struct.pack('<4siiii44s', HEAD_MAGIC, VERSION, wtype, size, block_size, name_bytes)
            f.write(header)
            f.write(data)
            pad = block_size - size
            if pad > 0:
                f.write(b'\x00' * pad)

    print(f"Generated {out_bin_path} with {len(arrays)} arrays ({os.path.getsize(out_bin_path)} bytes)")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <rnnoise_data.c> <output_model.bin>")
        sys.exit(1)
    dump_blob(sys.argv[1], sys.argv[2])
