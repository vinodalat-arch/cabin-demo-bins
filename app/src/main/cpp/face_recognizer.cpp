#include "face_recognizer.h"

#include <android/log.h>
#include <cmath>
#include <algorithm>

#define TAG "InCabin-FaceRec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace incabin {

// ---- FaceEmbedding ----

void FaceEmbedding::normalize() {
    float norm = 0.0f;
    for (float v : data) norm += v * v;
    norm = std::sqrt(norm);
    if (norm > 1e-8f) {
        for (float& v : data) v /= norm;
    }
}

float FaceEmbedding::cosineSimilarity(const FaceEmbedding& other) const {
    float dot = 0.0f;
    for (int i = 0; i < FACE_EMBEDDING_DIM; i++) {
        dot += data[i] * other.data[i];
    }
    return dot; // Both are L2-normalized, so dot == cosine similarity
}

// ---- Model Loading ----

std::vector<uint8_t> FaceRecognizer::loadModelFromAssets(AAssetManager* mgr, const char* filename) {
    AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset: %s", filename);
        return {};
    }

    off_t size = AAsset_getLength(asset);
    std::vector<uint8_t> buffer(size);
    AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);

    LOGI("Loaded model %s: %ld bytes", filename, static_cast<long>(size));
    return buffer;
}

// ---- Constructor ----

FaceRecognizer::FaceRecognizer(AAssetManager* asset_manager, int num_threads,
                               const std::string& thread_affinity)
    : env_(ORT_LOGGING_LEVEL_WARNING, "InCabinFaceRec"),
      resize_buf_(FACE_INPUT_SIZE * FACE_INPUT_SIZE * 3),
      input_tensor_buf_(3 * FACE_INPUT_SIZE * FACE_INPUT_SIZE),
      input_tensor_fp16_(3 * FACE_INPUT_SIZE * FACE_INPUT_SIZE) {

    session_options_.SetIntraOpNumThreads(num_threads);
    session_options_.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    // Pin threads to specific cores if affinity is provided (empty = let OS schedule)
    if (!thread_affinity.empty()) {
        session_options_.AddConfigEntry("session.intra_op_thread_affinities", thread_affinity.c_str());
        LOGI("Thread affinity set: threads=%d, affinity=%s", num_threads, thread_affinity.c_str());
    } else {
        LOGI("Thread count set: %d (no core pinning)", num_threads);
    }

    auto model_data = loadModelFromAssets(asset_manager, "mobilefacenet-fp16.onnx");
    if (!model_data.empty()) {
        session_ = std::make_unique<Ort::Session>(
            env_, model_data.data(), model_data.size(), session_options_);
        LOGI("MobileFaceNet FP16 session created");
    } else {
        LOGE("Failed to load mobilefacenet-fp16.onnx");
    }
}

// ---- Bilinear Resize ----

void FaceRecognizer::resizeBilinear(const uint8_t* src, int src_w, int src_h,
                                     uint8_t* dst, int dst_w, int dst_h) {
    const float x_ratio = static_cast<float>(src_w) / dst_w;
    const float y_ratio = static_cast<float>(src_h) / dst_h;

    for (int y = 0; y < dst_h; y++) {
        const float src_y = y * y_ratio;
        const int y0 = static_cast<int>(src_y);
        const int y1 = std::min(y0 + 1, src_h - 1);
        const float fy = src_y - y0;
        const float fy1 = 1.0f - fy;

        for (int x = 0; x < dst_w; x++) {
            const float src_x = x * x_ratio;
            const int x0 = static_cast<int>(src_x);
            const int x1 = std::min(x0 + 1, src_w - 1);
            const float fx = src_x - x0;
            const float fx1 = 1.0f - fx;

            const int src_idx00 = (y0 * src_w + x0) * 3;
            const int src_idx01 = (y0 * src_w + x1) * 3;
            const int src_idx10 = (y1 * src_w + x0) * 3;
            const int src_idx11 = (y1 * src_w + x1) * 3;
            const int dst_idx = (y * dst_w + x) * 3;

            for (int c = 0; c < 3; c++) {
                float val = fy1 * (fx1 * src[src_idx00 + c] + fx * src[src_idx01 + c]) +
                            fy  * (fx1 * src[src_idx10 + c] + fx * src[src_idx11 + c]);
                dst[dst_idx + c] = static_cast<uint8_t>(std::clamp(val, 0.0f, 255.0f));
            }
        }
    }
}

// ---- Preprocess: BGR -> RGB, HWC -> CHW, normalize to [-1, 1] ----

void FaceRecognizer::preprocess(const uint8_t* bgr_112, float* output_chw) {
    const int hw = FACE_INPUT_SIZE * FACE_INPUT_SIZE;
    for (int i = 0; i < hw; i++) {
        // BGR -> RGB and normalize to [-1, 1]
        output_chw[0 * hw + i] = (bgr_112[i * 3 + 2] / 127.5f) - 1.0f; // R
        output_chw[1 * hw + i] = (bgr_112[i * 3 + 1] / 127.5f) - 1.0f; // G
        output_chw[2 * hw + i] = (bgr_112[i * 3 + 0] / 127.5f) - 1.0f; // B
    }
}

// ---- Compute Embedding ----

bool FaceRecognizer::computeEmbedding(const uint8_t* bgr_crop, int crop_w, int crop_h,
                                       FaceEmbedding& out) {
    if (!session_) {
        LOGE("computeEmbedding: session not initialized");
        return false;
    }

    // Resize to 112x112
    resizeBilinear(bgr_crop, crop_w, crop_h,
                   resize_buf_.data(), FACE_INPUT_SIZE, FACE_INPUT_SIZE);

    // Preprocess
    preprocess(resize_buf_.data(), input_tensor_buf_.data());

    // Convert FP32 preprocessed data to FP16 for model input
    const size_t input_size = 3 * FACE_INPUT_SIZE * FACE_INPUT_SIZE;
    for (size_t i = 0; i < input_size; i++) {
        input_tensor_fp16_[i] = Ort::Float16_t(input_tensor_buf_[i]);
    }

    // Run inference with FP16 I/O
    try {
        Ort::AllocatorWithDefaultOptions allocator;
        auto input_name_ptr = session_->GetInputNameAllocated(0, allocator);
        auto output_name_ptr = session_->GetOutputNameAllocated(0, allocator);
        const char* input_name = input_name_ptr.get();
        const char* output_name = output_name_ptr.get();

        auto memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        std::vector<int64_t> input_shape = {1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE};

        Ort::Value input_tensor = Ort::Value::CreateTensor<Ort::Float16_t>(
            memory_info, input_tensor_fp16_.data(), input_size,
            input_shape.data(), input_shape.size());

        auto output_tensors = session_->Run(
            Ort::RunOptions{nullptr},
            &input_name, &input_tensor, 1,
            &output_name, 1);

        // Validate output
        auto& output_tensor = output_tensors[0];
        auto type_info = output_tensor.GetTensorTypeAndShapeInfo();
        size_t output_size = 1;
        for (auto dim : type_info.GetShape()) output_size *= dim;

        if (output_size != FACE_EMBEDDING_DIM) {
            LOGE("Output size mismatch: expected %d, got %zu", FACE_EMBEDDING_DIM, output_size);
            return false;
        }

        // Read FP16 output and convert back to FP32
        const Ort::Float16_t* output_fp16 = output_tensor.GetTensorData<Ort::Float16_t>();
        for (int i = 0; i < FACE_EMBEDDING_DIM; i++) {
            out.data[i] = output_fp16[i].ToFloat();
        }
        out.normalize();

        return true;
    } catch (const Ort::Exception& e) {
        LOGE("ONNX Runtime error: %s", e.what());
        return false;
    } catch (const std::exception& e) {
        LOGE("computeEmbedding error: %s", e.what());
        return false;
    }
}

} // namespace incabin
