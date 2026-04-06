cmake_minimum_required(VERSION 3.4.1)

# Declaramos que este proyecto usa código C, lo cual ayuda a CMake.
project(squeak_jni C) 

add_library(squeak_jni SHARED squeak_jni.c)

# Configuramos explícitamente el estándar C11 SÓLO para este target.
set_target_properties(squeak_jni PROPERTIES 
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

target_link_libraries(squeak_jni android log dl)