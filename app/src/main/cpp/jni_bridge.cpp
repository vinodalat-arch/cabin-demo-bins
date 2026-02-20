#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "image_utils.h"
#include "pose_analyzer.h"
#include "face_recognizer.h"
#include "v4l2_camera.h"

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
    if (!result) {
        LOGE("NewByteArray returned null (OOM) for YUV->BGR, size=%zu", bgr.size());
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bgr.size()),
                            reinterpret_cast<const jbyte*>(bgr.data()));

    LOGI("YUV->BGR conversion: %dx%d -> %zu bytes", width, height, bgr.size());
    return result;
}

// ---- BGR to ARGB pixel conversion ----

JNIEXPORT void JNICALL
Java_com_incabin_NativeLib_nativeBgrToArgbPixels(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray bgr_array,
    jintArray pixel_array,
    jint width,
    jint height
) {
    jbyte* bgr = env->GetByteArrayElements(bgr_array, nullptr);
    jint* pixels = env->GetIntArrayElements(pixel_array, nullptr);
    if (!bgr || !pixels) {
        LOGE("nativeBgrToArgbPixels: failed to get array elements");
        if (bgr) env->ReleaseByteArrayElements(bgr_array, bgr, JNI_ABORT);
        if (pixels) env->ReleaseIntArrayElements(pixel_array, pixels, JNI_ABORT);
        return;
    }

    if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        LOGE("nativeBgrToArgbPixels: invalid dimensions %dx%d", width, height);
        env->ReleaseIntArrayElements(pixel_array, pixels, JNI_ABORT);
        env->ReleaseByteArrayElements(bgr_array, bgr, JNI_ABORT);
        return;
    }

    const int count = width * height;
    const uint8_t* src = reinterpret_cast<const uint8_t*>(bgr);
    for (int i = 0; i < count; i++) {
        uint8_t b = src[i * 3];
        uint8_t g = src[i * 3 + 1];
        uint8_t r = src[i * 3 + 2];
        pixels[i] = static_cast<jint>((0xFF << 24) | (r << 16) | (g << 8) | b);
    }

    env->ReleaseIntArrayElements(pixel_array, pixels, 0);
    env->ReleaseByteArrayElements(bgr_array, bgr, JNI_ABORT);
}

// ---- PoseAnalyzer JNI: Create ----

JNIEXPORT jlong JNICALL
Java_com_incabin_PoseAnalyzerBridge_nativeCreatePoseAnalyzer(
    JNIEnv* env,
    jobject /* this */,
    jobject asset_manager_obj,
    jint num_threads,
    jstring thread_affinity_str
) {
    AAssetManager* asset_manager = AAssetManager_fromJava(env, asset_manager_obj);
    if (!asset_manager) {
        LOGE("Failed to get AAssetManager from Java object");
        return 0;
    }

    std::string thread_affinity;
    if (thread_affinity_str) {
        const char* str = env->GetStringUTFChars(thread_affinity_str, nullptr);
        if (str) {
            thread_affinity = str;
            env->ReleaseStringUTFChars(thread_affinity_str, str);
        }
    }

    try {
        auto* analyzer = new incabin::PoseAnalyzer(asset_manager, num_threads, thread_affinity);
        LOGI("PoseAnalyzer created at %p (threads=%d, affinity=%s)",
             analyzer, num_threads, thread_affinity.c_str());
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

// ---- V4L2 Camera JNI ----

JNIEXPORT jstring JNICALL
Java_com_incabin_NativeLib_nativeFindV4l2Device(
    JNIEnv* env,
    jobject /* this */
) {
    std::string path = incabin::V4l2Camera::findCaptureDevice();
    if (path.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(path.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_incabin_NativeLib_nativeCreateV4l2Camera(
    JNIEnv* env,
    jobject /* this */,
    jstring devicePath,
    jint width,
    jint height
) {
    const char* path = env->GetStringUTFChars(devicePath, nullptr);
    if (!path) {
        LOGE("V4L2: Failed to get device path string");
        return 0;
    }

    auto* camera = new incabin::V4l2Camera(path, width, height);
    env->ReleaseStringUTFChars(devicePath, path);

    if (!camera->isOpen()) {
        LOGE("V4L2: Camera failed to open/configure");
        delete camera;
        return 0;
    }

    LOGI("V4L2: Camera created at %p (%dx%d)", camera, camera->width(), camera->height());
    return reinterpret_cast<jlong>(camera);
}

JNIEXPORT jbyteArray JNICALL
Java_com_incabin_NativeLib_nativeGrabBgrFrame(
    JNIEnv* env,
    jobject /* this */,
    jlong cameraPtr
) {
    if (cameraPtr == 0) {
        LOGE("V4L2: nativeGrabBgrFrame: null camera pointer");
        return nullptr;
    }

    auto* camera = reinterpret_cast<incabin::V4l2Camera*>(cameraPtr);
    auto bgr = camera->grabBgrFrame();

    if (bgr.empty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bgr.size()));
    if (!result) {
        LOGE("NewByteArray returned null (OOM) for V4L2 frame, size=%zu", bgr.size());
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bgr.size()),
                            reinterpret_cast<const jbyte*>(bgr.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_incabin_NativeLib_nativeDestroyV4l2Camera(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong cameraPtr
) {
    if (cameraPtr != 0) {
        auto* camera = reinterpret_cast<incabin::V4l2Camera*>(cameraPtr);
        LOGI("V4L2: Destroying camera at %p", camera);
        delete camera;
    }
}

// ---- FaceRecognizer JNI: Create ----

JNIEXPORT jlong JNICALL
Java_com_incabin_FaceRecognizerBridge_nativeCreateFaceRecognizer(
    JNIEnv* env,
    jobject /* this */,
    jobject asset_manager_obj,
    jint num_threads,
    jstring thread_affinity_str
) {
    AAssetManager* asset_manager = AAssetManager_fromJava(env, asset_manager_obj);
    if (!asset_manager) {
        LOGE("FaceRecognizer: Failed to get AAssetManager");
        return 0;
    }

    std::string thread_affinity;
    if (thread_affinity_str) {
        const char* str = env->GetStringUTFChars(thread_affinity_str, nullptr);
        if (str) {
            thread_affinity = str;
            env->ReleaseStringUTFChars(thread_affinity_str, str);
        }
    }

    try {
        auto* recognizer = new incabin::FaceRecognizer(asset_manager, num_threads, thread_affinity);
        LOGI("FaceRecognizer created at %p (threads=%d, affinity=%s)",
             recognizer, num_threads, thread_affinity.c_str());
        return reinterpret_cast<jlong>(recognizer);
    } catch (const std::exception& e) {
        LOGE("Failed to create FaceRecognizer: %s", e.what());
        return 0;
    }
}

// ---- FaceRecognizer JNI: Compute Embedding ----

JNIEXPORT jfloatArray JNICALL
Java_com_incabin_FaceRecognizerBridge_nativeComputeEmbedding(
    JNIEnv* env,
    jobject /* this */,
    jlong recognizer_ptr,
    jbyteArray bgr_crop_array,
    jint crop_w,
    jint crop_h
) {
    if (recognizer_ptr == 0) {
        LOGE("nativeComputeEmbedding: null recognizer pointer");
        return nullptr;
    }

    auto* recognizer = reinterpret_cast<incabin::FaceRecognizer*>(recognizer_ptr);

    jbyte* bgr_data = env->GetByteArrayElements(bgr_crop_array, nullptr);
    if (!bgr_data) {
        LOGE("nativeComputeEmbedding: failed to get byte array");
        return nullptr;
    }

    incabin::FaceEmbedding embedding;
    bool success = false;

    try {
        success = recognizer->computeEmbedding(
            reinterpret_cast<const uint8_t*>(bgr_data), crop_w, crop_h, embedding);
    } catch (const std::exception& e) {
        LOGE("nativeComputeEmbedding exception: %s", e.what());
    }

    env->ReleaseByteArrayElements(bgr_crop_array, bgr_data, JNI_ABORT);

    if (!success) {
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(incabin::FACE_EMBEDDING_DIM);
    if (!result) {
        LOGE("NewFloatArray returned null (OOM) for embedding");
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, incabin::FACE_EMBEDDING_DIM, embedding.data.data());
    return result;
}

// ---- FaceRecognizer JNI: Destroy ----

JNIEXPORT void JNICALL
Java_com_incabin_FaceRecognizerBridge_nativeDestroyFaceRecognizer(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong recognizer_ptr
) {
    if (recognizer_ptr != 0) {
        auto* recognizer = reinterpret_cast<incabin::FaceRecognizer*>(recognizer_ptr);
        LOGI("Destroying FaceRecognizer at %p", recognizer);
        delete recognizer;
    }
}

} // extern "C"
