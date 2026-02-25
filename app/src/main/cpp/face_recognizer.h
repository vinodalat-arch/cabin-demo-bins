#pragma once

#include <array>
#include <memory>
#include <vector>
#include <android/asset_manager.h>
#include <onnxruntime_cxx_api.h>

namespace incabin {

static constexpr int FACE_EMBEDDING_DIM = 512;
static constexpr int FACE_INPUT_SIZE = 112;

struct FaceEmbedding {
    std::array<float, FACE_EMBEDDING_DIM> data;

    /** L2-normalize in place. */
    void normalize();

    /** Cosine similarity with another embedding. */
    float cosineSimilarity(const FaceEmbedding& other) const;
};

class FaceRecognizer {
public:
    /**
     * @param asset_manager    Android AAssetManager for reading model files
     * @param num_threads      Number of ONNX Runtime intra-op threads
     * @param thread_affinity  Thread affinity string (e.g. "5"), empty for no pinning
     */
    FaceRecognizer(AAssetManager* asset_manager, int num_threads, const std::string& thread_affinity);
    ~FaceRecognizer() = default;

    FaceRecognizer(const FaceRecognizer&) = delete;
    FaceRecognizer& operator=(const FaceRecognizer&) = delete;

    /**
     * Compute a 128-dim embedding from a BGR face crop.
     * @param bgr_crop  BGR pixel data
     * @param crop_w    Crop width
     * @param crop_h    Crop height
     * @param out       Output embedding (L2-normalized)
     * @return true on success
     */
    bool computeEmbedding(const uint8_t* bgr_crop, int crop_w, int crop_h,
                          FaceEmbedding& out);

private:
    Ort::Env env_;
    Ort::SessionOptions session_options_;
    std::unique_ptr<Ort::Session> session_;

    // Pre-allocated buffers
    std::vector<uint8_t> resize_buf_;       // 112*112*3
    std::vector<float> input_tensor_buf_;   // 3*112*112 (FP32 preprocessing)
    std::vector<Ort::Float16_t> input_tensor_fp16_;  // 3*112*112 (FP16 for model input)

    std::vector<uint8_t> loadModelFromAssets(AAssetManager* mgr, const char* filename);
    void resizeBilinear(const uint8_t* src, int src_w, int src_h,
                        uint8_t* dst, int dst_w, int dst_h);
    void preprocess(const uint8_t* bgr_112, float* output_chw);
};

} // namespace incabin
