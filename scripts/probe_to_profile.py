# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the capabilities section of assets/device-profiles/<device>.json from a probe report (ADR-0003).

Hand-written sections (presetLenses, calibration, focusCalibration) of an existing profile are preserved.
Usage: python scripts/probe_to_profile.py <device>          write assets/device-profiles/<device>.json
       python scripts/probe_to_profile.py <device> --check  exit 1 if the committed profile is out of date
"""
import glob
import json
import os
import re
import sys

from probe_gpu import gpu, nnapi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_VERSION = 1
OUTPUT_FORMATS = ("JPEG", "JPEG_R", "RAW_SENSOR", "YUV_420_888")
SENSOR_TYPES = {
    "pressure": "android.sensor.pressure",
    "light": "android.sensor.light",
    "magneticField": "android.sensor.magnetic_field",
    "ambientTemperature": "android.sensor.ambient_temperature",
    "pressureTemperature": "com.google.sensor.pressure_temp",
}
CROP_WIDTH_RATIO = 0.75  # docs/test/g0-blazer.md 1.1: a 2x crop mode reports half the sensor width


def latest_report(device):
    reports = sorted(glob.glob(os.path.join(ROOT, "tools", "probe", "results", f"{device}-*.json")))
    if not reports:
        sys.exit(f"no probe report for {device} in tools/probe/results/")
    with open(reports[-1], encoding="utf-8") as f:
        return json.load(f)


def matrix(text):
    """'ColorSpaceTransform([a/b, ...], ...)' -> 9 floats (row-major), or None."""
    if not text:
        return None
    return [round(int(n) / int(d), 6) for n, d in re.findall(r"(-?\d+)/(\d+)", text)] or None


def lens_kind(cam, main_focal):
    if cam["facing"] != "BACK":
        return "FRONT"
    focal = cam["lens"]["focalLengthsMm"][0]
    return "MAIN" if abs(focal - main_focal) < 0.01 else ("ULTRAWIDE" if focal < main_focal else "TELE")


def readout(cam, widest):
    width = float(cam["sensor"]["physicalSizeMm"].split("x")[0])
    return "CROP_2X" if width < widest * CROP_WIDTH_RATIO else "BINNED"


def physical(cam, logical, main_focal, widest):
    caps = set(cam["capabilities"])
    outputs = {f: {"max": s["max"], "minFrameNs": s["maxMinFrameDurationNs"], "stallNs": s["maxStallDurationNs"]}
               for f, s in (cam["streams"] or {}).items() if f in OUTPUT_FORMATS}
    c = cam["color"]
    return {
        "id": cam["id"], "logicalId": logical["id"], "lens": lens_kind(cam, main_focal),
        "readout": readout(cam, widest), "focalLengthMm": round(cam["lens"]["focalLengthsMm"][0], 3),
        "minFocusDistanceDiopters": round(cam["lens"]["minimumFocusDistanceDiopters"], 4),
        "manualSensor": "MANUAL_SENSOR" in caps, "manualPostProcessing": "MANUAL_POST_PROCESSING" in caps,
        "raw": "RAW" in caps, "isoRange": cam["sensor"]["sensitivityRange"],
        "maxAnalogIso": cam["sensor"]["maxAnalogSensitivity"], "exposureRangeNs": cam["sensor"]["exposureTimeRangeNs"],
        "afModes": cam["controls"]["afModes"], "outputs": outputs,
        "color": {
            "referenceIlluminant1": c["referenceIlluminant1"], "referenceIlluminant2": c["referenceIlluminant2"],
            "colorTransform1": matrix(c["colorTransform1"]), "colorTransform2": matrix(c["colorTransform2"]),
            "forwardMatrix1": matrix(c["forwardMatrix1"]), "forwardMatrix2": matrix(c["forwardMatrix2"]),
        },
    }


def capabilities(report):
    logicals, physicals = [], []
    for cam in report["camera"]["cameras"]:
        subs = cam["physicalCameras"] or [cam]
        main_focal = cam["lens"]["focalLengthsMm"][0]
        widest = {}
        for p in subs:
            key = p["lens"]["focalLengthsMm"][0]
            widest[key] = max(widest.get(key, 0), float(p["sensor"]["physicalSizeMm"].split("x")[0]))
        logicals.append({
            "id": cam["id"], "facing": cam["facing"], "hardwareLevel": cam["hardwareLevel"],
            "physicalIds": [p["id"] for p in subs], "aeTargetFpsRanges": cam["controls"]["aeTargetFpsRanges"],
            "zoomRatioRange": json.loads(cam["controls"]["zoomRatioRange"]), "extensions": cam["extensions"],
            "flashAvailable": cam["controls"]["flashAvailable"],
        })
        physicals += [physical(p, cam, main_focal, widest[p["lens"]["focalLengthsMm"][0]]) for p in subs]
    sensors = {}
    for key, string_type in SENSOR_TYPES.items():
        found = [s for s in report["sensors"]["sensors"] if s["stringType"] == string_type]
        sensors[key] = {"available": bool(found), "maxDelayUs": found[0]["maxDelayUs"] if found else None}
    return {"logicalCameras": logicals, "physicalCameras": physicals, "sensors": sensors,
            "gpu": gpu(report), "nnapi": nnapi(report)}


def build(device, existing):
    report = latest_report(device)
    d = report["device"]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "device": {"buildDevice": d["device"], "model": d["model"], "socModel": d["socModel"]},
        "source": {"probeSchema": report["schema"], "probedAt": report["generatedAt"],
                   "buildId": d["buildId"], "sdkIntFull": d["sdkIntFull"]},
        "capabilities": capabilities(report),
        "presetLenses": existing.get("presetLenses", {}),
        "calibration": existing.get("calibration", []),
        "focusCalibration": existing.get("focusCalibration", []),
    }


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    device = sys.argv[1]
    path = os.path.join(ROOT, "assets", "device-profiles", f"{device}.json")
    existing = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            existing = json.load(f)
    text = json.dumps(build(device, existing), indent=2, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        with open(path, encoding="utf-8") as f:
            current = f.read()
        ok = current == text
        print(f"{device}: {'up to date' if ok else 'OUT OF DATE; rerun without --check'}")
        return 0 if ok else 1
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"wrote {os.path.relpath(path, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
