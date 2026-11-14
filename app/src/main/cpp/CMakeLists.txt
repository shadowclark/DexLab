cmake_minimum_required(VERSION 3.22)
project(dexlab_capstone C)

set(CMAKE_C_STANDARD 11)

# Build only the architectures we need. Capstone 5 uses CAPSTONE_<ARCH>_SUPPORT.
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/capstone)

add_library(dexlab_capstone SHARED capstone_jni.c)
target_include_directories(dexlab_capstone PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/capstone/include)
target_link_libraries(dexlab_capstone capstone_static log)
target_link_options(dexlab_capstone PRIVATE "-Wl,-z,max-page-size=16384")

# A tiny legal practice target that ships in the APK.
add_library(sample SHARED sample.c)
target_link_options(sample PRIVATE "-Wl,-z,max-page-size=16384")
