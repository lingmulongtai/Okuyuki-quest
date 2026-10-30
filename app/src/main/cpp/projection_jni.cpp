#include <jni.h>
#include <android/native_window_jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_okuyuki_quest_OsmoProjectionSurface_setBufferSize(JNIEnv* env, jobject, jobject surface,
                                                            jint width, jint height) {
    if (!surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    const int result = ANativeWindow_setBuffersGeometry(window, width, height, 0);
    ANativeWindow_release(window);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}
