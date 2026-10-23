/**
 * Standalone Native IAD (Injection Attack Detection) Validation Harness
 * Replicates Persona, Sumsub (DeviceSecurityChecker), ARGOS IMU correlation,
 * and Bixelab April 2026 Level-2/3 NDK sensor-attestation checks across 600 transactions.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <dlfcn.h>
#include <math.h>

typedef struct {
    int32_t numerator;
    int32_t denominator;
} ACameraMetadata_rational;

typedef struct {
    uint32_t tag;
    uint8_t  type;
    uint32_t count;
    union {
        const uint8_t*                  u8;
        const int32_t*                  i32;
        const float*                    f;
        const int64_t*                  i64;
        const double*                   d;
        const ACameraMetadata_rational* r;
    } data;
} ACameraMetadata_const_entry;

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint64_t rfu1;
} AHardwareBuffer_Desc;

/* External symbols exported by mock NDK camera/media libraries */
extern int ACameraManager_getCameraCharacteristics(void* mgr, const char* cameraId, void** chars);
extern int ACameraMetadata_getConstEntry(const void* metadata, uint32_t tag, ACameraMetadata_const_entry* entry);
extern int ACameraManager_openCamera(void* mgr, const char* cameraId, void* cb, void** device);
extern int ACaptureSessionOutput_create(void* anw, void** output);
extern int ACaptureSessionOutputContainer_add(void* container, void* output);
extern int ACameraOutputTarget_create(void* anw, void** target);
extern int ACaptureRequest_addTarget(void* request, void* target);
extern int ACameraDevice_createCaptureSession(void* device, void* container, const void* cb, void** session);
extern int AImageReader_newWithUsage(int32_t w, int32_t h, int32_t fmt, uint64_t usage, int32_t maxImages, void** reader);
extern int AImageReader_getWindow(void* reader, void** window);
extern int AImageReader_acquireLatestImage(void* reader, void** image);
extern int AImage_getTimestamp(const void* image, int64_t* timestampNs);
extern int AImage_getHardwareBuffer(const void* image, void** buffer);
extern void AHardwareBuffer_describe(const void* buffer, AHardwareBuffer_Desc* outDesc);
extern int AImage_getNumberOfPlanes(const void* image, int32_t* numPlanes);
extern int AImage_getPlanePixelStride(const void* image, int planeIdx, int32_t* pixelStride);
extern int AImage_getPlaneData(const void* image, int planeIdx, uint8_t** data, int* dataLength);

static uint64_t fnv1a_hash(const uint8_t* buf, int len) {
    uint64_t h = 14695981039346656037ULL;
    for (int i = 0; i < len; i++) {
        h ^= (uint64_t)buf[i];
        h *= 1099511628211ULL;
    }
    return h;
}

static int64_t host_boottime_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

int main(void) {
    void* h_vcam = dlopen("./libvcam_ndk_test.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h_vcam) {
        fprintf(stderr, "Failed to dlopen libvcam_ndk_test.so: %s\n", dlerror());
        return 1;
    }

    typedef void (*pfn_install)(void);
    typedef void (*pfn_set_profile)(int, int, int, int, float, float, int, int, int64_t, int64_t);
    typedef void (*pfn_set_imu)(float, float, float, float);
    typedef void (*pfn_set_frame)(const uint8_t*, int32_t, int32_t, int32_t);
    typedef int  (*pfn_heal_got)(int);

    pfn_install fn_install = (pfn_install)dlsym(h_vcam, "vcam_ndk_install_all_hooks");
    pfn_set_profile fn_profile = (pfn_set_profile)dlsym(h_vcam, "vcam_ndk_set_sensor_profile");
    pfn_set_imu fn_imu = (pfn_set_imu)dlsym(h_vcam, "vcam_ndk_set_imu_motion");
    pfn_set_frame fn_frame = (pfn_set_frame)dlsym(h_vcam, "vcam_ndk_set_raw_frame");
    pfn_heal_got fn_heal = (pfn_heal_got)dlsym(h_vcam, "vcam_ndk_verify_and_heal_got");

    if (!fn_install || !fn_profile || !fn_imu || !fn_frame || !fn_heal) {
        fprintf(stderr, "Missing exports in libvcam_ndk_test.so\n");
        return 1;
    }

    /* Configure Samsung S24 / Pixel 8 Front Camera physical sensor profile */
    fn_profile(0 /* FRONT */, 270, 4032, 3024, 5.60f, 4.20f, 50, 3200, 100000LL, 100000000LL);

    /* Stage a synthetic 640x480 NV21 gradient frame */
    const int W = 640, H = 480;
    const int NV21_LEN = W * H * 3 / 2;
    uint8_t* raw_nv21 = (uint8_t*)malloc((size_t)NV21_LEN);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            raw_nv21[y * W + x] = (uint8_t)(16 + ((x + y) % 200));
        }
    }
    memset(raw_nv21 + W * H, 128, (size_t)(W * H / 2));
    fn_frame(raw_nv21, NV21_LEN, W, H);

    /* Install NDK hooks across process */
    fn_install();

    int total_tx = 600;
    int live_count = 0;
    int spoof_count = 0;
    uint64_t prev_hash = 0;
    int64_t prev_ts = 0;

    for (int tx = 0; tx < total_tx; tx++) {
        double sumsub_risk_score = 0.0; /* hookWeight=0.20, virtualCameraWeight=0.25, replayAttackWeight=0.25 */
        int persona_integrity_ok = 1;

        /* 1. Sumsub DeviceSecurityChecker: scan /proc/self/maps for vcam/xposed/frida */
        FILE* fp = fopen("/proc/self/maps", "r");
        if (fp) {
            char line[512];
            while (fgets(line, sizeof(line), fp)) {
                if (strstr(line, "vcam") || strstr(line, "frida") || strstr(line, "xposed") || strstr(line, "lsposed")) {
                    sumsub_risk_score += 0.20;
                    break;
                }
            }
            fclose(fp);
        }

        /* 2. Sumsub DeviceSecurityChecker: dlsym detector probe */
        typedef int (*pfn_detector)(void);
        pfn_detector fn_frida = (pfn_detector)dlsym(RTLD_DEFAULT, "frida_present");
        pfn_detector fn_xposed = (pfn_detector)dlsym(RTLD_DEFAULT, "xposed_present");
        pfn_detector fn_vcam = (pfn_detector)dlsym(RTLD_DEFAULT, "check_virtual_camera");
        if (!fn_frida || fn_frida() != 0 || !fn_xposed || fn_xposed() != 0 || !fn_vcam || fn_vcam() != 0) {
            sumsub_risk_score += 0.20;
        }

        /* 3. Persona / Bixelab Sensor Attestation: ACameraManager_getCameraCharacteristics */
        void* chars = 0;
        ACameraManager_getCameraCharacteristics((void*)0x1, "1", &chars);
        ACameraMetadata_const_entry entry;
        memset(&entry, 0, sizeof(entry));

        /* Check LENS_FACING (0x80005) != EXTERNAL (2) */
        if (ACameraMetadata_getConstEntry(chars, 0x00080005U, &entry) != 0 || !entry.data.u8 || entry.data.u8[0] == 2) {
            sumsub_risk_score += 0.25;
            persona_integrity_ok = 0;
        }
        /* Check SENSOR_INFO_TIMESTAMP_SOURCE (0xF0008) == REALTIME (1) */
        if (ACameraMetadata_getConstEntry(chars, 0x000F0008U, &entry) != 0 || !entry.data.u8 || entry.data.u8[0] != 1) {
            persona_integrity_ok = 0;
        }
        /* Check SENSOR_INFO_PHYSICAL_SIZE (0xF0005) */
        if (ACameraMetadata_getConstEntry(chars, 0x000F0005U, &entry) != 0 || entry.count < 2 || entry.data.f[0] < 1.0f) {
            persona_integrity_ok = 0;
        }
        /* Check SENSOR_INFO_PIXEL_ARRAY_SIZE (0xF0006) */
        if (ACameraMetadata_getConstEntry(chars, 0x000F0006U, &entry) != 0 || entry.count < 2 || entry.data.i32[0] < 1280) {
            persona_integrity_ok = 0;
        }

        /* 4. NDK Capture Chain + IMU Motion Correlation + Start-of-Exposure Timestamp Check */
        float gyro_x = (tx % 2 == 0) ? 1.0f : -1.0f;
        float gyro_y = (tx % 3 == 0) ? 1.0f : 0.0f;
        fn_imu(gyro_x, gyro_y, 0.01f, -0.01f);

        void* reader = 0;
        void* window = 0;
        void* image = 0;
        AImageReader_newWithUsage(W, H, 0x22 /* PRIVATE coerced to 0x23 */, 0x3ULL, 4, &reader);
        AImageReader_getWindow(reader, &window);
        AImageReader_acquireLatestImage(reader, &image);

        int64_t img_ts = 0;
        int64_t now_ns = host_boottime_ns();
        AImage_getTimestamp(image, &img_ts);
        /* Verify start-of-exposure timestamp is monotonic and prior to presentation time */
        if (img_ts <= prev_ts || img_ts > now_ns + 50000000LL) {
            persona_integrity_ok = 0;
        }
        prev_ts = img_ts;

        /* Verify AImage_getHardwareBuffer & AHardwareBuffer_describe */
        void* hw_buf = 0;
        AHardwareBuffer_Desc desc;
        memset(&desc, 0, sizeof(desc));
        if (AImage_getHardwareBuffer(image, &hw_buf) != 0 || !hw_buf) {
            persona_integrity_ok = 0;
        } else {
            AHardwareBuffer_describe(hw_buf, &desc);
            if (desc.format != 0x23U || (desc.usage & 0x20000ULL) == 0 || (desc.stride % 64U) != 0) {
                persona_integrity_ok = 0;
            }
        }

        /* Verify AImage_getPlaneData injection, temporal grain (anti-replay), and IMU correlation */
        uint8_t* plane_ptr = 0;
        int plane_len = 0;
        if (AImage_getPlaneData(image, 0, &plane_ptr, &plane_len) != 0 || !plane_ptr || plane_len < W * H) {
            sumsub_risk_score += 0.25;
            persona_integrity_ok = 0;
        } else {
            uint64_t h = fnv1a_hash(plane_ptr, plane_len);
            if (h == prev_hash) {
                /* Duplicate frame hash -> Replay Attack flag */
                sumsub_risk_score += 0.25;
            }
            prev_hash = h;
        }

        if (sumsub_risk_score == 0.0 && persona_integrity_ok) {
            live_count++;
        } else {
            spoof_count++;
        }
    }

    free(raw_nv21);
    printf("[IAD_HARNESS] Transactions=%d LIVE=%d SPOOF=%d PassRate=%.2f%%\n",
           total_tx, live_count, spoof_count, (100.0 * live_count) / total_tx);
    return (live_count == total_tx && spoof_count == 0) ? 0 : 1;
}
