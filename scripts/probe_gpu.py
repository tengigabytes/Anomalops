# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""GPU and NNAPI parts of the capabilities section (ADR-0017), used by probe_to_profile.py."""

GL_EXTENSIONS = (  # ADR-0017: float render targets and filtering, 16-bit normalized textures, subgroups
    "GL_EXT_color_buffer_float", "GL_EXT_color_buffer_half_float", "GL_OES_texture_float_linear",
    "GL_OES_texture_half_float_linear", "GL_EXT_texture_norm16", "GL_KHR_shader_subgroup",
)
HALF_MAX_REL_ERROR = 2 ** -10  # one half-float ULP: allows round-toward-zero as well as round-to-nearest


def format_works(name, result):
    if not (result.get("compiled") and result.get("glError") == "0x0"):
        return False
    return result["maxRelError"] <= (HALF_MAX_REL_ERROR if name.endswith("16F") else 0)


def gpu(report):
    features, gles = report["gpu"]["features"], report["gpu"]["gles"]
    limits, formats = gles["limits"], gles["formats"]
    store = formats["imageStore"]
    half = [r["rounding"] for n, r in store.items() if n.endswith("16F") and format_works(n, r)]
    return {
        "renderer": gles["renderer"], "glesVersion": f"{gles['majorVersion']}.{gles['minorVersion']}",
        "vulkanVersion": features["vulkanVersion"], "maxTextureSize": limits["maxTextureSize"],
        "maxComputeWorkGroupSize": limits["maxComputeWorkGroupSize"],
        "maxComputeWorkGroupInvocations": limits["maxComputeWorkGroupInvocations"],
        "maxComputeSharedMemoryBytes": limits["maxComputeSharedMemoryBytes"],
        "maxComputeImageUniforms": limits["maxComputeImageUniforms"],
        "mediumpFloatBits": gles["fragmentPrecision"]["mediumFloat"]["precisionBits"],
        "imageStoreFormats": [n for n, r in store.items() if format_works(n, r)],
        "imageReadWriteFormats": [n for n, r in formats["imageReadWrite"].items() if format_works(n, r)],
        "colorRenderableFormats": [n for n, ok in formats["colorRenderable"].items() if ok],
        "halfRounding": half[0] if half else None,
        "r16uiUpload": formats["r16uiUpload"].get("mismatches") == 0 and formats["r16uiUpload"]["glError"] == "0x0",
        "extensions": {e: e in gles["extensions"] for e in GL_EXTENSIONS},
    }


def nnapi(report):
    n = report["gpu"]["nnapi"]
    return {"runtimeFeatureLevel": n["runtimeFeatureLevel"], "halDevices": n["halDevices"] or []}
