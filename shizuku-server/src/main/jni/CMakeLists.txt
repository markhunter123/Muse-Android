cmake_minimum_required(VERSION 3.22.1)

# MUSE: 配对 JNI（adb_pairing.cpp）——上游 manager 的 adb 目标等价物。
# SPAKE2/HKDF 走 boringssl prefab（io.github.vvb2060.ndk:boringssl），静态链入。
project("muse-adb")

set(CMAKE_CXX_STANDARD 17)

find_package(boringssl REQUIRED CONFIG)

add_library(adb SHARED adb_pairing.cpp)

target_link_libraries(adb log boringssl::crypto_static)
