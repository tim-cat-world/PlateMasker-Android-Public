cmake_minimum_required(VERSION 3.10)

# プロジェクト名を汎用的なものに変更
project(utils_core)

set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../native/jni)
find_package(OpenCV REQUIRED PATHS ${OPENCV_DIR} NO_DEFAULT_PATH)

include_directories(${OpenCV_INCLUDE_DIRS})

# 生成されるライブラリ名を utils_core に変更
add_library(utils_core SHARED
            mask_processor.cpp)

target_link_libraries(utils_core
                      ${OpenCV_LIBS}
                      jnigraphics
                      log)
