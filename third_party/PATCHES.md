# Local patches to vendored sources

Re-apply these after updating a vendored dependency.

## llama.cpp `v0.5.0`

- `ggml/src/ggml-opencl/kernels/embed_kernel.py`: open the kernel source and the generated
  header with `encoding="utf-8"`. Without it, Python on a non-UTF-8 Windows locale (e.g. Korean,
  cp949) fails on the UTF-8 characters in the `.cl` files during the OpenCL build.

## Fetched tools (not patched)

- `shaderc/bin/glslc.exe`: from Google's official shaderc Windows release build (v2026.4). The
  NDK's glslc (2022) cannot compile ggml's cooperative-matrix Vulkan shaders.
