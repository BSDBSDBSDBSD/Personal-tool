cmake_minimum_required(VERSION 3.22)
project(ozer_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# whisper.cpp (speech recognition, runs fully on the phone). Pinned version.
include(FetchContent)
set(WHISPER_CPP_SOURCE "" CACHE PATH "Local whisper.cpp checkout (optional; downloaded otherwise)")
if (WHISPER_CPP_SOURCE)
    FetchContent_Declare(whisper SOURCE_DIR ${WHISPER_CPP_SOURCE})
else()
    FetchContent_Declare(whisper
        URL https://github.com/ggerganov/whisper.cpp/archive/refs/tags/v1.7.4.tar.gz)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whisper)

add_library(ozer_whisper SHARED whisper_jni.cpp)
target_link_libraries(ozer_whisper PRIVATE whisper)
if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(ozer_whisper PRIVATE ${LOG_LIB})
    target_link_options(ozer_whisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
