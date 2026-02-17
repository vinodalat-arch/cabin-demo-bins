#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "image_utils.h"
#include "pose_analyzer.h"

#define TAG "InCabin-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// ---- Existing: YUV to BGR conversion ----

JNIEXPORT jbyteArray JNICALL
Java_com_incabin_NativeLib_nativeYuvToBgr(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray y_array,
    jbyteArray u_array,
    jbyteArray v_array,
    jint y_row_stride,
    jint uv_row_stride,
    jint uv_pixel_stride,
    jint width,
    jint height
) {
    jbyte* y_data = env->GetByteArrayElements(y_array, nullptr);
    jbyte* u_data = env->GetByteArrayElements(u_array, nullptr);
    jbyte* v_data = env->GetByteArrayElements(v_array, nullptr);

    if (!y_data || !u_data || !v_data) {
        LOGE("Failed to get byte array elements");
        if (y_data) env->ReleaseByteArrayElements(y_array, y_data, JNI_ABORT);
        if (u_data) env->ReleaseByteArrayElements(u_array, u_data, JNI_ABORT);
        if (v_data) env->ReleaseByteArrayElements(v_array, v_data, JNI_ABORT);
        return nullptr;
    }

    auto bgr = incabin::yuvToBgr(
        reinterpret_cast<const uint8_t*>(y_data),
        reinterpret_cast<const uint8_t*>(u_data),
        reinterpret_cast<const uint8_t*>(v_data),
        width, height,
        y_row_stride, uv_row_stride, uv_pixel_stride
    );

    env->ReleaseByteArrayElements(y_array, y_data, JNI_ABORT);
    env->ReleaseByteArrayElements(u_array, u_data, JNI_ABORT);
    env->ReleaseByteArrayElements(v_array, v_data, JNI_ABORT);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bgr.size()));
    if (result) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bgr.size()),
                                reinterpret_cast<const jbyte*>(bgr.data()));
    }

    LOGI("YUV->BGR conversion: %dx%d -> %zu bytes", width, height, bgr.size());
    return result;
}

// ---- PoseAnalyzer JNI: Create ----

JNIEXPORT jlong JNICALL
Java_com_incabin_PoseAnalyzerBridge_nativeCreatePoseAnalyzer(
    JNIEnv* env,
    jobject /* this */,
    jobject asset_manager_obj
) {
    AAssetManager* asset_manager = AAssetManager_fromJava(env, asset_manager_obj);
    if (!asset_manager) {
        LOGE("Failed to get AAssetManager from Java object");
        return 0;
    }

    try {
        auto* analyzer = new incabin::PoseAnalyzer(asset_manager);
        LOGI("PoseAnalyzer created at %p", analyzer);
        return reinterpret_cast<jlong>(analyzer);
    } catch (const std::exception& e) {
        LOGE("Failed to create PoseAnalyzer: %s", e.what());
        return 0;
    }
}

// ---- PoseAnalyzer JNI: Analyze ----

JNIEXPORT jstring JNICALL
Java_com_incabin_PoseAnalyzerBridge_nativeAnalyzePose(
    JNIEnv* env,
    jobject /* this */,
    jlong analyzer_ptr,
    jbyteArray bgr_array,
    jint width,
    jint height
) {
    if (analyzer_ptr == 0) {
        LOGE("nativeAnalyzePose: null analyzer pointer");
        return env->NewStringUTF("{\"passenger_count\":0,\"driver_using_phone\":false,"
                                 "\"dangerous_posture\":false,\"child_present\":false,"
                                 "\"child_slouching\":false,\"driver_eating_drinking\":false}");
    }

    auto* analyzer = reinterpret_cast<incabin::PoseAnalyzer*>(analyzer_ptr);

    jbyte* bgr_data = env->GetByteArrayElements(bgr_array, nullptr);
    if (!bgr_data) {
        LOGE("nativeAnalyzePose: failed to get BGR byte array");
        return env->NewStringUTF("{\"passenger_count\":0,\"driver_using_phone\":false,"
                                 "\"dangerous_posture\":false,\"child_present\":false,"
                                 "\"child_slouching\":false,\"driver_eating_drinking\":false}");
    }

    try {
        auto result = analyzer->analyze(
            reinterpret_cast<const uint8_t*>(bgr_data), width, height);

        env->ReleaseByteArrayElements(bgr_array, bgr_data, JNI_ABORT);

        std::string json = result.toJson();
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeAnalyzePose exception: %s", e.what());
        env->ReleaseByteArrayElements(bgr_array, bgr_data, JNI_ABORT);
        return env->NewStringUTF("{\"passenger_count\":0,\"driver_using_phone\":false,"
                                 "\"dangerous_posture\":false,\"child_present\":false,"
                                 "\"child_slouching\":false,\"driver_eating_drinking\":false}");
    }
}

// ---- PoseAnalyzer JNI: Destroy ----

JNIEXPORT void JNICALL
Java_com_incabin_PoseAnalyzerBridge_nativeDestroyPoseAnalyzer(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong analyzer_ptr
) {
    if (analyzer_ptr != 0) {
        auto* analyzer = reinterpret_cast<incabin::PoseAnalyzer*>(analyzer_ptr);
        LOGI("Destroying PoseAnalyzer at %p", analyzer);
        delete analyzer;
    }
}

} // extern "C"
