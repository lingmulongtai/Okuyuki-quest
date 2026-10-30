cmake_minimum_required(VERSION 3.22.1)
project(okuyuki_projection LANGUAGES CXX)
add_library(okuyuki_projection SHARED projection_jni.cpp)
target_link_libraries(okuyuki_projection PRIVATE android)
