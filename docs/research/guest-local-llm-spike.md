# Guest local LLM: llama.cpp feasibility spike (SM-G970F)

Research note: 19 September 2026. This is a factual record of a device spike
that asked whether the NusaDesk guest can run a local LLM the way the Termux
community does. It is not a product commitment: nothing shipped depends on
the test payload, and the payload was removed from the device after the run.

## Question and result

Can the guest run local inference (llama.cpp or ollama)? **Yes, for CPU
inference with small models** — verified end-to-end on the physical S10e
(SM-G970F, Android 12/API 31, Exynos 9820) inside the curated Ubuntu Base
24.04 arm64 guest, under the same environment contract the product uses.

## Environment (guest, measured)

- CPU: 8 cores visible (Exynos 9820, big.LITTLE); `asimddp`/`sha2` present.
- RAM: 5.5 GB total, ~1.9 GB available at test time, 3 GB swap.
- Disk: 83 GB free on `/data`.
- Toolchain present: `git`, `curl`, `wget`, `tar`, `python3` (3.12.3, from the
  activated service overlay). Missing: `gcc`, `cmake`, `pip3`, `unzip` — a
  source build is not the practical path in this guest; prebuilt binaries are.

## What was tested

- Binary: official `llama-b11053-bin-ubuntu-arm64.tar.gz` (13.5 MB, CPU build)
  from the `ggml-org/llama.cpp` release page; its glibc target matches the
  guest's Ubuntu 24.04 arm64.
- Missing runtime dependency: `libgomp.so.1` (OpenMP) is not in the minimal
  rootfs. The spike extracted `libgomp1` from the Ubuntu noble arm64 package
  (`ports.ubuntu.com`) next to the binary; a product-shaped solution would
  vendor that dependency like the other curated payloads.
- Model: `Qwen2.5-0.5B-Instruct` Q4_K_M, 469 MB, GGUF magic verified.

## Measured results (llama-bench, r=3)

| threads | pp64 (prompt) | tg16 (generate) |
| --- | --- | --- |
| 4 | ~68 t/s | ~22-25 t/s |
| 8 | ~44-45 t/s | ~2-5 t/s |

**4 threads is the configuration to use on this SoC.** Eight threads collapse
generation throughput, most likely because the scheduler scatters the workers
onto little cores.

- `llama-server` works: `/health` answered `{"status":"ok"}`, and an
  OpenAI-compatible `/v1/chat/completions` request returned a completion with
  timings (prompt ~48 t/s, generation ~23.7 t/s).
- `llama-cli` single-turn works (flag `-st`; `-no-cnv` no longer exists in
  this build) and reported prompt ~52 t/s / generation ~23.9 t/s.
- Output quality at 0.5B is weak (a three-primary-colors answer was wrong) —
  that is the model size, not the runtime.

## Ollama

Not tested. The `ollama-linux-arm64` tarball is ~1.55 GB and bundles its own
runners for the same llama.cpp engine; a sub-1B model is plausible within the
measured RAM headroom, but the stack is far heavier. llama.cpp alone carried
this spike; ollama is the candidate only if model-management UX is ever
wanted.

## Acceleration: unavailable on this device, and why

- **GPU**: the S10e is Exynos/Mali. The community Mesa path (Turnip) is
  Adreno-only, and Android exposes no usable Mali driver to a glibc guest, so
  the termux-style Vulkan build does not apply here. On an Adreno device the
  same guest could use Turnip — device-class specific, not a product
  capability (see `docs/limitations.md`).
- **NPU**: no guest path exists on any tested device; the platform NN API is
  deprecated, and vendor NPU runtimes are host-side only.

## Method and cleanup

The spike drove the guest through the product's environment contract
(`PROOT_LOADER`, `PROOT_TMP_DIR`, fixed `PATH`, the service overlay bound at
`/opt/lw-services`, and the live bridge env file at
`/run/nusadesk/android-bridge.env`) using ad-hoc `adb` probe scripts, which
were deleted afterwards. No product source changed, and the `/root/llm-test/`
payload (~513 MB) was removed from the device once the numbers above were
recorded.
