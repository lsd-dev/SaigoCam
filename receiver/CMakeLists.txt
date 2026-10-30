# SaigoCam low-latency receiver (prototype).
# Part of SaigoCam, licensed under the GNU General Public License v3; see ../LICENSE.
cmake_minimum_required(VERSION 3.18)
project(saigocam_receiver C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED OFF)

set(FFMPEG_ROOT "" CACHE PATH
    "Folder of an FFmpeg 'shared' dev package (include/, lib/, bin/). Leave empty to use pkg-config.")
option(SAIGOCAM_RX_TESTS "Build the unit tests" ON)

if(MSVC)
  add_compile_options(/W4 /D_CRT_SECURE_NO_WARNINGS)
else()
  add_compile_options(-Wall -Wextra)
endif()

# --- Unit tests (no FFmpeg or SDL needed) ----------------------------------
if(SAIGOCAM_RX_TESTS)
  enable_testing()
  add_executable(test_early_nal tests/test_early_nal.c src/early_nal.c)
  add_test(NAME early_nal COMMAND test_early_nal)
endif()

# --- SDL2 -------------------------------------------------------------------
find_package(SDL2 2.0.16 REQUIRED CONFIG)

# --- FFmpeg (libavformat, libavcodec, libavutil) ----------------------------
if(FFMPEG_ROOT)
  find_path(FFMPEG_INCLUDE_DIR libavformat/avformat.h
            PATHS "${FFMPEG_ROOT}/include" NO_DEFAULT_PATH REQUIRED)
  add_library(ffmpeg INTERFACE)
  target_include_directories(ffmpeg INTERFACE "${FFMPEG_INCLUDE_DIR}")
  foreach(lib avformat avcodec avutil)
    find_library(FFMPEG_${lib}_LIBRARY NAMES ${lib}
                 PATHS "${FFMPEG_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)
    target_link_libraries(ffmpeg INTERFACE "${FFMPEG_${lib}_LIBRARY}")
  endforeach()
  set(FFMPEG_TARGET ffmpeg)
else()
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET
                    libavformat>=59 libavcodec>=59 libavutil>=57)
  set(FFMPEG_TARGET PkgConfig::FFMPEG)
endif()

# --- Receiver ---------------------------------------------------------------
add_executable(saigocam-receiver src/main.c src/early_nal.c src/overlay.c)
target_link_libraries(saigocam-receiver PRIVATE SDL2::SDL2 ${FFMPEG_TARGET})

# With a prebuilt FFmpeg package, copy the DLLs next to the .exe so it runs
# from Explorer or any shell.
if(WIN32 AND FFMPEG_ROOT)
  file(GLOB FFMPEG_DLLS "${FFMPEG_ROOT}/bin/*.dll")
  add_custom_command(TARGET saigocam-receiver POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${FFMPEG_DLLS} $<TARGET_FILE:SDL2::SDL2> $<TARGET_FILE_DIR:saigocam-receiver>
    COMMAND_EXPAND_LISTS)
endif()
