#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>

typedef int64_t off_t;
typedef long ssize_t;
typedef int pid_t;
typedef long time_t;

struct timespec {
    time_t tv_sec;
    long   tv_nsec;
};

#define CLOCK_MONOTONIC 1
#define CLOCK_BOOTTIME  7

#define PROT_READ  0x1
#define PROT_WRITE 0x2
#define PROT_EXEC  0x4
#define MAP_PRIVATE   0x02
#define MAP_ANONYMOUS 0x20
#define MAP_FAILED    ((void*)-1)

#define O_RDONLY 00
#define O_RDWR   02
#define SEEK_SET 0

#define ECONNREFUSED 111
#define AF_INET      2
#define AF_INET6     10

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6

/* External Bionic libc / libdl / liblog / libandroid symbols */
extern int __android_log_print(int prio, const char* tag, const char* fmt, ...);
extern void* dlopen(const char* filename, int flag);
extern void* dlsym(void* handle, const char* symbol);
extern int mprotect(void* addr, size_t len, int prot);
extern void* mmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset);
extern int munmap(void* addr, size_t length);
extern int clock_gettime(int clk_id, struct timespec* tp);
extern long syscall(long number, ...);
extern int strcmp(const char* s1, const char* s2);
extern int strncmp(const char* s1, const char* s2, size_t n);
extern char* strstr(const char* haystack, const char* needle);
extern char* strchr(const char* s, int c);
extern size_t strlen(const char* s);
extern void* memcpy(void* dest, const void* src, size_t n);
extern void* memset(void* s, int c, size_t n);
extern void* malloc(size_t size);
extern void free(void* ptr);
extern void* fopen(const char* pathname, const char* mode);
extern void* fdopen(int fd, const char* mode);
extern int fclose(void* stream);
extern char* fgets(char* s, int size, void* stream);
extern size_t fwrite(const void* ptr, size_t size, size_t nmemb, void* stream);
extern int fflush(void* stream);
extern int fseek(void* stream, long offset, int whence);
extern int fileno(void* stream);
extern int open(const char* pathname, int flags, ...);
extern int openat(int dirfd, const char* pathname, int flags, ...);
extern ssize_t read(int fd, void* buf, size_t count);
extern ssize_t write(int fd, const void* buf, size_t count);
extern off_t lseek(int fd, off_t offset, int whence);
extern int close(int fd);
extern int connect(int sockfd, const void* addr, uint32_t addrlen);

/* ============================================================================
 * 1. SINGLE NAMED CONFIG & NDK METADATA TAG / OFFSET BLOCK
 * ============================================================================ */
typedef struct {
    /* Android NDK CameraMetadata tag constants (camera/NdkCameraMetadataTags.h) */
    uint32_t tag_lens_facing;
    uint32_t tag_sensor_orientation;
    uint32_t tag_sensor_active_array_size;
    uint32_t tag_sensor_sensitivity_range;
    uint32_t tag_sensor_exposure_time_range;
    uint32_t tag_sensor_max_frame_duration;
    uint32_t tag_sensor_physical_size;
    uint32_t tag_sensor_pixel_array_size;
    uint32_t tag_sensor_timestamp_source;
    uint32_t tag_sensor_timestamp;
    uint32_t tag_sensor_exposure_time;
    uint32_t tag_sensor_sensitivity;
    uint32_t tag_info_supported_hardware_level;

    /* Advertised physical sensor profile (populated from real device HAL or JSON/INI) */
    uint8_t  advertised_lens_facing;           /* 0 = FRONT, 1 = BACK, 2 = EXTERNAL */
    int32_t  sensor_orientation;               /* 90 (back) or 270 (front) */
    int32_t  active_array_width;               /* e.g. 4032 */
    int32_t  active_array_height;              /* e.g. 3024 */
    float    physical_width_mm;                /* e.g. 5.60f */
    float    physical_height_mm;               /* e.g. 4.20f */
    int32_t  iso_min;                          /* e.g. 50 */
    int32_t  iso_max;                          /* e.g. 3200 */
    int64_t  exposure_min_ns;                  /* e.g. 100000 (0.1ms) */
    int64_t  exposure_max_ns;                  /* e.g. 100000000 (100ms) */
    int64_t  current_exposure_ns;              /* e.g. 16666666 (16.6ms) */
    int64_t  rolling_shutter_skew_ns;          /* e.g. 8200000 (8.2ms) */
    int32_t  current_iso;                      /* e.g. 160 */
    uint8_t  timestamp_source;                 /* 1 = ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME */
    uint8_t  hardware_level;                   /* 1 = FULL, 3 = LEVEL_3 */

    /* HardwareBuffer spoofing invariants */
    uint32_t ahb_format_yuv420;                /* 0x23 = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420 */
    uint64_t ahb_camera_usage_mask;            /* 0x20000 (CAMERA_WRITE) | 0x3 (CPU_READ_OFTEN) | 0x100 (GPU_SAMPLED_IMAGE) */
    uint32_t isp_stride_alignment;             /* 64-byte Qualcomm Spectra / Mali ISP row alignment */

    /* IMU motion-correlation state (updated live from real device Accelerometer/Gyroscope) */
    float    imu_jitter_dx_px;
    float    imu_jitter_dy_px;
    float    imu_pitch_rad;
    float    imu_roll_rad;
    int64_t  imu_last_update_ns;

    /* Hook strategy flags */
    int      enable_inline_trampoline;         /* 1 = inline prologue trampoline + clean GOT */
    int      enable_got_shadow_heal;           /* 1 = self-check and heal GOT on scanner inspection */
    int      enable_maps_cloaking;             /* 1 = scrub /proc/self/maps & /proc/net/tcp */
} VcamNdkConfig;

static VcamNdkConfig g_cfg = {
    .tag_lens_facing                   = 0x00080005U, /* ACAMERA_LENS_FACING */
    .tag_sensor_orientation            = 0x000E000EU, /* ACAMERA_SENSOR_ORIENTATION */
    .tag_sensor_active_array_size      = 0x000F0000U, /* ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE */
    .tag_sensor_sensitivity_range      = 0x000F0001U, /* ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE */
    .tag_sensor_exposure_time_range    = 0x000F0003U, /* ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE */
    .tag_sensor_max_frame_duration     = 0x000F0004U, /* ACAMERA_SENSOR_INFO_MAX_FRAME_DURATION */
    .tag_sensor_physical_size          = 0x000F0005U, /* ACAMERA_SENSOR_INFO_PHYSICAL_SIZE */
    .tag_sensor_pixel_array_size       = 0x000F0006U, /* ACAMERA_SENSOR_INFO_PIXEL_ARRAY_SIZE */
    .tag_sensor_timestamp_source       = 0x000F0008U, /* ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE */
    .tag_sensor_timestamp              = 0x000E0010U, /* ACAMERA_SENSOR_TIMESTAMP */
    .tag_sensor_exposure_time          = 0x000E0000U, /* ACAMERA_SENSOR_EXPOSURE_TIME */
    .tag_sensor_sensitivity            = 0x000E0004U, /* ACAMERA_SENSOR_SENSITIVITY */
    .tag_info_supported_hardware_level = 0x00130000U, /* ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL */

    .advertised_lens_facing            = 0,           /* ACAMERA_LENS_FACING_FRONT (default KYC target) */
    .sensor_orientation                = 270,
    .active_array_width                = 4032,
    .active_array_height               = 3024,
    .physical_width_mm                 = 5.60f,
    .physical_height_mm                = 4.20f,
    .iso_min                           = 50,
    .iso_max                           = 3200,
    .exposure_min_ns                   = 100000LL,
    .exposure_max_ns                   = 100000000LL,
    .current_exposure_ns               = 16666666LL,
    .rolling_shutter_skew_ns           = 8200000LL,
    .current_iso                       = 160,
    .timestamp_source                  = 1,           /* ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME */
    .hardware_level                    = 1,           /* ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_FULL */

    .ahb_format_yuv420                 = 0x23U,
    .ahb_camera_usage_mask             = 0x00020103ULL,
    .isp_stride_alignment              = 64U,

    .imu_jitter_dx_px                  = 0.0f,
    .imu_jitter_dy_px                  = 0.0f,
    .imu_pitch_rad                     = 0.0f,
    .imu_roll_rad                      = 0.0f,
    .imu_last_update_ns                = 0LL,

    .enable_inline_trampoline          = 1,
    .enable_got_shadow_heal            = 1,
    .enable_maps_cloaking              = 1
};

/* ============================================================================
 * 2. ELF & NDK DATA STRUCTURES
 * ============================================================================ */
#if defined(__LP64__)
typedef uint64_t ElfW_Addr;
typedef uint64_t ElfW_Off;
typedef uint64_t ElfW_Xword;
typedef int64_t  ElfW_Sxword;
typedef uint32_t ElfW_Word;
typedef uint16_t ElfW_Half;

typedef struct {
    ElfW_Word p_type;
    ElfW_Word p_flags;
    ElfW_Off p_offset;
    ElfW_Addr p_vaddr;
    ElfW_Addr p_paddr;
    ElfW_Xword p_filesz;
    ElfW_Xword p_memsz;
    ElfW_Xword p_align;
} ElfW_Phdr;

typedef struct {
    ElfW_Sxword d_tag;
    union {
        ElfW_Xword d_val;
        ElfW_Addr d_ptr;
    } d_un;
} ElfW_Dyn;

typedef struct {
    ElfW_Word st_name;
    unsigned char st_info;
    unsigned char st_other;
    ElfW_Half st_shndx;
    ElfW_Addr st_value;
    ElfW_Xword st_size;
} ElfW_Sym;

typedef struct {
    ElfW_Addr r_offset;
    ElfW_Xword r_info;
    ElfW_Sxword r_addend;
} ElfW_Rela;

#define ELF_R_SYM(i) ((i) >> 32)
#define USE_RELA 1
#else
typedef uint32_t ElfW_Addr;
typedef uint32_t ElfW_Off;
typedef uint32_t ElfW_Word;
typedef int32_t  ElfW_Sword;
typedef uint16_t ElfW_Half;

typedef struct {
    ElfW_Word p_type;
    ElfW_Off p_offset;
    ElfW_Addr p_vaddr;
    ElfW_Addr p_paddr;
    ElfW_Word p_filesz;
    ElfW_Word p_memsz;
    ElfW_Word p_flags;
    ElfW_Word p_align;
} ElfW_Phdr;

typedef struct {
    ElfW_Sword d_tag;
    union {
        ElfW_Word d_val;
        ElfW_Addr d_ptr;
    } d_un;
} ElfW_Dyn;

typedef struct {
    ElfW_Word st_name;
    ElfW_Addr st_value;
    ElfW_Word st_size;
    unsigned char st_info;
    unsigned char st_other;
    ElfW_Half st_shndx;
} ElfW_Sym;

typedef struct {
    ElfW_Addr r_offset;
    ElfW_Word r_info;
} ElfW_Rel;

#define ELF_R_SYM(i) ((i) >> 8)
#define USE_RELA 0
#endif

#define PT_DYNAMIC 2
#define DT_NULL    0
#define DT_PLTRELSZ 2
#define DT_STRTAB  5
#define DT_SYMTAB  6
#define DT_RELA    7
#define DT_RELASZ  8
#define DT_REL     17
#define DT_RELSZ   18
#define DT_JMPREL  23

struct dl_phdr_info {
    ElfW_Addr dlpi_addr;
    const char* dlpi_name;
    const ElfW_Phdr* dlpi_phdr;
    ElfW_Half dlpi_phnum;
};

extern int dl_iterate_phdr(int (*callback)(struct dl_phdr_info* info, size_t size, void* data), void* data);

/* ACameraMetadata_const_entry layout (camera/NdkCameraMetadata.h) */
enum {
    ACAMERA_TYPE_BYTE     = 0,
    ACAMERA_TYPE_INT32    = 1,
    ACAMERA_TYPE_FLOAT    = 2,
    ACAMERA_TYPE_INT64    = 3,
    ACAMERA_TYPE_DOUBLE   = 4,
    ACAMERA_TYPE_RATIONAL = 5
};

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
    int numCameras;
    const char* const* cameraIds;
} ACameraIdList;

/* AHardwareBuffer_Desc layout (android/hardware_buffer.h) */
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

/* Minimal JNI types */
typedef const struct JNINativeInterface* JNIEnv;
typedef const struct JNIInvokeInterface* JavaVM;
typedef void* jobject;
typedef void* jclass;
typedef void* jmethodID;
typedef void* jfieldID;
typedef void* jstring;
typedef void* jbyteArray;
typedef uint8_t jboolean;
typedef int32_t jint;
typedef int64_t jlong;
typedef float   jfloat;

#define JNI_OK 0
#define JNI_VERSION_1_6 0x00010006

struct JNINativeInterface {
    void* reserved0;
    void* reserved1;
    void* reserved2;
    void* reserved3;
    jint (*GetVersion)(JNIEnv*);
    jclass (*DefineClass)(JNIEnv*, const char*, jobject, const int8_t*, jint);
    jclass (*FindClass)(JNIEnv*, const char*);
    jmethodID (*FromReflectedMethod)(JNIEnv*, jobject);
    jfieldID (*FromReflectedField)(JNIEnv*, jobject);
    jobject (*ToReflectedMethod)(JNIEnv*, jclass, jmethodID, jboolean);
    jclass (*GetSuperclass)(JNIEnv*, jclass);
    jboolean (*IsAssignableFrom)(JNIEnv*, jclass, jclass);
    jobject (*ToReflectedField)(JNIEnv*, jclass, jfieldID, jboolean);
    jint (*Throw)(JNIEnv*, jobject);
    jint (*ThrowNew)(JNIEnv*, jclass, const char*);
    jobject (*ExceptionOccurred)(JNIEnv*);
    void (*ExceptionDescribe)(JNIEnv*);
    void (*ExceptionClear)(JNIEnv*);
    void (*FatalError)(JNIEnv*, const char*);
    jint (*PushLocalFrame)(JNIEnv*, jint);
    jobject (*PopLocalFrame)(JNIEnv*, jobject);
    jobject (*NewGlobalRef)(JNIEnv*, jobject);
    void (*DeleteGlobalRef)(JNIEnv*, jobject);
    void (*DeleteLocalRef)(JNIEnv*, jobject);
    jboolean (*IsSameObject)(JNIEnv*, jobject, jobject);
    jobject (*NewLocalRef)(JNIEnv*, jobject);
    jint (*EnsureLocalCapacity)(JNIEnv*, jint);
    jobject (*AllocObject)(JNIEnv*, jclass);
    jobject (*NewObject)(JNIEnv*, jclass, jmethodID, ...);
    jobject (*NewObjectV)(JNIEnv*, jclass, jmethodID, void*);
    jobject (*NewObjectA)(JNIEnv*, jclass, jmethodID, const void*);
    jclass (*GetObjectClass)(JNIEnv*, jobject);
    jboolean (*IsInstanceOf)(JNIEnv*, jobject, jclass);
    jmethodID (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
    jobject (*CallObjectMethod)(JNIEnv*, jobject, jmethodID, ...);
    jobject (*CallObjectMethodV)(JNIEnv*, jobject, jmethodID, void*);
    jobject (*CallObjectMethodA)(JNIEnv*, jobject, jmethodID, const void*);
    jboolean (*CallBooleanMethod)(JNIEnv*, jobject, jmethodID, ...);
    jboolean (*CallBooleanMethodV)(JNIEnv*, jobject, jmethodID, void*);
    jboolean (*CallBooleanMethodA)(JNIEnv*, jobject, jmethodID, const void*);
    int8_t (*CallByteMethod)(JNIEnv*, jobject, jmethodID, ...);
    void* call_other[78];
    jmethodID (*GetStaticMethodID)(JNIEnv*, jclass, const char*, const char*);
    jobject (*CallStaticObjectMethod)(JNIEnv*, jclass, jmethodID, ...);
    void* call_static_other[29];
    void (*CallStaticVoidMethod)(JNIEnv*, jclass, jmethodID, ...);
    void* rest_jni[100];
};

struct JNIInvokeInterface {
    void* reserved0;
    void* reserved1;
    void* reserved2;
    jint (*DestroyJavaVM)(JavaVM*);
    jint (*AttachCurrentThread)(JavaVM*, JNIEnv**, void*);
    jint (*DetachCurrentThread)(JavaVM*);
    jint (*GetEnv)(JavaVM*, void**, jint);
    jint (*AttachCurrentThreadAsDaemon)(JavaVM*, JNIEnv**, void*);
};

extern void* ANativeWindow_fromSurface(JNIEnv* env, jobject surface);
extern jobject ANativeWindow_toSurface(JNIEnv* env, void* window);
extern void ANativeWindow_acquire(void* window);
extern void ANativeWindow_release(void* window);
extern int32_t ANativeWindow_getWidth(void* window);
extern int32_t ANativeWindow_getHeight(void* window);
extern int32_t ANativeWindow_getFormat(void* window);

#define LOG_TAG "VCAM_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = 0;
static jclass g_bridge_class = 0;
static jmethodID g_mid_on_ndk_surface = 0;
static jmethodID g_mid_on_ndk_camera_open = 0;
static jmethodID g_mid_on_ndk_session_created = 0;

static void* g_virtual_sink_anw = 0;
static uint8_t* g_latest_nv21 = 0;
static int32_t g_latest_nv21_len = 0;
static int32_t g_latest_width = 1280;
static int32_t g_latest_height = 720;
static volatile int g_spinlock = 0;

static inline void spin_lock(void) {
    while (__sync_lock_test_and_set(&g_spinlock, 1)) {}
}
static inline void spin_unlock(void) {
    __sync_lock_release(&g_spinlock);
}

/* ============================================================================
 * 3. MONOTONIC SENSOR CLOCK & START-OF-EXPOSURE SYNTHESIS
 * ============================================================================ */
static int64_t g_last_sensor_timestamp_ns = 0;
static uint64_t g_prng_state = 0x853C49E6748FEA9BULL;

static uint32_t fast_rand_u32(void) {
    uint64_t oldstate = g_prng_state;
    g_prng_state = oldstate * 6364136223846793005ULL + 1442695040888963407ULL;
    uint32_t xorshifted = (uint32_t)(((oldstate >> 18u) ^ oldstate) >> 27u);
    uint32_t rot = (uint32_t)(oldstate >> 59u);
    return (xorshifted >> rot) | (xorshifted << ((-rot) & 31));
}

static int64_t get_boottime_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
        clock_gettime(CLOCK_MONOTONIC, &ts);
    }
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

/**
 * Computes a realistic physical start-of-exposure timestamp in the CLOCK_BOOTTIME
 * domain (matching ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME = 1):
 *   t_sensor = t_presentation - t_exposure - (t_rolling_shutter_skew / 2) + pll_jitter
 * Strictly monotonic across consecutive frames.
 */
static int64_t compute_sensor_exposure_timestamp_ns(void) {
    int64_t now_ns = get_boottime_ns();
    int64_t jitter_ns = (int64_t)(fast_rand_u32() % 40000U) - 20000LL; /* +/- 20us PLL jitter */
    int64_t soe_ns = now_ns - g_cfg.current_exposure_ns - (g_cfg.rolling_shutter_skew_ns / 2) + jitter_ns;
    spin_lock();
    if (soe_ns <= g_last_sensor_timestamp_ns) {
        /* Advance by nominal 33.333ms frame interval if called within same tick */
        soe_ns = g_last_sensor_timestamp_ns + 33333333LL + jitter_ns;
    }
    g_last_sensor_timestamp_ns = soe_ns;
    spin_unlock();
    return soe_ns;
}

/* ============================================================================
 * 4. TRACKED SURFACES, IMAGES & HARDWARE BUFFERS
 * ============================================================================ */
#define MAX_TRACKED_SURFACES 64
typedef struct {
    void* anw;
    jobject surface_global;
    int32_t width;
    int32_t height;
    int32_t format;
    int is_image_reader;
} TrackedAnw;

static TrackedAnw g_tracked[MAX_TRACKED_SURFACES];
static int g_tracked_count = 0;

typedef struct {
    void* reader;
    int32_t width;
    int32_t height;
    int32_t format;
    uint64_t usage;
} TrackedNdkReader;

static TrackedNdkReader g_readers[MAX_TRACKED_SURFACES];
static int g_reader_count = 0;

typedef struct {
    const void* image;
    int64_t spoofed_timestamp_ns;
    int32_t width;
    int32_t height;
    int32_t format;
    void*   hw_buffer;
} TrackedNdkImage;

static TrackedNdkImage g_images[MAX_TRACKED_SURFACES];
static int g_image_idx = 0;

static void* g_container_has_sink[MAX_TRACKED_SURFACES];
static int g_container_idx = 0;
static void* g_request_has_sink[MAX_TRACKED_SURFACES];
static int g_request_idx = 0;

static int has_ptr(void** arr, void* ptr) {
    if (!ptr) return 0;
    for (int i = 0; i < MAX_TRACKED_SURFACES; i++) {
        if (arr[i] == ptr) return 1;
    }
    return 0;
}

static void add_ptr(void** arr, int* idx, void* ptr) {
    if (!ptr || has_ptr(arr, ptr)) return;
    arr[*idx % MAX_TRACKED_SURFACES] = ptr;
    (*idx)++;
}

static TrackedNdkImage* track_acquired_image(const void* image, int32_t w, int32_t h, int32_t fmt) {
    if (!image) return 0;
    int64_t ts = compute_sensor_exposure_timestamp_ns();
    spin_lock();
    for (int i = 0; i < MAX_TRACKED_SURFACES; i++) {
        if (g_images[i].image == image) {
            g_images[i].spoofed_timestamp_ns = ts;
            if (w > 0) g_images[i].width = w;
            if (h > 0) g_images[i].height = h;
            if (fmt > 0) g_images[i].format = fmt;
            spin_unlock();
            return &g_images[i];
        }
    }
    int slot = g_image_idx % MAX_TRACKED_SURFACES;
    g_image_idx++;
    g_images[slot].image = image;
    g_images[slot].spoofed_timestamp_ns = ts;
    g_images[slot].width = w > 0 ? w : g_latest_width;
    g_images[slot].height = h > 0 ? h : g_latest_height;
    g_images[slot].format = fmt > 0 ? fmt : (int32_t)g_cfg.ahb_format_yuv420;
    g_images[slot].hw_buffer = 0;
    spin_unlock();
    return &g_images[slot];
}

static TrackedNdkImage* find_tracked_image(const void* image) {
    if (!image) return 0;
    for (int i = 0; i < MAX_TRACKED_SURFACES; i++) {
        if (g_images[i].image == image) return &g_images[i];
    }
    return 0;
}

static TrackedNdkImage* find_tracked_hw_buffer(const void* hw_buf) {
    if (!hw_buf) return 0;
    for (int i = 0; i < MAX_TRACKED_SURFACES; i++) {
        if (g_images[i].hw_buffer == hw_buf) return &g_images[i];
    }
    return 0;
}

static JNIEnv* get_jni_env(int* needs_detach) {
    *needs_detach = 0;
    if (!g_jvm) return 0;
    JNIEnv* env = 0;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (res != JNI_OK || !env) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, 0) == JNI_OK && env) {
            *needs_detach = 1;
            return env;
        }
        return 0;
    }
    return env;
}

static void release_jni_env(int needs_detach) {
    if (needs_detach && g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

typedef jobject (*pfn_ANativeWindow_toSurface_t)(JNIEnv*, void*);
static pfn_ANativeWindow_toSurface_t g_dyn_toSurface = 0;

static jobject convert_anw_to_surface(JNIEnv* env, void* anw) {
    if (!env || !anw) return 0;
    spin_lock();
    for (int i = 0; i < g_tracked_count; i++) {
        if (g_tracked[i].anw == anw && g_tracked[i].surface_global) {
            jobject local = (*env)->NewLocalRef(env, g_tracked[i].surface_global);
            spin_unlock();
            return local;
        }
    }
    spin_unlock();

    if (!g_dyn_toSurface) {
        void* h = dlopen("libandroid.so", 1);
        if (h) {
            g_dyn_toSurface = (pfn_ANativeWindow_toSurface_t)dlsym(h, "ANativeWindow_toSurface");
        }
    }
    if (g_dyn_toSurface) {
        jobject surf = g_dyn_toSurface(env, anw);
        if ((*env)->ExceptionOccurred(env)) {
            (*env)->ExceptionClear(env);
            return 0;
        }
        return surf;
    }
    return 0;
}

static void notify_java_ndk_surface(void* anw, int is_image_reader, int32_t w, int32_t h, int32_t fmt) {
    if (!anw || anw == g_virtual_sink_anw) return;
    if (w <= 0) w = ANativeWindow_getWidth(anw);
    if (h <= 0) h = ANativeWindow_getHeight(anw);
    if (fmt <= 0) fmt = ANativeWindow_getFormat(anw);

    int detach = 0;
    JNIEnv* env = get_jni_env(&detach);
    if (!env) return;

    jobject surf = convert_anw_to_surface(env, anw);
    if (surf) {
        spin_lock();
        int found = 0;
        for (int i = 0; i < g_tracked_count; i++) {
            if (g_tracked[i].anw == anw) {
                found = 1;
                break;
            }
        }
        if (!found && g_tracked_count < MAX_TRACKED_SURFACES) {
            g_tracked[g_tracked_count].anw = anw;
            g_tracked[g_tracked_count].surface_global = (*env)->NewGlobalRef(env, surf);
            g_tracked[g_tracked_count].width = w;
            g_tracked[g_tracked_count].height = h;
            g_tracked[g_tracked_count].format = fmt;
            g_tracked[g_tracked_count].is_image_reader = is_image_reader;
            g_tracked_count++;
        }
        spin_unlock();

        if (g_bridge_class && g_mid_on_ndk_surface) {
            (*env)->CallStaticVoidMethod(env, g_bridge_class, g_mid_on_ndk_surface,
                                         surf, (jint)w, (jint)h, (jint)fmt, (jboolean)(is_image_reader ? 1 : 0));
            if ((*env)->ExceptionOccurred(env)) {
                (*env)->ExceptionClear(env);
            }
        }
        (*env)->DeleteLocalRef(env, surf);
    }
    release_jni_env(detach);
}

/* ============================================================================
 * 5. NDK FUNCTION TYPEDEFS & ORIGINAL POINTERS
 * ============================================================================ */
typedef int (*pfn_ACameraManager_getCameraIdList)(void* mgr, ACameraIdList** cameraIdList);
typedef int (*pfn_ACameraManager_getCameraCharacteristics)(void* mgr, const char* cameraId, void** characteristics);
typedef int (*pfn_ACameraMetadata_getConstEntry)(const void* metadata, uint32_t tag, ACameraMetadata_const_entry* entry);
typedef int (*pfn_ACameraManager_openCamera)(void* mgr, const char* cameraId, void* cb, void** device);
typedef int (*pfn_ACaptureSessionOutput_create)(void* anw, void** output);
typedef int (*pfn_ACaptureSessionOutputContainer_add)(void* container, void* output);
typedef int (*pfn_ACameraOutputTarget_create)(void* anw, void** target);
typedef int (*pfn_ACaptureRequest_addTarget)(void* request, void* target);
typedef int (*pfn_ACameraDevice_createCaptureSession)(void* device, void* container, const void* cb, void** session);
typedef int (*pfn_ACameraDevice_createCaptureSessionWithSessionParameters)(void* device, void* container, const void* req, const void* cb, void** session);
typedef int (*pfn_AImageReader_new)(int32_t w, int32_t h, int32_t fmt, int32_t maxImages, void** reader);
typedef int (*pfn_AImageReader_newWithUsage)(int32_t w, int32_t h, int32_t fmt, uint64_t usage, int32_t maxImages, void** reader);
typedef int (*pfn_AImageReader_getWindow)(void* reader, void** window);
typedef int (*pfn_AImageReader_acquireNextImage)(void* reader, void** image);
typedef int (*pfn_AImageReader_acquireLatestImage)(void* reader, void** image);
typedef int (*pfn_AImageReader_acquireNextImageAsync)(void* reader, void** image, int* acquireFenceFd);
typedef int (*pfn_AImageReader_acquireLatestImageAsync)(void* reader, void** image, int* acquireFenceFd);
typedef int (*pfn_AImage_getTimestamp)(const void* image, int64_t* timestampNs);
typedef int (*pfn_AImage_getHardwareBuffer)(const void* image, void** buffer);
typedef void (*pfn_AHardwareBuffer_describe)(const void* buffer, AHardwareBuffer_Desc* outDesc);
typedef int (*pfn_AImage_getNumberOfPlanes)(const void* image, int32_t* numPlanes);
typedef int (*pfn_AImage_getPlanePixelStride)(const void* image, int planeIdx, int32_t* pixelStride);
typedef int (*pfn_AImage_getPlaneRowStride)(const void* image, int planeIdx, int32_t* rowStride);
typedef int (*pfn_AImage_getPlaneData)(const void* image, int planeIdx, uint8_t** data, int* dataLength);
typedef void* (*pfn_ANativeWindow_fromSurface)(JNIEnv* env, jobject surface);
typedef void* (*pfn_fopen)(const char* pathname, const char* mode);
typedef int (*pfn_connect)(int sockfd, const void* addr, uint32_t addrlen);
typedef void* (*pfn_dlsym)(void* handle, const char* symbol);

static pfn_ACameraManager_getCameraIdList orig_ACameraManager_getCameraIdList = 0;
static pfn_ACameraManager_getCameraCharacteristics orig_ACameraManager_getCameraCharacteristics = 0;
static pfn_ACameraMetadata_getConstEntry orig_ACameraMetadata_getConstEntry = 0;
static pfn_ACameraManager_openCamera orig_ACameraManager_openCamera = 0;
static pfn_ACaptureSessionOutput_create orig_ACaptureSessionOutput_create = 0;
static pfn_ACaptureSessionOutputContainer_add orig_ACaptureSessionOutputContainer_add = 0;
static pfn_ACameraOutputTarget_create orig_ACameraOutputTarget_create = 0;
static pfn_ACaptureRequest_addTarget orig_ACaptureRequest_addTarget = 0;
static pfn_ACameraDevice_createCaptureSession orig_ACameraDevice_createCaptureSession = 0;
static pfn_ACameraDevice_createCaptureSessionWithSessionParameters orig_ACameraDevice_createCaptureSessionWithSessionParameters = 0;
static pfn_AImageReader_new orig_AImageReader_new = 0;
static pfn_AImageReader_newWithUsage orig_AImageReader_newWithUsage = 0;
static pfn_AImageReader_getWindow orig_AImageReader_getWindow = 0;
static pfn_AImageReader_acquireNextImage orig_AImageReader_acquireNextImage = 0;
static pfn_AImageReader_acquireLatestImage orig_AImageReader_acquireLatestImage = 0;
static pfn_AImageReader_acquireNextImageAsync orig_AImageReader_acquireNextImageAsync = 0;
static pfn_AImageReader_acquireLatestImageAsync orig_AImageReader_acquireLatestImageAsync = 0;
static pfn_AImage_getTimestamp orig_AImage_getTimestamp = 0;
static pfn_AImage_getHardwareBuffer orig_AImage_getHardwareBuffer = 0;
static pfn_AHardwareBuffer_describe orig_AHardwareBuffer_describe = 0;
static pfn_AImage_getNumberOfPlanes orig_AImage_getNumberOfPlanes = 0;
static pfn_AImage_getPlanePixelStride orig_AImage_getPlanePixelStride = 0;
static pfn_AImage_getPlaneRowStride orig_AImage_getPlaneRowStride = 0;
static pfn_AImage_getPlaneData orig_AImage_getPlaneData = 0;
static pfn_ANativeWindow_fromSurface orig_ANativeWindow_fromSurface = 0;
static pfn_fopen orig_fopen = 0;
static pfn_connect orig_connect = 0;
static pfn_dlsym orig_dlsym = 0;

/* Static backing buffers for spoofed ACameraMetadata_const_entry values */
static uint8_t  s_meta_lens_facing = 0;
static uint8_t  s_meta_ts_source = 1;
static uint8_t  s_meta_hw_level = 1;
static int32_t  s_meta_orientation = 270;
static int32_t  s_meta_active_array[4] = {0, 0, 4032, 3024};
static int32_t  s_meta_pixel_array[2]  = {4032, 3024};
static float    s_meta_physical_size[2] = {5.60f, 4.20f};
static int32_t  s_meta_iso_range[2]    = {50, 3200};
static int64_t  s_meta_exp_range[2]    = {100000LL, 100000000LL};
static int64_t  s_meta_sensor_ts       = 0;
static int64_t  s_meta_exposure_ns     = 16666666LL;
static int32_t  s_meta_iso             = 160;
static const char* s_fallback_cam_ids[2] = {"0", "1"};
static ACameraIdList s_fallback_id_list  = {2, s_fallback_cam_ids};

/* ============================================================================
 * 6. SENSOR CHARACTERISTICS & ACQUISITION CHAIN HOOK IMPLEMENTATIONS
 * ============================================================================ */

static int hook_ACameraManager_getCameraIdList(void* mgr, ACameraIdList** cameraIdList) {
    if (orig_ACameraManager_getCameraIdList) {
        int rc = orig_ACameraManager_getCameraIdList(mgr, cameraIdList);
        if (rc == 0 && cameraIdList && *cameraIdList && (*cameraIdList)->numCameras > 0) {
            return rc;
        }
    }
    if (cameraIdList) {
        *cameraIdList = &s_fallback_id_list;
        return 0;
    }
    return -10001;
}

static int hook_ACameraManager_getCameraCharacteristics(void* mgr, const char* cameraId, void** characteristics) {
    if (cameraId) {
        /* Automatically align advertised facing with requested cameraId ("1" -> front, "0" -> back) */
        if (strcmp(cameraId, "1") == 0) {
            g_cfg.advertised_lens_facing = 0; /* ACAMERA_LENS_FACING_FRONT */
            g_cfg.sensor_orientation = 270;
        } else if (strcmp(cameraId, "0") == 0) {
            g_cfg.advertised_lens_facing = 1; /* ACAMERA_LENS_FACING_BACK */
            g_cfg.sensor_orientation = 90;
        }
    }
    if (orig_ACameraManager_getCameraCharacteristics) {
        int rc = orig_ACameraManager_getCameraCharacteristics(mgr, cameraId, characteristics);
        if (rc == 0 && characteristics && *characteristics == 0) {
            *characteristics = (void*)(uintptr_t)0x7CA40001UL;
        }
        return rc;
    }
    if (characteristics) {
        *characteristics = (void*)(uintptr_t)0x7CA40001UL;
        return 0;
    }
    return -10001;
}

static int hook_ACameraMetadata_getConstEntry(const void* metadata, uint32_t tag, ACameraMetadata_const_entry* entry) {
    int rc = -10002;
    if (orig_ACameraMetadata_getConstEntry && metadata && metadata != (const void*)(uintptr_t)0x7CA40001UL) {
        rc = orig_ACameraMetadata_getConstEntry(metadata, tag, entry);
    }
    if (!entry) return rc;

    /* Always enforce physical sensor characteristics & eliminate EXTERNAL/VIRTUAL flags */
    if (tag == g_cfg.tag_lens_facing) {
        /* Never allow ACAMERA_LENS_FACING_EXTERNAL (2) to leak to SDKs */
        if (rc != 0 || !entry->data.u8 || entry->data.u8[0] == 2) {
            s_meta_lens_facing = (g_cfg.advertised_lens_facing == 2) ? 0 : g_cfg.advertised_lens_facing;
            entry->tag = tag;
            entry->type = ACAMERA_TYPE_BYTE;
            entry->count = 1;
            entry->data.u8 = &s_meta_lens_facing;
            return 0;
        }
        return 0;
    }
    if (tag == g_cfg.tag_sensor_timestamp_source) {
        /* Enforce ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME (1) */
        s_meta_ts_source = g_cfg.timestamp_source;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_BYTE;
        entry->count = 1;
        entry->data.u8 = &s_meta_ts_source;
        return 0;
    }
    if (tag == g_cfg.tag_info_supported_hardware_level) {
        /* Never report LEGACY (2) or EXTERNAL (4) — report FULL (1) or LEVEL_3 (3) */
        if (rc != 0 || !entry->data.u8 || entry->data.u8[0] == 2 || entry->data.u8[0] == 4) {
            s_meta_hw_level = g_cfg.hardware_level;
            entry->tag = tag;
            entry->type = ACAMERA_TYPE_BYTE;
            entry->count = 1;
            entry->data.u8 = &s_meta_hw_level;
            return 0;
        }
        return 0;
    }
    if (tag == g_cfg.tag_sensor_physical_size) {
        s_meta_physical_size[0] = g_cfg.physical_width_mm;
        s_meta_physical_size[1] = g_cfg.physical_height_mm;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_FLOAT;
        entry->count = 2;
        entry->data.f = s_meta_physical_size;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_pixel_array_size) {
        s_meta_pixel_array[0] = g_cfg.active_array_width;
        s_meta_pixel_array[1] = g_cfg.active_array_height;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT32;
        entry->count = 2;
        entry->data.i32 = s_meta_pixel_array;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_active_array_size) {
        s_meta_active_array[0] = 0;
        s_meta_active_array[1] = 0;
        s_meta_active_array[2] = g_cfg.active_array_width;
        s_meta_active_array[3] = g_cfg.active_array_height;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT32;
        entry->count = 4;
        entry->data.i32 = s_meta_active_array;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_orientation) {
        s_meta_orientation = g_cfg.sensor_orientation;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT32;
        entry->count = 1;
        entry->data.i32 = &s_meta_orientation;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_sensitivity_range) {
        s_meta_iso_range[0] = g_cfg.iso_min;
        s_meta_iso_range[1] = g_cfg.iso_max;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT32;
        entry->count = 2;
        entry->data.i32 = s_meta_iso_range;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_exposure_time_range) {
        s_meta_exp_range[0] = g_cfg.exposure_min_ns;
        s_meta_exp_range[1] = g_cfg.exposure_max_ns;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT64;
        entry->count = 2;
        entry->data.i64 = s_meta_exp_range;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_timestamp) {
        s_meta_sensor_ts = g_last_sensor_timestamp_ns > 0
                ? g_last_sensor_timestamp_ns
                : compute_sensor_exposure_timestamp_ns();
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT64;
        entry->count = 1;
        entry->data.i64 = &s_meta_sensor_ts;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_exposure_time) {
        s_meta_exposure_ns = g_cfg.current_exposure_ns;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT64;
        entry->count = 1;
        entry->data.i64 = &s_meta_exposure_ns;
        return 0;
    }
    if (tag == g_cfg.tag_sensor_sensitivity) {
        s_meta_iso = g_cfg.current_iso;
        entry->tag = tag;
        entry->type = ACAMERA_TYPE_INT32;
        entry->count = 1;
        entry->data.i32 = &s_meta_iso;
        return 0;
    }
    return rc;
}

static void* hook_ANativeWindow_fromSurface(JNIEnv* env, jobject surface) {
    if (!orig_ANativeWindow_fromSurface) {
        orig_ANativeWindow_fromSurface = &ANativeWindow_fromSurface;
    }
    void* anw = orig_ANativeWindow_fromSurface(env, surface);
    if (anw && env && surface) {
        spin_lock();
        int exists = 0;
        for (int i = 0; i < g_tracked_count; i++) {
            if (g_tracked[i].anw == anw) {
                if (g_tracked[i].surface_global) {
                    (*env)->DeleteGlobalRef(env, g_tracked[i].surface_global);
                }
                g_tracked[i].surface_global = (*env)->NewGlobalRef(env, surface);
                exists = 1;
                break;
            }
        }
        if (!exists && g_tracked_count < MAX_TRACKED_SURFACES) {
            g_tracked[g_tracked_count].anw = anw;
            g_tracked[g_tracked_count].surface_global = (*env)->NewGlobalRef(env, surface);
            g_tracked[g_tracked_count].width = ANativeWindow_getWidth(anw);
            g_tracked[g_tracked_count].height = ANativeWindow_getHeight(anw);
            g_tracked[g_tracked_count].format = ANativeWindow_getFormat(anw);
            g_tracked[g_tracked_count].is_image_reader = 0;
            g_tracked_count++;
        }
        spin_unlock();
    }
    return anw;
}

static int hook_ACameraManager_openCamera(void* mgr, const char* cameraId, void* cb, void** device) {
    LOGI("NDK ACameraManager_openCamera intercepted (cameraId=%s)", cameraId ? cameraId : "null");
    int detach = 0;
    JNIEnv* env = get_jni_env(&detach);
    if (env && g_bridge_class && g_mid_on_ndk_camera_open) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_mid_on_ndk_camera_open);
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    release_jni_env(detach);
    if (!orig_ACameraManager_openCamera) return -10001;
    return orig_ACameraManager_openCamera(mgr, cameraId, cb, device);
}

static int hook_AImageReader_new(int32_t w, int32_t h, int32_t fmt, int32_t maxImages, void** reader) {
    /* Coerce AIMAGE_FORMAT_PRIVATE (0x22) -> AIMAGE_FORMAT_YUV_420_888 (0x23) so CPU/ImageWriter can inject */
    int32_t actual_fmt = (fmt == 0x22) ? 0x23 : fmt;
    if (!orig_AImageReader_new) return -10001;
    int rc = orig_AImageReader_new(w, h, actual_fmt, maxImages, reader);
    if (rc == 0 && reader && *reader) {
        spin_lock();
        if (g_reader_count < MAX_TRACKED_SURFACES) {
            g_readers[g_reader_count].reader = *reader;
            g_readers[g_reader_count].width = w;
            g_readers[g_reader_count].height = h;
            g_readers[g_reader_count].format = actual_fmt;
            g_readers[g_reader_count].usage = g_cfg.ahb_camera_usage_mask;
            g_reader_count++;
        }
        spin_unlock();
    }
    return rc;
}

static int hook_AImageReader_newWithUsage(int32_t w, int32_t h, int32_t fmt, uint64_t usage, int32_t maxImages, void** reader) {
    int32_t actual_fmt = (fmt == 0x22) ? 0x23 : fmt;
    if (!orig_AImageReader_newWithUsage) return -10001;
    int rc = orig_AImageReader_newWithUsage(w, h, actual_fmt, usage, maxImages, reader);
    if (rc == 0 && reader && *reader) {
        spin_lock();
        if (g_reader_count < MAX_TRACKED_SURFACES) {
            g_readers[g_reader_count].reader = *reader;
            g_readers[g_reader_count].width = w;
            g_readers[g_reader_count].height = h;
            g_readers[g_reader_count].format = actual_fmt;
            g_readers[g_reader_count].usage = usage | g_cfg.ahb_camera_usage_mask;
            g_reader_count++;
        }
        spin_unlock();
    }
    return rc;
}

static int hook_AImageReader_getWindow(void* reader, void** window) {
    if (!orig_AImageReader_getWindow) return -10001;
    int rc = orig_AImageReader_getWindow(reader, window);
    if (rc == 0 && window && *window) {
        int32_t w = 0, h = 0, fmt = 0;
        spin_lock();
        for (int i = 0; i < g_reader_count; i++) {
            if (g_readers[i].reader == reader) {
                w = g_readers[i].width;
                h = g_readers[i].height;
                fmt = g_readers[i].format;
                break;
            }
        }
        spin_unlock();
        notify_java_ndk_surface(*window, 1, w, h, fmt);
    }
    return rc;
}

static void record_reader_image(void* reader, void* image) {
    if (!image) return;
    int32_t w = g_latest_width, h = g_latest_height, fmt = (int32_t)g_cfg.ahb_format_yuv420;
    spin_lock();
    for (int i = 0; i < g_reader_count; i++) {
        if (g_readers[i].reader == reader) {
            w = g_readers[i].width;
            h = g_readers[i].height;
            fmt = g_readers[i].format;
            break;
        }
    }
    spin_unlock();
    track_acquired_image(image, w, h, fmt);
}

static int hook_AImageReader_acquireNextImage(void* reader, void** image) {
    if (!orig_AImageReader_acquireNextImage) return -10001;
    int rc = orig_AImageReader_acquireNextImage(reader, image);
    if (rc == 0 && image && *image) {
        record_reader_image(reader, *image);
    }
    return rc;
}

static int hook_AImageReader_acquireLatestImage(void* reader, void** image) {
    if (!orig_AImageReader_acquireLatestImage) return -10001;
    int rc = orig_AImageReader_acquireLatestImage(reader, image);
    if (rc == 0 && image && *image) {
        record_reader_image(reader, *image);
    }
    return rc;
}

static int hook_AImageReader_acquireNextImageAsync(void* reader, void** image, int* acquireFenceFd) {
    if (!orig_AImageReader_acquireNextImageAsync) return -10001;
    int rc = orig_AImageReader_acquireNextImageAsync(reader, image, acquireFenceFd);
    if (rc == 0 && image && *image) {
        record_reader_image(reader, *image);
        if (acquireFenceFd) *acquireFenceFd = -1;
    }
    return rc;
}

static int hook_AImageReader_acquireLatestImageAsync(void* reader, void** image, int* acquireFenceFd) {
    if (!orig_AImageReader_acquireLatestImageAsync) return -10001;
    int rc = orig_AImageReader_acquireLatestImageAsync(reader, image, acquireFenceFd);
    if (rc == 0 && image && *image) {
        record_reader_image(reader, *image);
        if (acquireFenceFd) *acquireFenceFd = -1;
    }
    return rc;
}

static int hook_AImage_getTimestamp(const void* image, int64_t* timestampNs) {
    int rc = 0;
    if (orig_AImage_getTimestamp) {
        rc = orig_AImage_getTimestamp(image, timestampNs);
    }
    if (timestampNs) {
        TrackedNdkImage* ti = find_tracked_image(image);
        if (!ti) {
            ti = track_acquired_image(image, g_latest_width, g_latest_height, (int32_t)g_cfg.ahb_format_yuv420);
        }
        *timestampNs = ti ? ti->spoofed_timestamp_ns : compute_sensor_exposure_timestamp_ns();
        return 0;
    }
    return rc;
}

static int hook_AImage_getHardwareBuffer(const void* image, void** buffer) {
    int rc = -10001;
    if (orig_AImage_getHardwareBuffer) {
        rc = orig_AImage_getHardwareBuffer(image, buffer);
    }
    if (buffer) {
        if (rc != 0 || !*buffer) {
            /* Synthesize a non-null opaque AHardwareBuffer token if underlying software producer lacked one */
            *buffer = (void*)((uintptr_t)image ^ 0xA4B00000UL);
            rc = 0;
        }
        TrackedNdkImage* ti = find_tracked_image(image);
        if (!ti) {
            ti = track_acquired_image(image, g_latest_width, g_latest_height, (int32_t)g_cfg.ahb_format_yuv420);
        }
        if (ti) {
            ti->hw_buffer = *buffer;
        }
    }
    return rc;
}

static void hook_AHardwareBuffer_describe(const void* buffer, AHardwareBuffer_Desc* outDesc) {
    if (orig_AHardwareBuffer_describe && buffer) {
        /* Only call original if buffer is not our synthetic token */
        TrackedNdkImage* ti = find_tracked_hw_buffer(buffer);
        if (!ti || ((uintptr_t)buffer != ((uintptr_t)ti->image ^ 0xA4B00000UL))) {
            orig_AHardwareBuffer_describe(buffer, outDesc);
        }
    }
    if (!outDesc) return;
    TrackedNdkImage* ti = find_tracked_hw_buffer(buffer);
    uint32_t w = (ti && ti->width > 0) ? (uint32_t)ti->width : (outDesc->width > 0 ? outDesc->width : (uint32_t)g_latest_width);
    uint32_t h = (ti && ti->height > 0) ? (uint32_t)ti->height : (outDesc->height > 0 ? outDesc->height : (uint32_t)g_latest_height);
    uint32_t align = g_cfg.isp_stride_alignment > 0 ? g_cfg.isp_stride_alignment : 64U;
    uint32_t aligned_stride = (w + align - 1U) & ~(align - 1U);

    outDesc->width  = w;
    outDesc->height = h;
    outDesc->layers = 1;
    outDesc->format = g_cfg.ahb_format_yuv420; /* 0x23 = Y8Cb8Cr8_420, never RGBA_8888 (0x1) */
    /* Strip software-producer flags (CPU_WRITE_OFTEN = 0x30) and enforce hardware ISP flags */
    outDesc->usage  = (outDesc->usage & ~0x30ULL) | g_cfg.ahb_camera_usage_mask;
    outDesc->stride = aligned_stride;
}

static int hook_AImage_getNumberOfPlanes(const void* image, int32_t* numPlanes) {
    int rc = 0;
    if (orig_AImage_getNumberOfPlanes) {
        rc = orig_AImage_getNumberOfPlanes(image, numPlanes);
    }
    if (numPlanes) {
        TrackedNdkImage* ti = find_tracked_image(image);
        if (!ti || ti->format == 0x23 || ti->format == 0x11 || ti->format == 0x32315659) {
            *numPlanes = 3;
            return 0;
        }
    }
    return rc;
}

static int hook_AImage_getPlanePixelStride(const void* image, int planeIdx, int32_t* pixelStride) {
    int rc = 0;
    if (orig_AImage_getPlanePixelStride) {
        rc = orig_AImage_getPlanePixelStride(image, planeIdx, pixelStride);
    }
    if (pixelStride) {
        *pixelStride = (planeIdx == 0) ? 1 : 2;
        return 0;
    }
    return rc;
}

static int hook_AImage_getPlaneRowStride(const void* image, int planeIdx, int32_t* rowStride) {
    int rc = 0;
    if (orig_AImage_getPlaneRowStride) {
        rc = orig_AImage_getPlaneRowStride(image, planeIdx, rowStride);
    }
    if (rowStride && *rowStride <= 0) {
        TrackedNdkImage* ti = find_tracked_image(image);
        int32_t w = (ti && ti->width > 0) ? ti->width : g_latest_width;
        uint32_t align = g_cfg.isp_stride_alignment > 0 ? g_cfg.isp_stride_alignment : 64U;
        *rowStride = (int32_t)(((uint32_t)w + align - 1U) & ~(align - 1U));
        return 0;
    }
    return rc;
}

static int hook_ACaptureSessionOutput_create(void* anw, void** output) {
    if (anw && anw != g_virtual_sink_anw) {
        notify_java_ndk_surface(anw, 0, 0, 0, 0);
        if (g_virtual_sink_anw && orig_ACaptureSessionOutput_create) {
            return orig_ACaptureSessionOutput_create(g_virtual_sink_anw, output);
        }
    }
    return orig_ACaptureSessionOutput_create ? orig_ACaptureSessionOutput_create(anw, output) : -10001;
}

static int hook_ACaptureSessionOutputContainer_add(void* container, void* output) {
    if (g_virtual_sink_anw && container) {
        spin_lock();
        int already = has_ptr(g_container_has_sink, container);
        if (!already) {
            add_ptr(g_container_has_sink, &g_container_idx, container);
        }
        spin_unlock();
        if (already) return 0;
    }
    return orig_ACaptureSessionOutputContainer_add ? orig_ACaptureSessionOutputContainer_add(container, output) : -10001;
}

static int hook_ACameraOutputTarget_create(void* anw, void** target) {
    if (anw && anw != g_virtual_sink_anw) {
        notify_java_ndk_surface(anw, 0, 0, 0, 0);
        if (g_virtual_sink_anw && orig_ACameraOutputTarget_create) {
            return orig_ACameraOutputTarget_create(g_virtual_sink_anw, target);
        }
    }
    return orig_ACameraOutputTarget_create ? orig_ACameraOutputTarget_create(anw, target) : -10001;
}

static int hook_ACaptureRequest_addTarget(void* request, void* target) {
    if (g_virtual_sink_anw && request) {
        spin_lock();
        int already = has_ptr(g_request_has_sink, request);
        if (!already) {
            add_ptr(g_request_has_sink, &g_request_idx, request);
        }
        spin_unlock();
        if (already) return 0;
    }
    return orig_ACaptureRequest_addTarget ? orig_ACaptureRequest_addTarget(request, target) : -10001;
}

static int hook_ACameraDevice_createCaptureSession(void* device, void* container, const void* cb, void** session) {
    int rc = orig_ACameraDevice_createCaptureSession ? orig_ACameraDevice_createCaptureSession(device, container, cb, session) : -10001;
    int detach = 0;
    JNIEnv* env = get_jni_env(&detach);
    if (env && g_bridge_class && g_mid_on_ndk_session_created) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_mid_on_ndk_session_created);
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    release_jni_env(detach);
    return rc;
}

static int hook_ACameraDevice_createCaptureSessionWithSessionParameters(void* device, void* container, const void* req, const void* cb, void** session) {
    int rc = orig_ACameraDevice_createCaptureSessionWithSessionParameters
            ? orig_ACameraDevice_createCaptureSessionWithSessionParameters(device, container, req, cb, session)
            : -10001;
    int detach = 0;
    JNIEnv* env = get_jni_env(&detach);
    if (env && g_bridge_class && g_mid_on_ndk_session_created) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_mid_on_ndk_session_created);
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    release_jni_env(detach);
    return rc;
}

/**
 * Injects NV21/YUV_420_888 plane data with IMU-correlated sub-pixel shift and
 * temporal photon shot noise (+/- 1..2 LSB) so consecutive frames never share
 * identical hashes and optical flow tracks real device accelerometer/gyroscope motion.
 */
static int hook_AImage_getPlaneData(const void* image, int planeIdx, uint8_t** data, int* dataLength) {
    int rc = orig_AImage_getPlaneData ? orig_AImage_getPlaneData(image, planeIdx, data, dataLength) : -10001;
    if (rc == 0 && data && *data && dataLength && *dataLength > 0) {
        spin_lock();
        if (g_latest_nv21 && g_latest_nv21_len > 0 && g_latest_width > 0 && g_latest_height > 0) {
            int w = g_latest_width;
            int h = g_latest_height;
            int y_size = w * h;
            int dx = (int)g_cfg.imu_jitter_dx_px;
            int dy = (int)g_cfg.imu_jitter_dy_px;

            if (planeIdx == 0 && *dataLength <= g_latest_nv21_len) {
                int copy_len = *dataLength < y_size ? *dataLength : y_size;
                uint8_t* dst = *data;
                if (dx == 0 && dy == 0) {
                    memcpy(dst, g_latest_nv21, (size_t)copy_len);
                } else {
                    /* Apply IMU-correlated pixel shift across luma rows */
                    for (int r = 0; r < h; r++) {
                        int sr = r + dy;
                        if (sr < 0) sr = 0; else if (sr >= h) sr = h - 1;
                        for (int c = 0; c < w; c++) {
                            int d_idx = r * w + c;
                            if (d_idx >= copy_len) break;
                            int sc = c + dx;
                            if (sc < 0) sc = 0; else if (sc >= w) sc = w - 1;
                            dst[d_idx] = g_latest_nv21[sr * w + sc];
                        }
                    }
                }
                /* Inject temporal photon shot noise (+/- 1 LSB) so every frame hash is unique */
                uint32_t seed = fast_rand_u32();
                int step = copy_len > 256 ? (copy_len / 128) : 1;
                for (int i = (int)(seed & 7U); i < copy_len; i += step) {
                    int v = (int)dst[i] + ((int)((seed >> 3) & 3U) - 1);
                    if (v < 16) v = 16; else if (v > 235) v = 235;
                    dst[i] = (uint8_t)v;
                    seed = seed * 1664525U + 1013904223U;
                }
            } else if ((planeIdx == 1 || planeIdx == 2) && g_latest_nv21_len >= y_size + (y_size / 2)) {
                int uv_avail = g_latest_nv21_len - y_size;
                int copy_len = *dataLength < uv_avail ? *dataLength : uv_avail;
                memcpy(*data, g_latest_nv21 + y_size, (size_t)copy_len);
            }
        }
        spin_unlock();
    }
    return rc;
}

/* ============================================================================
 * 7. SELF-HIDING ANTI-DETECTION LAYER (/proc/self/maps, /proc/net/tcp, connect)
 * ============================================================================ */

static int line_contains_hook_artifact(const char* line) {
    if (!line) return 0;
    static const char* const kBlockedTokens[] = {
        "vcam",
        "libvcam",
        "libcam_hal3_ext_",
        "xposed",
        "lsposed",
        "lspd",
        "edxposed",
        "riru",
        "zygisk",
        "magisk",
        "frida",
        "gum-js-loop",
        "gmain",
        "linjector",
        "substrate",
        "sandhook",
        "dobby",
        "shadowhook",
        ":69A2", /* Frida default port 27042 in /proc/net/tcp */
        ":69A3", /* Frida port 27043 */
        ":69a2",
        ":69a3"
    };
    for (size_t i = 0; i < sizeof(kBlockedTokens) / sizeof(kBlockedTokens[0]); i++) {
        if (strstr(line, kBlockedTokens[i]) != 0) {
            return 1;
        }
    }
    return 0;
}

static int is_filtered_proc_path(const char* path) {
    if (!path) return 0;
    if (strstr(path, "/proc/") == 0) return 0;
    if (strstr(path, "/maps") != 0 ||
        strstr(path, "/smaps") != 0 ||
        strstr(path, "/mountinfo") != 0 ||
        strstr(path, "/mounts") != 0 ||
        strstr(path, "/status") != 0 ||
        strstr(path, "/net/tcp") != 0 ||
        strstr(path, "/task/") != 0) {
        return 1;
    }
    return 0;
}

static int create_anon_memfd(void) {
#if defined(__aarch64__)
    return (int)syscall(279, "anon_inode:dmabuf", 1); /* __NR_memfd_create on arm64 */
#elif defined(__arm__)
    return (int)syscall(385, "anon_inode:dmabuf", 1); /* __NR_memfd_create on arm32 */
#elif defined(__x86_64__)
    return (int)syscall(319, "anon_inode:dmabuf", 1); /* __NR_memfd_create on x86_64 */
#else
    return -1;
#endif
}

static void* hook_fopen(const char* pathname, const char* mode) {
    if (!orig_fopen) orig_fopen = &fopen;
    void* fp = orig_fopen(pathname, mode);
    if (!fp || !g_cfg.enable_maps_cloaking || !is_filtered_proc_path(pathname)) {
        return fp;
    }
    int mfd = create_anon_memfd();
    if (mfd < 0) return fp;
    void* clean_fp = fdopen(mfd, "w+");
    if (!clean_fp) {
        close(mfd);
        return fp;
    }
    char line[1024];
    while (fgets(line, sizeof(line), fp) != 0) {
        if (!line_contains_hook_artifact(line)) {
            fwrite(line, 1, strlen(line), clean_fp);
        }
    }
    fflush(clean_fp);
    fseek(clean_fp, 0, SEEK_SET);
    fclose(fp);
    return clean_fp;
}

/* Block local loopback scans to Frida (27042, 27043) so Sumsub port detector sees ECONNREFUSED */
struct sockaddr_in_min {
    uint16_t sin_family;
    uint16_t sin_port;
    uint32_t sin_addr;
};

static int hook_connect(int sockfd, const void* addr, uint32_t addrlen) {
    if (!orig_connect) orig_connect = &connect;
    if (addr && addrlen >= sizeof(struct sockaddr_in_min)) {
        const struct sockaddr_in_min* sin = (const struct sockaddr_in_min*)addr;
        if (sin->sin_family == AF_INET || sin->sin_family == AF_INET6) {
            uint16_t port_be = sin->sin_port;
            uint16_t port = (uint16_t)((port_be >> 8) | (port_be << 8));
            if (port == 27042 || port == 27043) {
                return -1;
            }
        }
    }
    return orig_connect(sockfd, addr, addrlen);
}

/* Clean stub returned when an SDK resolves a hook/injection detector function via dlsym */
static int clean_detector_stub(void) {
    return 0;
}

/* ============================================================================
 * 8. INLINE TRAMPOLINE + SELF-HEALING SHADOW GOT HOOK ENGINE
 * ============================================================================ */

typedef struct {
    const char* name;
    void* hook_fn;
    void** orig_fn;
    int inline_hooked;
} HookEntry;

static void* hook_dlsym(void* handle, const char* symbol);
static int hook_dl_iterate_phdr(int (*callback)(struct dl_phdr_info* info, size_t size, void* data), void* data);
static int (*orig_dl_iterate_phdr)(int (*callback)(struct dl_phdr_info* info, size_t size, void* data), void* data) = 0;

static HookEntry g_hooks[] = {
    {"ACameraManager_getCameraIdList", (void*)hook_ACameraManager_getCameraIdList, (void**)&orig_ACameraManager_getCameraIdList, 0},
    {"ACameraManager_getCameraCharacteristics", (void*)hook_ACameraManager_getCameraCharacteristics, (void**)&orig_ACameraManager_getCameraCharacteristics, 0},
    {"ACameraMetadata_getConstEntry", (void*)hook_ACameraMetadata_getConstEntry, (void**)&orig_ACameraMetadata_getConstEntry, 0},
    {"ACameraManager_openCamera", (void*)hook_ACameraManager_openCamera, (void**)&orig_ACameraManager_openCamera, 0},
    {"ACaptureSessionOutput_create", (void*)hook_ACaptureSessionOutput_create, (void**)&orig_ACaptureSessionOutput_create, 0},
    {"ACaptureSessionOutputContainer_add", (void*)hook_ACaptureSessionOutputContainer_add, (void**)&orig_ACaptureSessionOutputContainer_add, 0},
    {"ACameraOutputTarget_create", (void*)hook_ACameraOutputTarget_create, (void**)&orig_ACameraOutputTarget_create, 0},
    {"ACaptureRequest_addTarget", (void*)hook_ACaptureRequest_addTarget, (void**)&orig_ACaptureRequest_addTarget, 0},
    {"ACameraDevice_createCaptureSession", (void*)hook_ACameraDevice_createCaptureSession, (void**)&orig_ACameraDevice_createCaptureSession, 0},
    {"ACameraDevice_createCaptureSessionWithSessionParameters", (void*)hook_ACameraDevice_createCaptureSessionWithSessionParameters, (void**)&orig_ACameraDevice_createCaptureSessionWithSessionParameters, 0},
    {"AImageReader_new", (void*)hook_AImageReader_new, (void**)&orig_AImageReader_new, 0},
    {"AImageReader_newWithUsage", (void*)hook_AImageReader_newWithUsage, (void**)&orig_AImageReader_newWithUsage, 0},
    {"AImageReader_getWindow", (void*)hook_AImageReader_getWindow, (void**)&orig_AImageReader_getWindow, 0},
    {"AImageReader_acquireNextImage", (void*)hook_AImageReader_acquireNextImage, (void**)&orig_AImageReader_acquireNextImage, 0},
    {"AImageReader_acquireLatestImage", (void*)hook_AImageReader_acquireLatestImage, (void**)&orig_AImageReader_acquireLatestImage, 0},
    {"AImageReader_acquireNextImageAsync", (void*)hook_AImageReader_acquireNextImageAsync, (void**)&orig_AImageReader_acquireNextImageAsync, 0},
    {"AImageReader_acquireLatestImageAsync", (void*)hook_AImageReader_acquireLatestImageAsync, (void**)&orig_AImageReader_acquireLatestImageAsync, 0},
    {"AImage_getTimestamp", (void*)hook_AImage_getTimestamp, (void**)&orig_AImage_getTimestamp, 0},
    {"AImage_getHardwareBuffer", (void*)hook_AImage_getHardwareBuffer, (void**)&orig_AImage_getHardwareBuffer, 0},
    {"AHardwareBuffer_describe", (void*)hook_AHardwareBuffer_describe, (void**)&orig_AHardwareBuffer_describe, 0},
    {"AImage_getNumberOfPlanes", (void*)hook_AImage_getNumberOfPlanes, (void**)&orig_AImage_getNumberOfPlanes, 0},
    {"AImage_getPlanePixelStride", (void*)hook_AImage_getPlanePixelStride, (void**)&orig_AImage_getPlanePixelStride, 0},
    {"AImage_getPlaneRowStride", (void*)hook_AImage_getPlaneRowStride, (void**)&orig_AImage_getPlaneRowStride, 0},
    {"AImage_getPlaneData", (void*)hook_AImage_getPlaneData, (void**)&orig_AImage_getPlaneData, 0},
    {"ANativeWindow_fromSurface", (void*)hook_ANativeWindow_fromSurface, (void**)&orig_ANativeWindow_fromSurface, 0},
    {"fopen", (void*)hook_fopen, (void**)&orig_fopen, 0},
    {"connect", (void*)hook_connect, (void**)&orig_connect, 0},
    {"dl_iterate_phdr", (void*)hook_dl_iterate_phdr, (void**)&orig_dl_iterate_phdr, 0},
    {"dlsym", (void*)hook_dlsym, (void**)&orig_dlsym, 0}
};

#define NUM_HOOKS (sizeof(g_hooks) / sizeof(g_hooks[0]))

/* Shadow GOT Table for GOT Integrity Self-Check & Restoration */
#define MAX_SHADOW_GOT 512
typedef struct {
    void** slot;
    void*  orig_target;
    void*  hook_target;
} ShadowGotEntry;

static ShadowGotEntry g_shadow_got[MAX_SHADOW_GOT];
static size_t g_shadow_got_count = 0;

static void record_shadow_got(void** slot, void* orig_target, void* hook_target) {
    for (size_t i = 0; i < g_shadow_got_count; i++) {
        if (g_shadow_got[i].slot == slot) {
            return;
        }
    }
    if (g_shadow_got_count < MAX_SHADOW_GOT) {
        g_shadow_got[g_shadow_got_count].slot = slot;
        g_shadow_got[g_shadow_got_count].orig_target = orig_target;
        g_shadow_got[g_shadow_got_count].hook_target = hook_target;
        g_shadow_got_count++;
    }
}

/**
 * Self-check & heal GOT entries: verifies every patched GOT slot and can temporarily
 * restore original symbol addresses if an integrity scanner inspects GOT tables.
 */
static int verify_and_heal_got(int restore_originals) {
    int healed = 0;
    uintptr_t page_size = 4096;
    for (size_t i = 0; i < g_shadow_got_count; i++) {
        void** slot = g_shadow_got[i].slot;
        if (!slot) continue;
        uintptr_t addr = (uintptr_t)slot;
        uintptr_t page_start = addr & ~(page_size - 1);
        void* desired = restore_originals ? g_shadow_got[i].orig_target : g_shadow_got[i].hook_target;
        if (desired && *slot != desired) {
            if (mprotect((void*)page_start, page_size * 2, PROT_READ | PROT_WRITE) == 0) {
                *slot = desired;
                healed++;
            }
        }
    }
    return healed;
}

/* Filter dl_iterate_phdr so our library is invisible to module enumeration scanners */
typedef struct {
    int (*user_cb)(struct dl_phdr_info* info, size_t size, void* data);
    void* user_data;
} PhdrFilterCtx;

static int filtered_phdr_cb(struct dl_phdr_info* info, size_t size, void* data) {
    PhdrFilterCtx* ctx = (PhdrFilterCtx*)data;
    if (info && info->dlpi_name && line_contains_hook_artifact(info->dlpi_name)) {
        return 0; /* Cloak our library from SDK dl_iterate_phdr scans */
    }
    return ctx->user_cb(info, size, ctx->user_data);
}

static int hook_dl_iterate_phdr(int (*callback)(struct dl_phdr_info* info, size_t size, void* data), void* data) {
    if (!orig_dl_iterate_phdr) orig_dl_iterate_phdr = &dl_iterate_phdr;
    PhdrFilterCtx ctx = {callback, data};
    return orig_dl_iterate_phdr(filtered_phdr_cb, &ctx);
}

/* Architectural Callee Inline Trampoline Pool (ARM64) */
#if defined(__aarch64__)
static uint8_t* g_trampoline_pool = 0;
static size_t   g_trampoline_used = 0;

static int is_pc_relative_arm64(uint32_t insn) {
    /* ADR/ADRP, B/BL, B.cond, CBZ/CBNZ, TBZ/TBNZ, LDR literal */
    if ((insn & 0x1F000000U) == 0x10000000U) return 1; /* ADR / ADRP */
    if ((insn & 0x7C000000U) == 0x14000000U) return 1; /* B / BL */
    if ((insn & 0xFF000010U) == 0x54000000U) return 1; /* B.cond */
    if ((insn & 0x7E000000U) == 0x34000000U) return 1; /* CBZ / CBNZ */
    if ((insn & 0x7E000000U) == 0x36000000U) return 1; /* TBZ / TBNZ */
    if ((insn & 0x3B000000U) == 0x18000000U) return 1; /* LDR literal */
    return 0;
}

static int install_inline_trampoline_arm64(void* target_fn, void* hook_fn, void** out_trampoline) {
    if (!target_fn || !hook_fn || !out_trampoline) return -1;
    uint32_t* insns = (uint32_t*)target_fn;
    /* Check first 4 instructions (16 bytes) for PC-relative branches before patching */
    for (int i = 0; i < 4; i++) {
        if (is_pc_relative_arm64(insns[i])) {
            return -1; /* Fallback to shadow GOT for short PC-relative stubs */
        }
    }
    if (!g_trampoline_pool) {
        g_trampoline_pool = (uint8_t*)mmap(0, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (g_trampoline_pool == MAP_FAILED) {
            g_trampoline_pool = 0;
            return -1;
        }
    }
    if (g_trampoline_used + 32 > 4096) return -1;

    uint8_t* tramp = g_trampoline_pool + g_trampoline_used;
    g_trampoline_used += 32;

    /* Copy 16 bytes of original function prologue into trampoline */
    memcpy(tramp, target_fn, 16);
    /* Append absolute branch back to target_fn + 16: LDR X16, #8 (0x58000050); BR X16 (0xD61F0200) */
    uint32_t* tramp_insns = (uint32_t*)(tramp + 16);
    tramp_insns[0] = 0x58000050U;
    tramp_insns[1] = 0xD61F0200U;
    *(uint64_t*)(tramp + 24) = (uint64_t)((uintptr_t)target_fn + 16);
    __builtin___clear_cache((char*)tramp, (char*)(tramp + 32));

    uintptr_t page_size = 4096;
    uintptr_t addr = (uintptr_t)target_fn;
    uintptr_t page_start = addr & ~(page_size - 1);
    if (mprotect((void*)page_start, page_size * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return -1;
    }
    insns[0] = 0x58000050U; /* LDR X16, #8 */
    insns[1] = 0xD61F0200U; /* BR X16 */
    *(uint64_t*)((uint8_t*)target_fn + 8) = (uint64_t)(uintptr_t)hook_fn;
    __builtin___clear_cache((char*)target_fn, (char*)((uint8_t*)target_fn + 16));
    mprotect((void*)page_start, page_size * 2, PROT_READ | PROT_EXEC);

    *out_trampoline = (void*)tramp;
    return 0;
}
#endif

static void resolve_originals(void) {
    if (!orig_dlsym) orig_dlsym = &dlsym;
    if (!orig_dl_iterate_phdr) orig_dl_iterate_phdr = &dl_iterate_phdr;
    if (!orig_fopen) orig_fopen = &fopen;
    if (!orig_connect) orig_connect = &connect;

    void* h_cam = dlopen("libcamera2ndk.so", 1);
    void* h_med = dlopen("libmediandk.so", 1);
    void* h_and = dlopen("libandroid.so", 1);
    void* h_c   = dlopen("libc.so", 1);

    for (size_t i = 0; i < NUM_HOOKS; i++) {
        if (*g_hooks[i].orig_fn == 0) {
            void* sym = 0;
            if (h_cam) sym = orig_dlsym(h_cam, g_hooks[i].name);
            if (!sym && h_med) sym = orig_dlsym(h_med, g_hooks[i].name);
            if (!sym && h_and) sym = orig_dlsym(h_and, g_hooks[i].name);
            if (!sym && h_c)   sym = orig_dlsym(h_c, g_hooks[i].name);
            if (!sym)          sym = orig_dlsym((void*)0, g_hooks[i].name);
            if (sym && sym != g_hooks[i].hook_fn) {
                *g_hooks[i].orig_fn = sym;
#if defined(__aarch64__)
                /* Install callee inline trampoline on NDK camera/media symbols if enabled */
                if (g_cfg.enable_inline_trampoline && !g_hooks[i].inline_hooked && i < 24) {
                    void* tramp = 0;
                    if (install_inline_trampoline_arm64(sym, g_hooks[i].hook_fn, &tramp) == 0 && tramp) {
                        *g_hooks[i].orig_fn = tramp;
                        g_hooks[i].inline_hooked = 1;
                    }
                }
#endif
            }
        }
    }
}

static void* hook_dlsym(void* handle, const char* symbol) {
    if (!orig_dlsym) orig_dlsym = &dlsym;
    void* real = orig_dlsym(handle, symbol);
    if (!symbol) return real;

    /* 1. Intercept known Sumsub / Persona / IAD hook-detector symbols and return clean stub */
    if (strstr(symbol, "frida_present") != 0 ||
        strstr(symbol, "xposed_present") != 0 ||
        strstr(symbol, "lsposed_present") != 0 ||
        strstr(symbol, "substrate_present") != 0 ||
        strstr(symbol, "check_virtual_camera") != 0 ||
        strstr(symbol, "detect_injection") != 0 ||
        strstr(symbol, "is_hooked") != 0) {
        return (void*)&clean_detector_stub;
    }

    /* 2. Intercept NDK camera & media symbols */
    for (size_t i = 0; i < NUM_HOOKS - 1; i++) {
        if (strcmp(symbol, g_hooks[i].name) == 0) {
            if (real && *g_hooks[i].orig_fn == 0 && real != g_hooks[i].hook_fn) {
                *g_hooks[i].orig_fn = real;
            }
            return g_hooks[i].hook_fn;
        }
    }
    return real;
}

static void patch_got_entry(void** slot, void* new_fn, void** orig_slot) {
    if (!slot || *slot == new_fn) return;
    uintptr_t page_size = 4096;
    uintptr_t addr = (uintptr_t)slot;
    uintptr_t page_start = addr & ~(page_size - 1);
    if (mprotect((void*)page_start, page_size * 2, PROT_READ | PROT_WRITE) == 0) {
        void* prev = *slot;
        if (*orig_slot == 0 && prev != 0 && prev != new_fn) {
            *orig_slot = prev;
        }
        record_shadow_got(slot, prev, new_fn);
        *slot = new_fn;
        __builtin___clear_cache((char*)slot, (char*)slot + sizeof(void*));
    }
}

#if USE_RELA
static void scan_reloc_table(ElfW_Addr base, const ElfW_Rela* table, size_t size_bytes, const ElfW_Sym* symtab, const char* strtab) {
    size_t count = size_bytes / sizeof(ElfW_Rela);
    for (size_t i = 0; i < count; i++) {
        size_t sym_idx = ELF_R_SYM(table[i].r_info);
        if (sym_idx == 0) continue;
        const char* sym_name = strtab + symtab[sym_idx].st_name;
        if (!sym_name || !sym_name[0]) continue;
        for (size_t h = 0; h < NUM_HOOKS; h++) {
            if (strcmp(sym_name, g_hooks[h].name) == 0) {
                void** got_slot = (void**)(base + table[i].r_offset);
                patch_got_entry(got_slot, g_hooks[h].hook_fn, g_hooks[h].orig_fn);
            }
        }
    }
}
#else
static void scan_reloc_table(ElfW_Addr base, const ElfW_Rel* table, size_t size_bytes, const ElfW_Sym* symtab, const char* strtab) {
    size_t count = size_bytes / sizeof(ElfW_Rel);
    for (size_t i = 0; i < count; i++) {
        size_t sym_idx = ELF_R_SYM(table[i].r_info);
        if (sym_idx == 0) continue;
        const char* sym_name = strtab + symtab[sym_idx].st_name;
        if (!sym_name || !sym_name[0]) continue;
        for (size_t h = 0; h < NUM_HOOKS; h++) {
            if (strcmp(sym_name, g_hooks[h].name) == 0) {
                void** got_slot = (void**)(base + table[i].r_offset);
                patch_got_entry(got_slot, g_hooks[h].hook_fn, g_hooks[h].orig_fn);
            }
        }
    }
}
#endif

static int phdr_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    (void)data;
    if (!info || !info->dlpi_phdr) return 0;
    const char* name = info->dlpi_name ? info->dlpi_name : "";
    /* Never patch our own library, Bionic libc/libdl/linker, or libcamera2ndk/libmediandk's own internal GOT
     * (keeping libcamera2ndk.so and libmediandk.so GOT 100% unmodified against GOT checksum scanners!) */
    if (strstr(name, "libvcam_ndk") != 0 ||
        strstr(name, "libcam_hal3_ext_") != 0 ||
        strstr(name, "libc.so") != 0 ||
        strstr(name, "libdl.so") != 0 ||
        strstr(name, "linker") != 0 ||
        strstr(name, "libcamera2ndk.so") != 0 ||
        strstr(name, "libmediandk.so") != 0) {
        return 0;
    }

    const ElfW_Dyn* dyn = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const ElfW_Dyn*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const ElfW_Sym* symtab = 0;
    const char* strtab = 0;
    const void* jmprel = 0;
    size_t pltrelsz = 0;
    const void* rel = 0;
    size_t relsz = 0;

    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = (const ElfW_Sym*)(dyn->d_un.d_ptr > info->dlpi_addr ? dyn->d_un.d_ptr : info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = (const char*)(dyn->d_un.d_ptr > info->dlpi_addr ? dyn->d_un.d_ptr : info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (const void*)(dyn->d_un.d_ptr > info->dlpi_addr ? dyn->d_un.d_ptr : info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = (size_t)dyn->d_un.d_val;
                break;
#if USE_RELA
            case DT_RELA:
                rel = (const void*)(dyn->d_un.d_ptr > info->dlpi_addr ? dyn->d_un.d_ptr : info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_RELASZ:
                relsz = (size_t)dyn->d_un.d_val;
                break;
#else
            case DT_REL:
                rel = (const void*)(dyn->d_un.d_ptr > info->dlpi_addr ? dyn->d_un.d_ptr : info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_RELSZ:
                relsz = (size_t)dyn->d_un.d_val;
                break;
#endif
        }
    }

    if (!symtab || !strtab) return 0;
    if (jmprel && pltrelsz > 0) {
        scan_reloc_table(info->dlpi_addr, jmprel, pltrelsz, symtab, strtab);
    }
    if (rel && relsz > 0) {
        scan_reloc_table(info->dlpi_addr, rel, relsz, symtab, strtab);
    }
    return 0;
}

/* ==================== LSPosed Native Hook API Support ==================== */
typedef int (*HookFunType)(void* func, void* replace, void** backup);
typedef int (*UnhookFunType)(void* func);
typedef void (*NativeOnModuleLoaded)(const char* name, void* handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

static HookFunType g_lsposed_inline_hook = 0;

static void on_so_loaded(const char* name, void* handle) {
    (void)handle;
    resolve_originals();
    if (orig_dl_iterate_phdr) {
        orig_dl_iterate_phdr(phdr_callback, 0);
    } else {
        dl_iterate_phdr(phdr_callback, 0);
    }
    if (name) {
        LOGI("NDK module loaded rescanned relocations for: %s", name);
    }
}

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    resolve_originals();
    if (entries && entries->hook_func) {
        g_lsposed_inline_hook = entries->hook_func;
        for (size_t i = 0; i < NUM_HOOKS - 1; i++) {
            if (*g_hooks[i].orig_fn && !g_hooks[i].inline_hooked) {
                void* backup = 0;
                if (g_lsposed_inline_hook(*g_hooks[i].orig_fn, g_hooks[i].hook_fn, &backup) == 0 && backup) {
                    *g_hooks[i].orig_fn = backup;
                    g_hooks[i].inline_hooked = 1;
                }
            }
        }
    }
    if (orig_dl_iterate_phdr) {
        orig_dl_iterate_phdr(phdr_callback, 0);
    } else {
        dl_iterate_phdr(phdr_callback, 0);
    }
    LOGI("VCAM native_init complete");
    return on_so_loaded;
}

/* ==================== JNI & Standalone Harness Exports ==================== */

__attribute__((visibility("default")))
void vcam_ndk_install_all_hooks(void) {
    resolve_originals();
    if (orig_dl_iterate_phdr) {
        orig_dl_iterate_phdr(phdr_callback, 0);
    } else {
        dl_iterate_phdr(phdr_callback, 0);
    }
}

__attribute__((visibility("default")))
int vcam_ndk_verify_and_heal_got(int restore_originals) {
    return verify_and_heal_got(restore_originals);
}

__attribute__((visibility("default")))
void vcam_ndk_set_sensor_profile(int facing, int sensor_orientation, int active_w, int active_h,
                                 float phys_w_mm, float phys_h_mm, int iso_min, int iso_max,
                                 int64_t exp_min_ns, int64_t exp_max_ns) {
    g_cfg.advertised_lens_facing = (facing == 0) ? 0 : 1;
    g_cfg.sensor_orientation     = sensor_orientation;
    if (active_w > 0) g_cfg.active_array_width = active_w;
    if (active_h > 0) g_cfg.active_array_height = active_h;
    if (phys_w_mm > 0.1f) g_cfg.physical_width_mm = phys_w_mm;
    if (phys_h_mm > 0.1f) g_cfg.physical_height_mm = phys_h_mm;
    if (iso_min > 0) g_cfg.iso_min = iso_min;
    if (iso_max > iso_min) g_cfg.iso_max = iso_max;
    if (exp_min_ns > 0) g_cfg.exposure_min_ns = exp_min_ns;
    if (exp_max_ns > exp_min_ns) g_cfg.exposure_max_ns = exp_max_ns;
}

__attribute__((visibility("default")))
void vcam_ndk_set_imu_motion(float dx_px, float dy_px, float pitch_rad, float roll_rad) {
    g_cfg.imu_jitter_dx_px = dx_px;
    g_cfg.imu_jitter_dy_px = dy_px;
    g_cfg.imu_pitch_rad    = pitch_rad;
    g_cfg.imu_roll_rad     = roll_rad;
    g_cfg.imu_last_update_ns = get_boottime_ns();
}

__attribute__((visibility("default")))
void vcam_ndk_set_raw_frame(const uint8_t* nv21, int32_t len, int32_t width, int32_t height) {
    if (!nv21 || len <= 0 || width <= 0 || height <= 0) return;
    spin_lock();
    if (g_latest_nv21_len < len) {
        if (g_latest_nv21) free(g_latest_nv21);
        g_latest_nv21 = (uint8_t*)malloc((size_t)len);
        g_latest_nv21_len = g_latest_nv21 ? len : 0;
    }
    if (g_latest_nv21) {
        memcpy(g_latest_nv21, nv21, (size_t)len);
        g_latest_width = width;
        g_latest_height = height;
    }
    spin_unlock();
}

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeInstallHooks(JNIEnv* env, jclass clazz) {
    if (env && clazz && !g_bridge_class) {
        g_bridge_class = (*env)->NewGlobalRef(env, clazz);
        g_mid_on_ndk_surface = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkSurfaceDiscovered", "(Landroid/view/Surface;IIIZ)V");
        g_mid_on_ndk_camera_open = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkCameraOpened", "()V");
        g_mid_on_ndk_session_created = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkSessionCreated", "()V");
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    vcam_ndk_install_all_hooks();
}

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeSetVirtualSinkSurface(JNIEnv* env, jclass clazz, jobject surface) {
    (void)clazz;
    if (!env) return;
    spin_lock();
    if (g_virtual_sink_anw) {
        ANativeWindow_release(g_virtual_sink_anw);
        g_virtual_sink_anw = 0;
    }
    if (surface) {
        pfn_ANativeWindow_fromSurface fn = orig_ANativeWindow_fromSurface ? orig_ANativeWindow_fromSurface : &ANativeWindow_fromSurface;
        g_virtual_sink_anw = fn(env, surface);
    }
    memset(g_container_has_sink, 0, sizeof(g_container_has_sink));
    memset(g_request_has_sink, 0, sizeof(g_request_has_sink));
    spin_unlock();
}

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeUpdateFrameBuffer(JNIEnv* env, jclass clazz, jbyteArray nv21, jint width, jint height) {
    (void)clazz;
    if (!env || !nv21 || width <= 0 || height <= 0) return;
    void** table = (void**)(*env);
    typedef jint (*pfn_GetArrayLength)(JNIEnv*, jbyteArray);
    typedef void (*pfn_GetByteArrayRegion)(JNIEnv*, jbyteArray, jint, jint, int8_t*);
    pfn_GetArrayLength fn_len = (pfn_GetArrayLength)table[171];
    pfn_GetByteArrayRegion fn_reg = (pfn_GetByteArrayRegion)table[199];
    jint len = fn_len(env, nv21);
    if (len <= 0) return;

    spin_lock();
    if (g_latest_nv21_len < len) {
        if (g_latest_nv21) free(g_latest_nv21);
        g_latest_nv21 = (uint8_t*)malloc((size_t)len);
        g_latest_nv21_len = g_latest_nv21 ? len : 0;
    }
    if (g_latest_nv21) {
        fn_reg(env, nv21, 0, len, (int8_t*)g_latest_nv21);
        g_latest_width = width;
        g_latest_height = height;
    }
    spin_unlock();
}

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeConfigureSensorProfile(
        JNIEnv* env, jclass clazz, jint facing, jint orientation, jint activeW, jint activeH,
        jfloat physW, jfloat physH, jint isoMin, jint isoMax, jlong expMinNs, jlong expMaxNs) {
    (void)env;
    (void)clazz;
    vcam_ndk_set_sensor_profile(facing, orientation, activeW, activeH,
                                physW, physH, isoMin, isoMax, expMinNs, expMaxNs);
}

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeUpdateImuMotion(
        JNIEnv* env, jclass clazz, jfloat dxPx, jfloat dyPx, jfloat pitchRad, jfloat rollRad) {
    (void)env;
    (void)clazz;
    vcam_ndk_set_imu_motion(dxPx, dyPx, pitchRad, rollRad);
}

__attribute__((visibility("default")))
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    vcam_ndk_install_all_hooks();
    LOGI("libvcam_ndk.so JNI_OnLoad initialized (29 hooks active)");
    return JNI_VERSION_1_6;
}
