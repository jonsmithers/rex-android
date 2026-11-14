cmake_minimum_required(VERSION 3.22)
project(rexvt C)

# Prebuilt by scripts/build-libghostty-vt.sh.
set(GHOSTTY_VT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libghostty-vt)
if(NOT EXISTS ${GHOSTTY_VT_DIR}/${ANDROID_ABI}/libghostty-vt.a)
    message(FATAL_ERROR "libghostty-vt for ${ANDROID_ABI} is missing. Run scripts/build-libghostty-vt.sh "
                        "(set ABIS=\"arm64-v8a x86_64\" for an x86_64 emulator).")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/${ANDROID_ABI}/libghostty-vt.a
    INTERFACE_INCLUDE_DIRECTORIES ${GHOSTTY_VT_DIR}/include
    INTERFACE_COMPILE_DEFINITIONS GHOSTTY_STATIC)

add_library(rexvt SHARED rexvt.c)
file(READ ${GHOSTTY_VT_DIR}/VERSION GHOSTTY_COMMIT)
string(STRIP "${GHOSTTY_COMMIT}" GHOSTTY_COMMIT)
target_compile_definitions(rexvt PRIVATE GHOSTTY_COMMIT="${GHOSTTY_COMMIT}")
target_compile_options(rexvt PRIVATE -Wall -Wno-unused-parameter)
target_link_libraries(rexvt PRIVATE ghostty-vt log)
# Android 15+ devices can use 16 KB pages.
target_link_options(rexvt PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
