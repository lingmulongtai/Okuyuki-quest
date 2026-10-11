cmake_minimum_required(VERSION 3.22.1)
project(okuyuki_stereo LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(de265_source
    URL https://codeload.github.com/strukturag/libde265/tar.gz/refs/tags/v1.1.3
    URL_HASH SHA256=189baa08fd6d2dd34db099de411bd6b2e6bd5eb88e81236a3d0fa3826b9715c4)
FetchContent_MakeAvailable(de265_source)
# Satisfy libheif's explicit find_package contract with the in-tree decoder target.
set(LIBDE265_INCLUDE_DIR "${de265_source_SOURCE_DIR}" CACHE PATH "" FORCE)
set(LIBDE265_LIBRARY de265 CACHE STRING "" FORCE)
target_include_directories(de265 PUBLIC
    "$<BUILD_INTERFACE:${de265_source_SOURCE_DIR}>"
    "$<BUILD_INTERFACE:${de265_source_BINARY_DIR}>")

set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
foreach(option WITH_X265 WITH_X264 WITH_OpenH264_DECODER WITH_AOM_DECODER WITH_AOM_ENCODER
    WITH_DAV1D WITH_RAV1E WITH_SvtEnc WITH_VVDEC WITH_VVENC WITH_FFMPEG_DECODER
    WITH_JPEG_DECODER WITH_JPEG_ENCODER WITH_OpenJPEG_DECODER WITH_OpenJPEG_ENCODER
    WITH_LIBSHARPYUV WITH_EXAMPLES WITH_GDK_PIXBUF BUILD_TESTING BUILD_DOCUMENTATION)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(heif_source
    URL https://codeload.github.com/strukturag/libheif/tar.gz/refs/tags/v1.23.6
    URL_HASH SHA256=c9a00b0452f5c16d72ef71e5e40c0e90fbe2ad37148d27a1b2f9bb21546f5f03)
FetchContent_MakeAvailable(heif_source)
set_target_properties(heif de265 PROPERTIES VERSION "" SOVERSION "")
add_library(okuyuki_stereo SHARED stereo_jni.cpp)
target_include_directories(okuyuki_stereo PRIVATE "${heif_source_SOURCE_DIR}/libheif/api" "${heif_source_BINARY_DIR}")
target_link_libraries(okuyuki_stereo PRIVATE heif jnigraphics)
