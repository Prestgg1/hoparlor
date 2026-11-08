# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(hoparlor CXX)

add_library(hoparlor SHARED engine.cpp jni.cpp)
target_compile_options(hoparlor PRIVATE -O2 -Wall -Wextra -ffast-math)
# Reproducible builds for F-Droid: no build IDs or absolute paths baked into the .so.
target_link_options(hoparlor PRIVATE -Wl,--build-id=none)
target_compile_options(hoparlor PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
target_link_libraries(hoparlor aaudio log)
