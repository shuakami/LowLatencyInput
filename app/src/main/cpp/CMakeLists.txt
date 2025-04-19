# -*- cmake -*-
# 设置运行此项目所需的最低 CMake 版本。
# 这确保了构建脚本中使用的命令和语法是兼容的。
cmake_minimum_required(VERSION 3.22.1)

# 声明项目名称。
# 项目名称将在后续命令中通过 ${PROJECT_NAME} 或 ${CMAKE_PROJECT_NAME} 变量引用。
# 对于顶层 CMakeLists.txt，这两个变量通常是相同的。
project("lowlatencyinput" LANGUAGES CXX)

include(FetchContent)
FetchContent_Declare(
    nlohmann_json
    GIT_REPOSITORY https://github.com/nlohmann/json.git
    GIT_TAG v3.11.3
)
FetchContent_MakeAvailable(nlohmann_json)

# 创建并命名一个库目标。
# ${CMAKE_PROJECT_NAME}: 库的名称，这里设置为项目名称 "lowlatencyinput"。
# SHARED: 指定构建类型为共享库 (.so 文件 on Android/Linux)。
#         也可以是 STATIC (静态库 .a 文件)。
# 随后的参数是构成该库的源文件列表。
add_library(${CMAKE_PROJECT_NAME} SHARED
        # 提供源文件的相对路径。
        native-lib.cpp
        bridge/jni_bridge.cpp
        input/input_reader.cpp
        input/input_reader_loop.cpp
        input/input_reader_permissions.cpp
        input/input_reader_jni_utils.cpp
        )

# 查找 NDK (Native Development Kit) 提供的日志库 (liblog.so)。
# find_library 会搜索指定的库，并将其完整路径存储在第一个参数指定的变量 (log-lib) 中。
# 'log' 是 NDK 中日志库的标准名称。
find_library(log-lib log)

# 指定目标库需要链接的其他系统库或第三方库。
# ${CMAKE_PROJECT_NAME}: 我们要链接库的目标，即上面 add_library 定义的库。
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        # android: 链接 Android NDK 提供的核心 API 库。
        android
        # ${log-lib}: 链接上面通过 find_library 找到的日志库变量。
        ${log-lib}
        # atomic: 提供 C++11 原子操作所需的底层支持库。
        atomic
        # m: 链接标准数学库 (libm)。
        m
        )

# --- 新增：链接 nlohmann_json::nlohmann_json --- 
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE nlohmann_json::nlohmann_json)
# --- 新增结束 ---