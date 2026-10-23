#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

typedef int64_t off_t;
typedef long ssize_t;
typedef int pid_t;

#define PROT_READ  0x1
#define PROT_WRITE 0x2
#define PROT_EXEC  0x4
#define RTLD_DEFAULT ((void*) 0)
#define RTLD_NEXT    ((void*) -1L)

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6

extern int __android_log_print(int prio, const char* tag, const char* fmt, ...);
extern void* dlopen(const char* filename, int flag);
extern void* dlsym(void* handle, const char* symbol);
extern int mprotect(void* addr, size_t len, int prot);
extern long sysconf(int name);
extern int strcmp(const char* s1, const char* s2);
extern int strncmp(const char* s1, const char* s2, size_t n);
extern char* strstr(const char* haystack, const char* needle);
extern size_t strlen(const char* s);
extern void* memcpy(void* dest, const void* src, size_t n);
extern void* memset(void* s, int c, size_t n);
extern void* malloc(size_t size);
extern void free(void* ptr);
extern int pthread_mutex_lock(void* mutex);
extern int pthread_mutex_unlock(void* mutex);

#if defined(__LP64__)
#define _SC_PAGESIZE 39
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
#define _SC_PAGESIZE 39
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
static int32_t g_latest_width = 0;
#pragma GCC diagnostic ignored "-Wunused-variable"
static int32_t g_latest_height = 0;
static volatile int g_spinlock = 0;

static inline void spin_lock(void) {
    while (__sync_lock_test_and_set(&g_spinlock, 1)) {}
}
static inline void spin_unlock(void) {
    __sync_lock_release(&g_spinlock);
}

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

/* Track which containers/requests already received g_virtual_sink_anw */
#define MAX_CONTAINERS 64
static void* g_container_has_sink[MAX_CONTAINERS];
static int g_container_idx = 0;

static void* g_request_has_sink[MAX_CONTAINERS];
static int g_request_idx = 0;

static int has_ptr(void** arr, void* ptr) {
    if (!ptr) return 0;
    for (int i = 0; i < MAX_CONTAINERS; i++) {
        if (arr[i] == ptr) return 1;
    }
    return 0;
}

static void add_ptr(void** arr, int* idx, void* ptr) {
    if (!ptr || has_ptr(arr, ptr)) return;
    arr[*idx % MAX_CONTAINERS] = ptr;
    (*idx)++;
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

/* ==================== NDK Camera2 & Media Hook Targets ==================== */

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
typedef int (*pfn_AImage_getPlaneData)(const void* image, int planeIdx, uint8_t** data, int* dataLength);
typedef int (*pfn_AImage_getWidth)(const void* image, int32_t* width);
typedef int (*pfn_AImage_getHeight)(const void* image, int32_t* height);
typedef int (*pfn_AImage_getFormat)(const void* image, int32_t* format);
typedef void* (*pfn_ANativeWindow_fromSurface)(JNIEnv* env, jobject surface);
typedef void* (*pfn_dlsym)(void* handle, const char* symbol);

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
static pfn_AImage_getPlaneData orig_AImage_getPlaneData = 0;
static pfn_AImage_getWidth orig_AImage_getWidth = 0;
static pfn_AImage_getHeight orig_AImage_getHeight = 0;
static pfn_AImage_getFormat orig_AImage_getFormat = 0;
static pfn_ANativeWindow_fromSurface orig_ANativeWindow_fromSurface = 0;
static pfn_dlsym orig_dlsym = 0;

typedef struct {
    void* reader;
    int32_t width;
    int32_t height;
    int32_t format;
} TrackedNdkReader;

static TrackedNdkReader g_readers[MAX_TRACKED_SURFACES];
static int g_reader_count = 0;

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
    return orig_ACameraManager_openCamera(mgr, cameraId, cb, device);
}

static int hook_AImageReader_new(int32_t w, int32_t h, int32_t fmt, int32_t maxImages, void** reader) {
    int rc = orig_AImageReader_new(w, h, fmt, maxImages, reader);
    if (rc == 0 && reader && *reader) {
        LOGI("NDK AImageReader_new intercepted (%dx%d fmt=0x%x)", w, h, fmt);
        spin_lock();
        if (g_reader_count < MAX_TRACKED_SURFACES) {
            g_readers[g_reader_count].reader = *reader;
            g_readers[g_reader_count].width = w;
            g_readers[g_reader_count].height = h;
            g_readers[g_reader_count].format = fmt;
            g_reader_count++;
        }
        spin_unlock();
    }
    return rc;
}

static int hook_AImageReader_newWithUsage(int32_t w, int32_t h, int32_t fmt, uint64_t usage, int32_t maxImages, void** reader) {
    int rc = orig_AImageReader_newWithUsage(w, h, fmt, usage, maxImages, reader);
    if (rc == 0 && reader && *reader) {
        LOGI("NDK AImageReader_newWithUsage intercepted (%dx%d fmt=0x%x)", w, h, fmt);
        spin_lock();
        if (g_reader_count < MAX_TRACKED_SURFACES) {
            g_readers[g_reader_count].reader = *reader;
            g_readers[g_reader_count].width = w;
            g_readers[g_reader_count].height = h;
            g_readers[g_reader_count].format = fmt;
            g_reader_count++;
        }
        spin_unlock();
    }
    return rc;
}

static int hook_AImageReader_getWindow(void* reader, void** window) {
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
        LOGI("NDK AImageReader_getWindow -> ANativeWindow=%p (%dx%d fmt=0x%x)", *window, w, h, fmt);
        notify_java_ndk_surface(*window, 1, w, h, fmt);
    }
    return rc;
}

static int hook_ACaptureSessionOutput_create(void* anw, void** output) {
    if (anw && anw != g_virtual_sink_anw) {
        LOGI("NDK ACaptureSessionOutput_create intercepted ANativeWindow=%p", anw);
        notify_java_ndk_surface(anw, 0, 0, 0, 0);
        if (g_virtual_sink_anw) {
            return orig_ACaptureSessionOutput_create(g_virtual_sink_anw, output);
        }
    }
    return orig_ACaptureSessionOutput_create(anw, output);
}

static int hook_ACaptureSessionOutputContainer_add(void* container, void* output) {
    if (g_virtual_sink_anw && container) {
        spin_lock();
        int already = has_ptr(g_container_has_sink, container);
        if (!already) {
            add_ptr(g_container_has_sink, &g_container_idx, container);
        }
        spin_unlock();
        if (already) {
            /* Avoid ACAMERA_ERROR_INVALID_PARAMETER from duplicate ANativeWindow in container */
            return 0;
        }
    }
    return orig_ACaptureSessionOutputContainer_add(container, output);
}

static int hook_ACameraOutputTarget_create(void* anw, void** target) {
    if (anw && anw != g_virtual_sink_anw) {
        LOGI("NDK ACameraOutputTarget_create intercepted ANativeWindow=%p", anw);
        notify_java_ndk_surface(anw, 0, 0, 0, 0);
        if (g_virtual_sink_anw) {
            return orig_ACameraOutputTarget_create(g_virtual_sink_anw, target);
        }
    }
    return orig_ACameraOutputTarget_create(anw, target);
}

static int hook_ACaptureRequest_addTarget(void* request, void* target) {
    if (g_virtual_sink_anw && request) {
        spin_lock();
        int already = has_ptr(g_request_has_sink, request);
        if (!already) {
            add_ptr(g_request_has_sink, &g_request_idx, request);
        }
        spin_unlock();
        if (already) {
            return 0;
        }
    }
    return orig_ACaptureRequest_addTarget(request, target);
}

static int hook_ACameraDevice_createCaptureSession(void* device, void* container, const void* cb, void** session) {
    LOGI("NDK ACameraDevice_createCaptureSession intercepted");
    int rc = orig_ACameraDevice_createCaptureSession(device, container, cb, session);
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
    LOGI("NDK ACameraDevice_createCaptureSessionWithSessionParameters intercepted");
    int rc = orig_ACameraDevice_createCaptureSessionWithSessionParameters(device, container, req, cb, session);
    int detach = 0;
    JNIEnv* env = get_jni_env(&detach);
    if (env && g_bridge_class && g_mid_on_ndk_session_created) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_mid_on_ndk_session_created);
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    release_jni_env(detach);
    return rc;
}

static int hook_AImage_getPlaneData(const void* image, int planeIdx, uint8_t** data, int* dataLength) {
    int rc = orig_AImage_getPlaneData(image, planeIdx, data, dataLength);
    if (rc == 0 && data && *data && dataLength && *dataLength > 0) {
        spin_lock();
        if (g_latest_nv21 && g_latest_nv21_len > 0 && g_latest_width > 0 && g_latest_height > 0) {
            int y_size = g_latest_width * g_latest_height;
            if (planeIdx == 0 && *dataLength <= g_latest_nv21_len) {
                int copy_len = *dataLength < y_size ? *dataLength : y_size;
                memcpy(*data, g_latest_nv21, copy_len);
            } else if ((planeIdx == 1 || planeIdx == 2) && g_latest_nv21_len >= y_size + (y_size / 2)) {
                int uv_avail = g_latest_nv21_len - y_size;
                int copy_len = *dataLength < uv_avail ? *dataLength : uv_avail;
                memcpy(*data, g_latest_nv21 + y_size, copy_len);
            }
        }
        spin_unlock();
    }
    return rc;
}

typedef struct {
    const char* name;
    void* hook_fn;
    void** orig_fn;
} HookEntry;

static void* hook_dlsym(void* handle, const char* symbol);

static HookEntry g_hooks[] = {
    {"ACameraManager_openCamera", (void*)hook_ACameraManager_openCamera, (void**)&orig_ACameraManager_openCamera},
    {"ACaptureSessionOutput_create", (void*)hook_ACaptureSessionOutput_create, (void**)&orig_ACaptureSessionOutput_create},
    {"ACaptureSessionOutputContainer_add", (void*)hook_ACaptureSessionOutputContainer_add, (void**)&orig_ACaptureSessionOutputContainer_add},
    {"ACameraOutputTarget_create", (void*)hook_ACameraOutputTarget_create, (void**)&orig_ACameraOutputTarget_create},
    {"ACaptureRequest_addTarget", (void*)hook_ACaptureRequest_addTarget, (void**)&orig_ACaptureRequest_addTarget},
    {"ACameraDevice_createCaptureSession", (void*)hook_ACameraDevice_createCaptureSession, (void**)&orig_ACameraDevice_createCaptureSession},
    {"ACameraDevice_createCaptureSessionWithSessionParameters", (void*)hook_ACameraDevice_createCaptureSessionWithSessionParameters, (void**)&orig_ACameraDevice_createCaptureSessionWithSessionParameters},
    {"AImageReader_new", (void*)hook_AImageReader_new, (void**)&orig_AImageReader_new},
    {"AImageReader_newWithUsage", (void*)hook_AImageReader_newWithUsage, (void**)&orig_AImageReader_newWithUsage},
    {"AImageReader_getWindow", (void*)hook_AImageReader_getWindow, (void**)&orig_AImageReader_getWindow},
    {"AImage_getPlaneData", (void*)hook_AImage_getPlaneData, (void**)&orig_AImage_getPlaneData},
    {"ANativeWindow_fromSurface", (void*)hook_ANativeWindow_fromSurface, (void**)&orig_ANativeWindow_fromSurface},
    {"dlsym", (void*)hook_dlsym, (void**)&orig_dlsym}
};

#define NUM_HOOKS (sizeof(g_hooks) / sizeof(g_hooks[0]))

static void resolve_originals(void) {
    if (!orig_dlsym) {
        orig_dlsym = &dlsym;
    }
    void* h_cam = dlopen("libcamera2ndk.so", 1);
    void* h_med = dlopen("libmediandk.so", 1);
    void* h_and = dlopen("libandroid.so", 1);

    for (size_t i = 0; i < NUM_HOOKS; i++) {
        if (*g_hooks[i].orig_fn == 0) {
            void* sym = 0;
            if (h_cam) sym = orig_dlsym(h_cam, g_hooks[i].name);
            if (!sym && h_med) sym = orig_dlsym(h_med, g_hooks[i].name);
            if (!sym && h_and) sym = orig_dlsym(h_and, g_hooks[i].name);
            if (sym && sym != g_hooks[i].hook_fn) {
                *g_hooks[i].orig_fn = sym;
            }
        }
    }
}

static void* hook_dlsym(void* handle, const char* symbol) {
    if (!orig_dlsym) orig_dlsym = &dlsym;
    void* real = orig_dlsym(handle, symbol);
    if (!symbol) return real;
    for (size_t i = 0; i < NUM_HOOKS - 1; i++) {
        if (strcmp(symbol, g_hooks[i].name) == 0) {
            if (real && *g_hooks[i].orig_fn == 0 && real != g_hooks[i].hook_fn) {
                *g_hooks[i].orig_fn = real;
            }
            LOGI("NDK dlsym intercepted lookup for %s", symbol);
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
        if (*orig_slot == 0 && *slot != 0 && *slot != new_fn) {
            *orig_slot = *slot;
        }
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
    if (strstr(name, "libvcam_ndk.so") != 0 ||
        strstr(name, "libc.so") != 0 ||
        strstr(name, "libdl.so") != 0 ||
        strstr(name, "linker") != 0) {
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
    dl_iterate_phdr(phdr_callback, 0);
    if (name) {
        LOGI("NDK module loaded rescanned PLT/GOT for: %s", name);
    }
}

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    resolve_originals();
    if (entries && entries->hook_func) {
        g_lsposed_inline_hook = entries->hook_func;
        for (size_t i = 0; i < NUM_HOOKS - 1; i++) {
            if (*g_hooks[i].orig_fn) {
                void* backup = 0;
                if (g_lsposed_inline_hook(*g_hooks[i].orig_fn, g_hooks[i].hook_fn, &backup) == 0 && backup) {
                    *g_hooks[i].orig_fn = backup;
                }
            }
        }
    }
    dl_iterate_phdr(phdr_callback, 0);
    LOGI("VCAM native_init complete");
    return on_so_loaded;
}

/* ==================== JNI Bridge Exports ==================== */

__attribute__((visibility("default")))
void Java_com_example_vcam_NdkHookEngine_nativeInstallHooks(JNIEnv* env, jclass clazz) {
    if (env && !g_jvm) {
        /* Resolve JavaVM from JNIEnv manually if not initialized via JNI_OnLoad */
        (void)clazz;
    }
    if (env && clazz && !g_bridge_class) {
        g_bridge_class = (*env)->NewGlobalRef(env, clazz);
        g_mid_on_ndk_surface = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkSurfaceDiscovered", "(Landroid/view/Surface;IIIZ)V");
        g_mid_on_ndk_camera_open = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkCameraOpened", "()V");
        g_mid_on_ndk_session_created = (*env)->GetStaticMethodID(env, g_bridge_class, "onNdkSessionCreated", "()V");
        if ((*env)->ExceptionOccurred(env)) (*env)->ExceptionClear(env);
    }
    resolve_originals();
    dl_iterate_phdr(phdr_callback, 0);
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
    /* Use JNI GetArrayLength (slot 171) & GetByteArrayRegion (slot 199) dynamically via raw pointer array */
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
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    resolve_originals();
    dl_iterate_phdr(phdr_callback, 0);
    LOGI("libvcam_ndk.so JNI_OnLoad initialized");
    return JNI_VERSION_1_6;
}
