# Embedded terminal native payload

The previous Karbon binaries were 4 KB ELF aligned even after the Rust JNI
libraries were fixed. On the official ARM64 16 KB Android 15 image, with page
compatibility disabled, the Android linker could not start that proot payload.

The app now bundles a source-built ARM64 payload. All three files are linked
with `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`, using NDK
`27.0.12077973` and Android API 26. The loader is ET_EXEC; proot is PIE and
libtalloc is ET_DYN. Every LOAD segment is at least 16 KB aligned, with matching
file/virtual address residues. APK verification covers the runtime assets as
well as JNI libraries.

## Pinned sources

| Component | Source | Source archive SHA-256 |
| --- | --- | --- |
| PRoot | [termux/proot e2d843ee6dab076ea0ed1ffd5d0ea12232185499](https://github.com/termux/proot/tree/e2d843ee6dab076ea0ed1ffd5d0ea12232185499) | `1c1dc902ee3bc089e58bf4fc316b723bcef3d7b75d53f22eb8725ff4bad127bc` |
| talloc | [talloc 2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) | `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007` |

Rebuild on macOS or Linux with Python 3, make, curl and the NDK:

```bash
ANDROID_NDK_HOME=/path/to/ndk/27.0.12077973 scripts/build-terminal-runtime.sh
```

The script verifies source hashes, cross-compiles talloc using Android cross
answers, then compiles proot and its matching external loader. It supplies
string.h only to the upstream ashmem C object that needs it. Loader workaround
metadata is generated from actual symbols using the NDK's llvm-readelf and
Python, avoiding a host GNU readelf/awk dependency and rejecting missing
symbols. No upstream C files are patched. The installer supplies PROOT_LOADER
from the system-extracted native library directory.

## Bundled artifact SHA-256

| File | SHA-256 |
| --- | --- |
| `app/src/main/embedded-native/arm64-v8a/proot` | `f40762e4040344babf96f3f1b5a88e250c76898ca95d2bf11429e560c200a79f` |
| `app/src/main/embedded-native/arm64-v8a/libtalloc.so.2` | `4d0cdc255b112e308dbcb333aa9aeb15341c3aa4fdb90438bd521a86a68acfdf` |
| `app/src/main/jniLibs/arm64-v8a/libproot-loader.so` | `f68a9744d8f66cb6ce86048f12520def33d4bbeb0e389db363149b901ed50a89` |

Upstream license texts are included in `app/src/main/assets/licenses/proot`
and `app/src/main/assets/licenses/talloc`. The runtime revision changes only
refresh the host binaries; existing Alpine user data is retained.
