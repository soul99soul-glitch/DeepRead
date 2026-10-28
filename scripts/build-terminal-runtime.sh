#!/usr/bin/env bash
# Rebuild the Android terminal payload from pinned upstream sources.
set -euo pipefail

task_root=$(cd "$(dirname "$0")/.." && pwd)
task_build="$task_root/app/build/terminal-native-build"
task_ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/27.0.12077973}"
case $(uname -s) in
  Darwin) task_host=darwin-x86_64 ;;
  Linux) task_host=linux-x86_64 ;;
  *) echo "Unsupported NDK build host" >&2; exit 1 ;;
esac
task_tools="$task_ndk/toolchains/llvm/prebuilt/$task_host/bin"
task_proot=e2d843ee6dab076ea0ed1ffd5d0ea12232185499
mkdir -p "$task_build"

fetch_source() {
  local destination=$1 url=$2 expected=$3
  if ! python3 - "$destination" "$expected" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
sys.exit(0 if p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == sys.argv[2] else 1)
PY
  then
    curl --fail --location --retry 3 "$url" -o "$destination.part"
    python3 - "$destination.part" "$expected" <<'PY'
import hashlib, pathlib, sys
assert hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2], "Source SHA-256 mismatch"
PY
    mv "$destination.part" "$destination"
  fi
}

fetch_source "$task_build/proot.tar.gz" \
  "https://github.com/termux/proot/archive/$task_proot.tar.gz" \
  1c1dc902ee3bc089e58bf4fc316b723bcef3d7b75d53f22eb8725ff4bad127bc
fetch_source "$task_build/talloc.tar.gz" \
  https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz \
  912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
tar -xzf "$task_build/proot.tar.gz" -C "$task_build"
tar -xzf "$task_build/talloc.tar.gz" -C "$task_build"

export CC="$task_tools/aarch64-linux-android26-clang"
export STRIP="$task_tools/llvm-strip"
export CFLAGS="-O2 -fPIC -ffile-prefix-map=$task_build=."
export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

cd "$task_build/talloc-2.5.0"
# Cross answers match the Android recipe in termux-packages/packages/libtalloc.
cat > cross-answers.txt <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS
PYTHON=python3 ./configure --prefix="$task_build/stage" --disable-rpath \
  --disable-python --cross-compile --cross-answers=cross-answers.txt
make -j4
make install

cd "$task_build/proot-$task_proot/src"
export CPPFLAGS='-I../../stage/include -DARG_MAX=131072 -DVERSION=\"e2d843ee\"'
export LDFLAGS="-L../../stage/lib $LDFLAGS"
export LOADER_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -no-pie"
# AlpineRuntimeInstaller always supplies the installer-extracted PROOT_LOADER.
export PROOT_UNBUNDLE_LOADER=/system/lib64
# Upstream ashmem_memfd.c uses strcmp/memset without including string.h.
# Force that declaration only for this C object, never the loader assembly.
cat > "$task_build/loader-info.py" <<'PY'
import subprocess, sys
symbols = {}
for line in subprocess.check_output([sys.argv[1], '--symbols', sys.argv[2]], text=True).splitlines():
    fields = line.split()
    if len(fields) >= 8 and fields[-1] in ('_start', 'pokedata_workaround'):
        symbols[fields[-1]] = int(fields[1], 16)
assert len(symbols) == 2, 'Missing loader symbols'
print('#include <unistd.h>')
print('const ssize_t offset_to_pokedata_workaround=%d;' % (symbols['pokedata_workaround'] - symbols['_start']))
PY
# Use the NDK's ELF tools and a portable generator; a missing host readelf or
# GNU awk must not silently produce a zero workaround offset on macOS.
cat > "$task_build/proot-overrides.mk" <<'OVERRIDES'
extension/ashmem_memfd/ashmem_memfd.o: CFLAGS += -include string.h
loader/loader-info.c: loader/loader
	python3 "$(AMBER_LOADER_INFO)" "$(AMBER_READELF)" "$<" > "$@"
OVERRIDES
rm -f loader/loader-info.c loader/loader-info.o
make -f GNUmakefile -f "$task_build/proot-overrides.mk" -j4 GIT=false V=1 \
  AMBER_LOADER_INFO="$task_build/loader-info.py" AMBER_READELF="$task_tools/llvm-readelf"

task_assets="$task_root/app/src/main/embedded-native/arm64-v8a"
task_jni="$task_root/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$task_assets" "$task_jni"
cp proot "$task_assets/proot"
cp "$task_build/stage/lib/libtalloc.so.2.5.0" "$task_assets/libtalloc.so.2"
cp loader/loader "$task_jni/libproot-loader.so"
"$STRIP" --strip-unneeded "$task_assets/proot" "$task_assets/libtalloc.so.2" "$task_jni/libproot-loader.so"
chmod 644 "$task_assets/proot" "$task_assets/libtalloc.so.2" "$task_jni/libproot-loader.so"
mkdir -p "$task_root/app/src/main/assets/licenses/proot" "$task_root/app/src/main/assets/licenses/talloc"
cp ../COPYING "$task_root/app/src/main/assets/licenses/proot/COPYING"
cp "$task_build/talloc-2.5.0/LICENSE" "$task_root/app/src/main/assets/licenses/talloc/LICENSE"
python3 - "$task_assets/proot" "$task_assets/libtalloc.so.2" "$task_jni/libproot-loader.so" <<'PY'
import hashlib, pathlib, struct, sys
for name in sys.argv[1:]:
    p = pathlib.Path(name)
    b = p.read_bytes()
    phoff = struct.unpack_from('<Q', b, 32)[0]
    entsize, count = struct.unpack_from('<HH', b, 54)
    loads = [phoff + i * entsize for i in range(count) if struct.unpack_from('<I', b, phoff + i * entsize)[0] == 1]
    assert loads, p.name
    for off in loads:
        assert struct.unpack_from('<Q', b, off + 48)[0] >= 16384, p.name
        assert struct.unpack_from('<Q', b, off + 8)[0] % 16384 == struct.unpack_from('<Q', b, off + 16)[0] % 16384, p.name
    print(hashlib.sha256(b).hexdigest(), p.name)
PY
