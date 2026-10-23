cmake_minimum_required(VERSION 3.22.1)
project(vcam_ndk LANGUAGES C)

add_library(vcam_ndk SHARED vcam_ndk.c)

target_compile_options(vcam_ndk PRIVATE
    -O2
    -Wall
    -Wextra
    -fPIC
    -fno-stack-protector
    -fvisibility=hidden
)

target_link_libraries(vcam_ndk
    android
    log
    dl
    c
)
