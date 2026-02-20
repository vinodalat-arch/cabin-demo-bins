#!/usr/bin/env python3
"""Convert FP32 ONNX models to FP16 for reduced memory and bandwidth on ARM asimdhp CPUs."""

import onnx
from onnxconverter_common import float16

MODELS = ["yolov8n-pose", "yolov8n"]
ASSETS_DIR = "app/src/main/assets"

for name in MODELS:
    src = f"{ASSETS_DIR}/{name}.onnx"
    dst = f"{ASSETS_DIR}/{name}-fp16.onnx"
    print(f"Converting {src} -> {dst}")
    model = onnx.load(src)
    model_fp16 = float16.convert_float_to_float16(model)
    onnx.save(model_fp16, dst)
    print(f"  Done: {dst}")

print("All models converted.")
