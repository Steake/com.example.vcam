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
void* mmap(void* addr, unsigned long length, int prot, int flags, int fd, long long offset) { return (void*)-1; }
int munmap(void* addr, unsigned long length) { return 0; }
int clock_gettime(int clk_id, void* tp) { return 0; }
long syscall(long number, ...) { return -1; }
int strcmp(const char* s1, const char* s2) { return 0; }
int strncmp(const char* s1, const char* s2, unsigned long n) { return 0; }
char* strstr(const char* haystack, const char* needle) { return 0; }
char* strchr(const char* s, int c) { return 0; }
unsigned long strlen(const char* s) { return 0; }
void* memcpy(void* dest, const void* src, unsigned long n) { return dest; }
void* memset(void* s, int c, unsigned long n) { return s; }
void* malloc(unsigned long size) { return 0; }
void free(void* ptr) {}
void* fopen(const char* pathname, const char* mode) { return 0; }
void* fdopen(int fd, const char* mode) { return 0; }
int fclose(void* stream) { return 0; }
char* fgets(char* s, int size, void* stream) { return 0; }
unsigned long fwrite(const void* ptr, unsigned long size, unsigned long nmemb, void* stream) { return 0; }
int fflush(void* stream) { return 0; }
int fseek(void* stream, long offset, int whence) { return 0; }
int fileno(void* stream) { return -1; }
int open(const char* pathname, int flags, ...) { return -1; }
int openat(int dirfd, const char* pathname, int flags, ...) { return -1; }
long read(int fd, void* buf, unsigned long count) { return 0; }
long write(int fd, const void* buf, unsigned long count) { return 0; }
long long lseek(int fd, long long offset, int whence) { return 0; }
int close(int fd) { return 0; }
int access(const char* pathname, int mode) { return -1; }
int faccessat(int dirfd, const char* pathname, int mode, int flags) { return -1; }
void* android_dlopen_ext(const char* filename, int flags, const void* extinfo) { return 0; }
int connect(int sockfd, const void* addr, unsigned int addrlen) { return 0; }
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

# Build & execute standalone x86_64 native IAD validation harness
HARNESS_DIR="/tmp/vcam_iad_harness"
mkdir -p "${HARNESS_DIR}"

cat << 'MOCK_C' > "${HARNESS_DIR}/mock_ndk.c"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static uint8_t s_mock_plane[640 * 480 * 2];

int __android_log_print(int prio, const char* tag, const char* fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }
void* ANativeWindow_fromSurface(void* env, void* surface) { (void)env; return surface ? surface : (void*)0xA001; }
void* ANativeWindow_toSurface(void* env, void* window) { (void)env; return window; }
void ANativeWindow_acquire(void* window) { (void)window; }
void ANativeWindow_release(void* window) { (void)window; }
int ANativeWindow_getWidth(void* window) { (void)window; return 640; }
int ANativeWindow_getHeight(void* window) { (void)window; return 480; }
int ANativeWindow_getFormat(void* window) { (void)window; return 0x23; }

int ACameraManager_getCameraIdList(void* mgr, void** list) { (void)mgr; (void)list; return 0; }
int ACameraManager_getCameraCharacteristics(void* mgr, const char* id, void** chars) {
    (void)mgr; (void)id;
    if (chars) *chars = (void*)0xC001;
    return 0;
}
int ACameraMetadata_getConstEntry(const void* meta, uint32_t tag, void* entry) {
    (void)meta; (void)tag; (void)entry;
    /* Unhooked returns -10002 or EXTERNAL lens facing to prove hook overrides it */
    return -10002;
}
int ACameraManager_openCamera(void* mgr, const char* id, void* cb, void** dev) {
    (void)mgr; (void)id; (void)cb;
    if (dev) *dev = (void*)0xD001;
    return 0;
}
int ACaptureSessionOutput_create(void* anw, void** out) { (void)anw; if (out) *out = (void*)0xE001; return 0; }
int ACaptureSessionOutputContainer_add(void* c, void* o) { (void)c; (void)o; return 0; }
int ACameraOutputTarget_create(void* anw, void** t) { (void)anw; if (t) *t = (void*)0xE002; return 0; }
int ACaptureRequest_addTarget(void* r, void* t) { (void)r; (void)t; return 0; }
int ACameraDevice_createCaptureSession(void* d, void* c, const void* cb, void** s) {
    (void)d; (void)c; (void)cb; if (s) *s = (void*)0xF001; return 0;
}
int AImageReader_new(int32_t w, int32_t h, int32_t fmt, int32_t max, void** r) {
    (void)w; (void)h; (void)fmt; (void)max; if (r) *r = (void*)0xBEEF01; return 0;
}
int AImageReader_newWithUsage(int32_t w, int32_t h, int32_t fmt, uint64_t u, int32_t max, void** r) {
    (void)w; (void)h; (void)fmt; (void)u; (void)max; if (r) *r = (void*)0xBEEF02; return 0;
}
int AImageReader_getWindow(void* r, void** w) { (void)r; if (w) *w = (void*)0xCAFE01; return 0; }
int AImageReader_acquireNextImage(void* r, void** img) { (void)r; if (img) *img = (void*)0x123401; return 0; }
int AImageReader_acquireLatestImage(void* r, void** img) { (void)r; if (img) *img = (void*)0x123402; return 0; }
int AImage_getTimestamp(const void* img, int64_t* ts) { (void)img; if (ts) *ts = 0; return 0; }
int AImage_getHardwareBuffer(const void* img, void** hb) { (void)img; if (hb) *hb = (void*)0xABCD01; return 0; }
void AHardwareBuffer_describe(const void* hb, void* desc) { (void)hb; memset(desc, 0, 32); }
int AImage_getNumberOfPlanes(const void* img, int32_t* n) { (void)img; if (n) *n = 1; return 0; }
int AImage_getPlanePixelStride(const void* img, int idx, int32_t* s) { (void)img; (void)idx; if (s) *s = 1; return 0; }
int AImage_getPlaneData(const void* img, int idx, uint8_t** data, int* len) {
    (void)img; (void)idx;
    if (data) *data = s_mock_plane;
    if (len) *len = 640 * 480;
    return 0;
}
MOCK_C

gcc -shared -fPIC -O2 "${HARNESS_DIR}/mock_ndk.c" -Wl,-soname,libcamera2ndk.so -o "${HARNESS_DIR}/libcamera2ndk.so"
ln -sf "${HARNESS_DIR}/libcamera2ndk.so" "${HARNESS_DIR}/libmediandk.so"
ln -sf "${HARNESS_DIR}/libcamera2ndk.so" "${HARNESS_DIR}/libandroid.so"

gcc -shared -fPIC -O2 -Wall -Wextra \
    "${SCRIPT_DIR}/vcam_ndk.c" \
    -L"${HARNESS_DIR}" -lcamera2ndk -ldl \
    -Wl,-rpath,"${HARNESS_DIR}" \
    -o "${HARNESS_DIR}/libvcam_ndk_test.so"

gcc -O2 -Wall -Wextra -fno-plt \
    "${SCRIPT_DIR}/vcam_iad_harness.c" \
    -L"${HARNESS_DIR}" -lcamera2ndk -ldl \
    -Wl,-rpath,"${HARNESS_DIR}" \
    -o "${HARNESS_DIR}/vcam_iad_harness"

(cd "${HARNESS_DIR}" && ./vcam_iad_harness)
