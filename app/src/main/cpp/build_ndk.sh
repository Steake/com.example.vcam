#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNILIBS_DIR="${SCRIPT_DIR}/../jniLibs"
STUB_DIR="/tmp/vcam_bionic_stubs"
mkdir -p "${STUB_DIR}"

cat << 'STUB_C' > "${STUB_DIR}/stubs.c"
int __android_log_print(int prio, const char* tag, const char* fmt, ...) { return 0; }
void* dlopen(const char* filename, int flag) { return 0; }
void* dlsym(void* handle, const char* symbol) { return 0; }
int dl_iterate_phdr(void* callback, void* data) { return 0; }
int mprotect(void* addr, unsigned long len, int prot) { return 0; }
long sysconf(int name) { return 4096; }
int strcmp(const char* s1, const char* s2) { return 0; }
int strncmp(const char* s1, const char* s2, unsigned long n) { return 0; }
char* strstr(const char* haystack, const char* needle) { return 0; }
unsigned long strlen(const char* s) { return 0; }
void* memcpy(void* dest, const void* src, unsigned long n) { return dest; }
void* memset(void* s, int c, unsigned long n) { return s; }
void* malloc(unsigned long size) { return 0; }
void free(void* ptr) {}
int pthread_mutex_lock(void* mutex) { return 0; }
int pthread_mutex_unlock(void* mutex) { return 0; }
void* ANativeWindow_fromSurface(void* env, void* surface) { return 0; }
void* ANativeWindow_toSurface(void* env, void* window) { return 0; }
void ANativeWindow_acquire(void* window) {}
void ANativeWindow_release(void* window) {}
int ANativeWindow_getWidth(void* window) { return 0; }
int ANativeWindow_getHeight(void* window) { return 0; }
int ANativeWindow_getFormat(void* window) { return 0; }
STUB_C

build_arch() {
    local abi="$1"
    local cc="$2"
    local strip_bin="$3"
    local out_dir="${JNILIBS_DIR}/${abi}"
    local arch_stub="${STUB_DIR}/${abi}"
    mkdir -p "${out_dir}" "${arch_stub}"

    "${cc}" -shared -fPIC -nostdlib "${STUB_DIR}/stubs.c" -Wl,-soname,libc.so -o "${arch_stub}/libc.so"
    "${cc}" -shared -fPIC -nostdlib "${STUB_DIR}/stubs.c" -Wl,-soname,libdl.so -o "${arch_stub}/libdl.so"
    "${cc}" -shared -fPIC -nostdlib "${STUB_DIR}/stubs.c" -Wl,-soname,liblog.so -o "${arch_stub}/liblog.so"
    "${cc}" -shared -fPIC -nostdlib "${STUB_DIR}/stubs.c" -Wl,-soname,libandroid.so -o "${arch_stub}/libandroid.so"

    "${cc}" -shared -fPIC -O2 -Wall -Wextra -fno-stack-protector -nostdlib \
        "${SCRIPT_DIR}/vcam_ndk.c" \
        -L"${arch_stub}" -lc -ldl -llog -landroid -lgcc \
        -Wl,-soname,libvcam_ndk.so \
        -Wl,--hash-style=both \
        -o "${out_dir}/libvcam_ndk.so"

    "${strip_bin}" --strip-unneeded "${out_dir}/libvcam_ndk.so"
    echo "Built ${out_dir}/libvcam_ndk.so ($(stat -c%s "${out_dir}/libvcam_ndk.so") bytes)"
}

build_arch "arm64-v8a" "aarch64-linux-gnu-gcc" "aarch64-linux-gnu-strip"
build_arch "armeabi-v7a" "arm-linux-gnueabihf-gcc" "arm-linux-gnueabihf-strip"
build_arch "x86_64" "gcc" "strip"
