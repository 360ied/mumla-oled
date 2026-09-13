# Mumla OLED Release APK Size Analysis & Binary Footprint Report

This report provides a comprehensive, quantitative analysis of the binary footprint, package composition, Dalvik bytecode structure, native shared libraries, asset payloads, and resource overhead in the production release APK of **Mumla OLED** ([`mumla-foss-release.apk`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/build/outputs/apk/foss/release/mumla-foss-release.apk)).

---

## 1. Executive Summary & Top-Level Metrics

The production release artifact is built under the `foss` flavor with ProGuard/R8 minification and resource shrinking enabled.

```mermaid
flowchart TD
    APK["mumla-foss-release.apk (6.97 MB / 7,311,002 bytes)"]
    APK --> ASSETS["assets/ — 2.65 MB (38.0%)"]
    APK --> DEX["classes.dex — 2.11 MB (30.2%)"]
    APK --> LIB["lib/ (3 ABIs) — 1.11 MB (15.9%)"]
    APK --> ARSC["resources.arsc — 0.62 MB (8.9%)"]
    APK --> RES["res/ (Drawables/XML) — 0.36 MB (5.2%)"]
    APK --> META["META-INF & Other — 0.12 MB (1.7%)"]

    ASSETS --> RNN["rnnoise_model.bin: 2.65 MB (99.9% of assets)"]
    DEX --> BC["Bouncy Castle: 3,066 classes (57.8% of DEX)"]
    DEX --> AX["AndroidX & Support: 1,125 classes (21.2% of DEX)"]
    DEX --> MUM["Mumla / Humla Core: 304 classes (5.7% of DEX)"]
    LIB --> OPUS["libjniopus.so (x3 ABIs): 844 KB"]
    LIB --> HUMLA["libhumlaaudio.so (x3 ABIs): 288 KB"]
```

### Global Artifact Metrics

| Metric | Measured Value | Human Readable | Notes |
| :--- | :--- | :--- | :--- |
| **Download / On-Disk Size (Compressed)** | `7,311,002 bytes` | **6.97 MiB** (~7.1 MB) | Exact byte count of the signed release APK |
| **Installed / Unpacked Size (Uncompressed)** | `12,723,793 bytes` | **12.13 MiB** (~12.7 MB) | Sum of all internal uncompressed payload streams |
| **Overall Compression Ratio** | `57.46%` | — | Ratio of compressed to uncompressed contents |
| **Total Files Inside Archive** | `1,003 files` | — | Includes classes, shared objects, resources, metadata |
| **Target Application ID** | `se.lublin.mumla.oled15` | — | Configured in [`app/build.gradle`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/build.gradle#L102) |
| **DEX Compilation Strategy** | Single DEX (`classes.dex`) | — | Single DEX 035 archive; no multidex split |
| **Target ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86_64` | — | Multi-architecture universal FOSS APK |

---

## 2. Global Archive Composition Breakdown

The archive contents break down across six primary functional categories:

| Category | Compressed Size | % of Total APK | Uncompressed Size | % of Uncompressed | File Count |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Assets (`assets/`)** | `2,781,718 B` | **38.05%** | `3,546,419 B` | **27.87%** | 3 |
| **Bytecode (`classes.dex`)** | `2,207,865 B` | **30.20%** | `5,056,836 B` | **39.74%** | 1 |
| **Native Libraries (`lib/`)** | `1,159,475 B` | **15.86%** | `2,319,060 B` | **18.23%** | 6 |
| **Compiled Table (`resources.arsc`)** | `654,304 B` | **8.95%** | `654,304 B` | **5.14%** | 1 |
| **App Resources (`res/`)** | `376,127 B` | **5.15%** | `713,198 B` | **5.61%** | 904 |
| **Signatures, Manifest & Meta** | `131,513 B` | **1.79%** | `433,976 B` | **3.41%** | 88 |
| **Total** | **`7,311,002 B`** | **100.00%** | **`12,723,793 B`** | **100.00%** | **1,003** |

### Breakdown by File Extension

| Extension | Compressed Size | % APK | Uncompressed Size | File Count | Primary Contents |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `.bin` | `2,781,315 B` | 38.04% | `3,547,368 B` | 3 | RNNoise model weights, coroutine debug probes |
| `.dex` | `2,207,865 B` | 30.20% | `5,056,836 B` | 1 | Application and dependency Dalvik bytecode |
| `.so` | `1,159,475 B` | 15.86% | `2,319,060 B` | 6 | Opus codec and Humla native audio engine |
| `.arsc` | `654,304 B` | 8.95% | `654,304 B` | 1 | Compiled resource string & ID lookup table |
| `.xml` | `233,485 B` | 3.19% | `579,576 B` | 635 | Binary Android layouts and vector drawables |
| `.png` | `145,522 B` | 1.99% | `145,522 B` | 270 | Legacy channel/status icons and splash graphics |
| `.SF` / `.MF` / `.RSA` | `83,282 B` | 1.14% | `188,973 B` | 3 | APK v1/v2/v3 signing manifests and certificates |
| `.properties` | `31,947 B` | 0.44% | `199,533 B` | 25 | BouncyCastle localization & JavaCPP configs |
| `.kotlin_builtins` | `10,164 B` | 0.14% | `28,940 B` | 7 | Kotlin runtime reflection metadata |
| Other | `3,643 B` | 0.05% | `4,781 B` | 52 | Baseline profiles, AndroidX version metadata |

---

## 3. Deep Dive: Asset Storage & RNNoise Model Payload

The single largest entry inside the entire APK is [`assets/rnnoise_model.bin`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/assets/rnnoise_model.bin), accounting for **38.02%** of the entire APK file size.

```math
\text{Asset Footprint Ratio} = \frac{\text{Compressed Size of } \texttt{assets/rnnoise\_model.bin}}{\text{Total Compressed APK Size}} = \frac{2,779,776}{7,311,002} \approx 38.02\%
```

### Detailed Asset File Census

| Path | Compressed Size | Uncompressed Size | Compression % | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `assets/rnnoise_model.bin` | `2,779,776 B` (2.65 MiB) | `3,544,256 B` (3.38 MiB) | 78.43% | Quantized neural network weights for RNNoise |
| `assets/dexopt/baseline.prof` | `2,177 B` (2.12 KiB) | `2,177 B` (2.12 KiB) | 100.00% | ART AOT baseline execution profile |
| `assets/dexopt/baseline.profm` | `288 B` (0.28 KiB) | `288 B` (0.28 KiB) | 100.00% | ART baseline profile metadata |

### Architecture & Format of `rnnoise_model.bin`

1. **Custom Binary Container Format (`DNNw`)**:
   - The neural model is converted from upstream C code by [`libraries/humla/tools/dump_rnnoise_blob.py`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/tools/dump_rnnoise_blob.py) into an 8-bit quantized binary blob.
   - Each tensor is serialized with a 64-byte structured header:
     - 4-byte magic: `b'DNNw'`
     - 4-byte version integer: `0`
     - 4-byte weight type: `0` for FP32 float, `1` for int32 index, `3` for int8 quantized
     - 4-byte payload size in bytes
     - 4-byte 64-byte-aligned block size
     - 44-byte null-padded tensor name string
   - Every tensor payload is zero-padded to a multiple of 64 bytes (`BLOCK_SIZE = 64`) to preserve memory alignment when mapped into native memory.

2. **Tensor Payload Breakdown**:

| Layer Type | Tensor Names | Data Type | Element Count | Size on Disk |
| :--- | :--- | :--- | :--- | :--- |
| **Gated Recurrent Units (GRU)** | `gru1_input_weights_int8`<br>`gru1_recurrent_weights_int8`<br>`gru2_input_weights_int8`<br>`gru2_recurrent_weights_int8`<br>`gru3_input_weights_int8`<br>`gru3_recurrent_weights_int8` | `opus_int8` (int8) | 6 × 442,368 elements | **2,654,208 bytes** (74.9% of model) |
| **Convolutional Layers** | `conv2_weights_int8`<br>`conv1_weights_float` | `opus_int8`<br>`float` | 147,456 elements<br>24,960 elements | **147,456 bytes**<br>**99,840 bytes** |
| **Sparse GRU Index Tables** | `gru1/2/3_input/recurrent_weights_idx` | `int` (int32) | 6 × 13,968 elements | **335,232 bytes** (9.5% of model) |
| **Dense Projection & VAD** | `dense_out_weights_float`<br>`vad_dense_weights_float` | `float` (FP32) | 49,152 + 1,536 elements | **202,752 bytes** |
| **Scales, Biases & Subiases** | `gru1/2/3_bias/scale/subias`, `conv1/2_bias` | `float` (FP32) | 26 arrays | **104,768 bytes** |
| **Total Payload** | **42 Tensors** | — | — | **3,544,256 bytes** |

### Architectural Synergy with Native Shared Libraries

- **The Multi-ABI Duplication Problem**:
  Upstream RNNoise defaults to embedding model weights statically inside `rnnoise_data.c` (an uncompressed 16 MB C source file generating ~3.5 MB of read-only `.rodata` machine code). If statically compiled into `libhumlaaudio.so`, this 3.5 MB footprint would be duplicated across all 3 target architectures (`arm64-v8a`, `armeabi-v7a`, and `x86_64`), consuming:

```math
\text{Static Multi-ABI Weight Bloat} = 3 \times 3,544,256\text{ bytes} \approx 10,632,768\text{ bytes} \quad (\approx 10.14\text{ MiB uncompressed})
```

- **Asset Extraction Strategy**:
  In [`libraries/humla/src/main/jni/Android.mk`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/Android.mk#L79), the build defines `-DUSE_WEIGHTS_FILE`. This wraps the weight table in `rnnoise_data.c` in `#ifndef USE_WEIGHTS_FILE`, completely stripping the static weights from the compiled `.so` binaries.
- **Runtime Loading**:
  Instead, [`NativeAudioInputEngine.loadRnnoiseModel()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioInputEngine.java#L47-L62) reads `rnnoise_model.bin` from Android assets once into a cached byte buffer in memory, passing it across JNI during `AudioInputEngine` initialization. This single optimization saved over **7 MB** of compressed APK overhead.

---

## 4. Deep Dive: Dalvik Executable Bytecode (`classes.dex`)

[`classes.dex`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/build/intermediates/dex/fossRelease/minifyFossReleaseWithR8/classes.dex) represents the second largest component of the APK at **2.11 MiB** compressed (30.20%) and **4.82 MiB** uncompressed (39.74%).

```mermaid
pie title classes.dex Retained Class Census (5,303 Total Retained Classes)
    "Bouncy Castle (org.bouncycastle.*)" : 3066
    "AndroidX & Support" : 1125
    "Google & Protobuf" : 317
    "Mumla / Humla Application" : 304
    "Utilities (Jsoup, MiniDNS, JavaCPP, Kotlin)" : 491
```

### Low-Level DEX Header Metrics

Extracted directly from the binary DEX 035 header:

| DEX Header Field | Measured Value | Android 64K Limit | Margin Remaining |
| :--- | :--- | :--- | :--- |
| **Class Definitions (`class_defs_size`)** | `5,321` | Unlimited | — |
| **Method References (`method_ids_size`)** | `34,514` | 65,536 | **31,022 methods (47.3% headroom)** |
| **Field References (`field_ids_size`)** | `14,523` | 65,536 | **51,013 fields (77.8% headroom)** |
| **Type IDs (`type_ids_size`)** | `6,611` | 65,536 | 58,925 types |
| **String IDs (`string_ids_size`)** | `27,759` | 65,536 | 37,777 strings |
| **Prototype IDs (`proto_ids_size`)** | `7,344` | 65,536 | 58,192 prototypes |

The entire application comfortably fits within a single primary DEX, avoiding the runtime startup overhead and APK packaging penalty of Dalvik Multidex splitting.

### Class Retention Census by Dependency Package

R8 minification and tree shaking (`minifyEnabled = true`) reduced the codebase to **5,303 mapped classes** and **245,428 method mappings**:

| Package Prefix | Retained Classes | % of Retained Classes | Mapped Methods | % of Mapped Methods | Dominant Role / Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`org.bouncycastle.*`** | **3,066** | **57.82%** | **84,826** | **34.56%** | PKCS#12, X.509 certificate gen & import |
| `androidx.appcompat.*` | 240 | 4.53% | 22,486 | 9.16% | Material/AppCompat UI widgets, themes, action bar |
| `androidx.core.*` | 208 | 3.92% | 9,322 | 3.80% | Android platform compatibility shims |
| **`se.lublin.humla.*`** | **192** | **3.62%** | **11,840** | **4.82%** | Core Mumble protocol engine, audio bridge, crypto |
| `com.google.android.*` | 227 | 4.28% | 18,240 | 7.43% | Material design components, layout widgets |
| `org.jsoup.*` | 127 | 2.40% | 8,431 | 3.44% | HTML sanitization and message rendering |
| **`se.lublin.mumla.*`** | **112** | **2.11%** | **5,800** | **2.36%** | Android activities, fragments, overlay, preferences |
| `com.google.protobuf.*` | 90 | 1.70% | 14,478 | 5.90% | Mumble protocol protobuf runtime serialization |
| `com.googlecode.javacpp.*` | 77 | 1.45% | 5,090 | 2.07% | JNI bindings generator runtime |
| `androidx.recyclerview.*` | 66 | 1.24% | 10,099 | 4.11% | Channel and user list virtualized rendering |
| `androidx.fragment.*` | 66 | 1.24% | 7,700 | 3.14% | Fragment lifecycle management |
| `org.minidns.*` | 65 | 1.23% | 3,133 | 1.28% | DNS SRV record lookup for Mumble server discovery |
| `androidx.constraintlayout.*` | 55 | 1.04% | 13,562 | 5.53% | Complex UI constraint layouts |
| `android.support.v4.*` | 56 | 1.06% | 3,203 | 1.31% | Legacy support interfaces |
| `androidx.emoji2.*` | 35 | 0.66% | 2,932 | 1.19% | Modern system emoji compatibility |
| `kotlin.*` | 42 | 0.79% | 1,840 | 0.75% | Kotlin standard library runtime helpers |
| Other AndroidX / Misc | 479 | 9.03% | 22,446 | 9.15% | Lifecycle, viewmodel, activity, transition |
| **Total** | **5,303** | **100.00%** | **245,428** | **100.00%** | |

### Why Bouncy Castle Accounts for 57.8% of Dalvik Classes

Bouncy Castle dominates bytecode size due to reflection-based provider instantiation.

In [`app/proguard-rules.pro`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/proguard-rules.pro#L22-L27), the build specifies:

```pro
# Preserve BouncyCastle Security Providers, certificate builders, and crypto engines
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-dontwarn org.bouncycastle.**
```

Because [`BouncyCastleProvider`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L81) registers dozens of cryptographic Service Provider Interfaces (SPIs) dynamically using string keys, R8 cannot statically verify reachability. The wildcard rule forces R8 to retain:

- `org.bouncycastle.jcajce.provider.*`: **1,669 classes**
- `org.bouncycastle.asn1.*`: **328 classes**
- `org.bouncycastle.crypto.*`: **305 classes**
- `org.bouncycastle.pqc.*` (post-quantum crypto): **188 classes**
- `org.bouncycastle.cert.*`: **152 classes**
- `org.bouncycastle.operator.*`: **126 classes**
- `org.bouncycastle.jce.*`: **107 classes**
- `org.bouncycastle.math.*`: **107 classes**

Mumla OLED only uses BouncyCastle for X.509 client certificate generation in [`HumlaCertificateGenerator`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaCertificateGenerator.java) and PKCS#12 keystore parsing in [`CertificateImportActivity`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java). The remaining cryptographic engines (PQC, Camellia, GOST, ElGamal, Blowfish, CAST, Twofish, etc.) are dead weight preserved solely by the broad `-keep` directive.

---

## 5. Deep Dive: Native Shared Libraries (`lib/`)

Native binaries represent **1.11 MiB** compressed (15.86%) and **2.21 MiB** uncompressed (18.23%). The application targets three Application Binary Interfaces (ABIs) configured via `ndk.abiFilters` in [`app/build.gradle`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/build.gradle#L84-L86).

```mermaid
flowchart LR
    subgraph arm64 ["arm64-v8a (392.8 KB comp / 817.7 KB uncomp)"]
        A1["libjniopus.so (289.5 KB)"]
        A2["libhumlaaudio.so (103.3 KB)"]
    end
    subgraph armv7 ["armeabi-v7a (316.2 KB comp / 520.0 KB uncomp)"]
        B1["libjniopus.so (240.3 KB)"]
        B2["libhumlaaudio.so (75.9 KB)"]
    end
    subgraph x86 ["x86_64 (423.3 KB comp / 927.0 KB uncomp)"]
        C1["libjniopus.so (314.0 KB)"]
        C2["libhumlaaudio.so (109.3 KB)"]
    end
```

### Detailed Native Library Metrics

| Architecture (ABI) | Library Name | Compressed Size | Uncompressed Size | Deflate Savings | Key Functionality |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`arm64-v8a`** | `libjniopus.so` | `296,497 B` (289.5 KB) | `566,224 B` (553.0 KB) | 47.6% | Upstream Opus 1.5.2 codec (ARM64 Neon asm) |
| | `libhumlaaudio.so` | `105,774 B` (103.3 KB) | `271,048 B` (264.7 KB) | 61.0% | AudioInputEngine, RNNoise C++ runtime, JitterBuffer |
| **`armeabi-v7a`** | `libjniopus.so` | `246,092 B` (240.3 KB) | `383,828 B` (374.8 KB) | 35.9% | Upstream Opus 1.5.2 codec (ARMv7 Thumb2/Neon) |
| | `libhumlaaudio.so` | `77,680 B` (75.9 KB) | `148,648 B` (145.2 KB) | 47.7% | AudioInputEngine, RNNoise C++ runtime, JitterBuffer |
| **`x86_64`** | `libjniopus.so` | `321,545 B` (314.0 KB) | `662,576 B` (647.0 KB) | 51.5% | Upstream Opus 1.5.2 codec (x86-64 AVX/SSE) |
| | `libhumlaaudio.so` | `111,887 B` (109.3 KB) | `286,736 B` (280.0 KB) | 61.0% | AudioInputEngine, RNNoise C++ runtime, JitterBuffer |
| **Subtotal (`libjniopus.so`)** | 3 ABIs | **`864,134 B` (843.9 KB)** | **`1,612,628 B` (1.54 MB)** | 46.4% | |
| **Subtotal (`libhumlaaudio.so`)** | 3 ABIs | **`295,341 B` (288.4 KB)** | **`706,432 B` (689.9 KB)** | 58.2% | |
| **Total Native (`lib/`)** | **6 files** | **`1,159,475 B` (1.11 MB)** | **`2,319,060 B` (2.21 MB)** | **50.0%** | |

### Component Analysis

1. **`libjniopus.so`**:
   - Compiled from upstream Opus submodule ([`libraries/humla/src/main/jni/opus`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/opus)).
   - Represents **74.5%** of all native code in the APK.
   - Built with `-O3 -fvisibility=hidden` and target-specific vector extensions (ARM Neon on `arm64-v8a`, AVX/SSE on `x86_64`).
2. **`libhumlaaudio.so`**:
   - Compiles the modernized C++ audio pipeline from [`libraries/humla/src/main/jni/audio_engine`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine):
     - [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp) (Oboe/AAudio audio stream processor)
     - [`RnnoiseProcessor.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/RnnoiseProcessor.cpp) (GRU neural network denoiser)
     - [`PreSpeechRingBuffer.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/PreSpeechRingBuffer.cpp) (80ms lookahead FIFO)
     - [`HysteresisVad.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/HysteresisVad.cpp) (Dual-threshold speech probability state machine)
     - [`SoftLimiter.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/SoftLimiter.cpp) and [`AdaptiveLeveler.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AdaptiveLeveler.cpp)
     - [`jitter.c`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/jitter/jitter.c) (Adaptive jitter buffer)
   - Highly compact (~76–112 KB compressed per ABI) due to symbol stripping and weight externalization.

---

## 6. Deep Dive: Resources, Assets & Packaging Overhead

### Compiled Resource Table (`resources.arsc`)

- **Size**: `654,304 bytes` (638.97 KiB), uncompressed ($8.95\%$ of total APK).
- Android packaging requires `resources.arsc` to be stored uncompressed (`Stored` mode) in APK archives so that `AssetManager` can mmap resource tables directly from disk without inflator decompression overhead.
- **Language Pruning Effect**:
  [`app/build.gradle`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/build.gradle#L82) defines:
  ```groovy
  resourceConfigurations += ["en", "fr", "zh-rCN"]
  ```
  This restricts compiled localization strings to English, French, and Simplified Chinese, trimming hundreds of unneeded translated strings from upstream dependencies (AndroidX, Material components) and saving an estimated **450–600 KB** in `resources.arsc`.

### Application Resource Files (`res/`)

- **Total Payload**: `376,127 bytes` compressed (5.15% of APK), `713,198 bytes` uncompressed across **904 files**.

| Resource Sub-Type | File Count | Compressed Size | Uncompressed Size | Characteristics |
| :--- | :--- | :--- | :--- | :--- |
| **Compiled XML (`.xml`)** | 634 | `230,605 B` (225.2 KB) | `567,676 B` (554.4 KB) | Binary XML layouts, vector drawables, state lists |
| **Bitmaps (`.png`)** | 270 | `145,522 B` (142.1 KB) | `145,522 B` (142.1 KB) | Legacy notification icons, channel emblems |

Notice that `.png` files have identical compressed and uncompressed sizes (`145,522 B` each), as AAPT2 leaves already-compressed PNG streams uncompressed within the APK deflate container to avoid negative compression expansion.

### Classpath Baggage & Cross-Platform Metadata Leaks

Several non-code resource files from upstream Java libraries leak into the root of the APK, totaling **~40 KB compressed** and **~235 KB uncompressed**:

1. **Bouncy Castle Localization Bundles**:
   - `org/bouncycastle/pkix/CertPathReviewerMessages.properties`: `6,038 B` comp (`42,868 B` uncomp)
   - `org/bouncycastle/pkix/CertPathReviewerMessages_de.properties`: `6,732 B` comp (`49,608 B` uncomp)
   - `org/bouncycastle/x509/CertPathReviewerMessages.properties`: `6,038 B` comp (`42,868 B` uncomp)
   - `org/bouncycastle/x509/CertPathReviewerMessages_de.properties`: `6,732 B` comp (`49,608 B` uncomp)
   - **Total impact**: `25,540 bytes` compressed (~185 KB uncompressed). These are localized error messages in German and English for certificate path validation, completely unused in Mumla.
2. **JavaCPP Desktop Platform Property Files**:
   - `com/googlecode/javacpp/properties/`: 20 `.properties` files totaling `6,183 bytes` compressed (`14,640 bytes` uncompressed).
   - Contains platform descriptors for non-Android targets: `macosx-x86_64.properties`, `windows-x86_64-cuda.properties`, `linux-x86-cuda.properties`, `ios-arm.properties`, etc.
3. **Debug Probes & Descriptors**:
   - `DebugProbesKt.bin`: `782 B` comp (`1,738 B` uncomp) — Kotlin coroutine debugger probe dictionary.
   - `core/java_features_proto-descriptor-set.proto.bin`: `743 B` comp (`1,310 B` uncomp) — Protobuf descriptor binary.

---

## 7. Actionable Size Optimization Matrix

Based on this empirical investigation, the following architectural and build configuration levers are available to reduce release APK footprint:

| Optimization Strategy | Target Component | Estimated Savings (Compressed) | Estimated Savings (Uncompressed) | Complexity | Technical Risk & Trade-offs |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Per-ABI APK Splitting** | `lib/` | **~750 KB** (~10.3%) | **~1.50 MB** | Low | Low risk. Requires publishing multiple APK artifacts (or AAB) rather than a single universal FOSS APK. |
| **2. BouncyCastle Class Stripping** | `classes.dex` | **~600–900 KB** (~8.2–12.3%) | **~1.5–2.2 MB** | Medium | Moderate risk. Requires auditing all dynamic JCE algorithm lookups to replace wildcard `-keep class org.bouncycastle.jcajce.provider.**` with granular rules for RSA, EC, and PKCS#12. |
| **3. Exclude Unused Packaging Resources** | `org/`, `com/` | **~32 KB** (~0.4%) | **~200 KB** | Trivial | Zero risk. Add `packaging.resources.excludes += ['org/bouncycastle/**/*.properties', 'com/googlecode/javacpp/properties/**']` in `app/build.gradle`. |
| **4. Vector Drawable Migration** | `res/` | **~60–90 KB** (~0.8–1.2%) | **~60–90 KB** | Medium | Low risk. Convert the 270 legacy PNG icons and drawables to Android vector XML drawables. |
| **5. Model Weight FP16 / Pruning** | `assets/` | **~300–600 KB** (~4.1–8.2%) | **~400–800 KB** | High | High risk. Requires re-training or fine-tuning the RNNoise GRU layers to verify denoising PESQ/POLQA voice quality does not regress. |

---

## 8. Summary Conclusion

The Mumla OLED release package size of **7.1 MB** is remarkably lean for a full-duplex VoIP communication client with an embedded deep neural network and multi-architecture native assembly. The binary footprint is dominated by two specialized components:

```math
\text{Dominant Payloads} = \underbrace{38.0\%}_{\text{RNNoise Weights}} + \underbrace{30.2\%}_{\text{Dalvik Bytecode (57.8\% BouncyCastle)}} + \underbrace{15.9\%}_{\text{3x Native ABIs}} = 84.1\% \text{ of Total APK}
```

The design decision to decouple RNNoise weights from `libhumlaaudio.so` into a shared `assets/rnnoise_model.bin` is the most significant existing architectural optimization, preventing more than **10 MB** of duplicate uncompressed native binary bloat across architectures. Future optimization should prioritize refining BouncyCastle ProGuard rules and stripping non-Android classpath resource leaks.
