# llama.cpp-b2710/examples/llama.android/app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# プロジェクトのルートディレクトリを設定
# CMakeLists.txtからPROJECT_ROOTまでの相対パス
set(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../")

# FetchContentモジュールのインクルード
include(FetchContent)

# llamaのFetchContent宣言
FetchContent_Declare(
        llama
        SOURCE_DIR "${PROJECT_ROOT}"
)

# FetchContentを利用可能にする
FetchContent_MakeAvailable(llama)

# ライブラリの作成 (相対パス)
add_library(${CMAKE_PROJECT_NAME} SHARED
    llama-android.cpp
)

# リンクするライブラリの指定
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log
)
