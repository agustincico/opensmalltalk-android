cmake_minimum_required(VERSION 3.22.1)

# Declaramos que este proyecto usa código C, lo cual ayuda a CMake.
project(squeak_jni C) 

add_library(squeak_jni SHARED squeak_jni.c)

# Configuramos explícitamente el estándar C11 SÓLO para este target.
set_target_properties(squeak_jni PROPERTIES 
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

target_link_libraries(squeak_jni android log dl)

# 16 KB page size support (required by Google Play for targetSdk 35+).
# NDK r27+ does this by default; we build with NDK 26, so ask the linker
# explicitly for 16 KB-aligned LOAD segments. Every prebuilt .so we ship is
# already 16 KB aligned — this keeps the one library WE compile in line.
target_link_options(squeak_jni PRIVATE "-Wl,-z,max-page-size=16384")