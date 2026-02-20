cmake_minimum_required(VERSION 3.22.1)
project("incabin" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- ONNX Runtime (extracted from AAR) ----
set(ONNXRUNTIME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so
    INTERFACE_INCLUDE_DIRECTORIES ${ONNXRUNTIME_DIR}/include
)

# ---- Source files ----

add_library(incabin SHARED
    image_utils.cpp
    yolo_utils.cpp
    pose_analyzer.cpp
    face_recognizer.cpp
    v4l2_camera.cpp
    jni_bridge.cpp
)

# ---- Include directories ----

target_include_directories(incabin PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ---- Find system libraries ----

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

# ---- Link libraries ----

target_link_libraries(incabin
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
    onnxruntime
)
