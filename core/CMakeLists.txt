cmake_minimum_required(VERSION 3.20)
project(classos_core VERSION 0.3.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Threads REQUIRED)

# MinGW's default thread model is not reliable for std::thread support in older
# toolchains. Fail early with a clear message instead of the cryptic
# std::mutex/condition_variable errors that appear during compile.
if(MINGW)
    if(CMAKE_CXX_COMPILER_VERSION VERSION_LESS 8.0)
        message(FATAL_ERROR "ClassOS requires a modern MinGW-w64/GCC 8+ toolchain or a Linux/WSL environment; the current MinGW install does not provide reliable std::thread support.")
    endif()
    add_compile_options(-mthreads)
    add_link_options(-mthreads)
endif()

# Optional: build with ThreadSanitizer to detect data races (Linux/WSL, GCC/Clang).
# Must come before targets are defined, because these options apply to targets created afterwards.
option(CLASSOS_TSAN "Build with ThreadSanitizer" OFF)
if(CLASSOS_TSAN AND NOT MSVC)
    add_compile_options(-fsanitize=thread -g)
    add_link_options(-fsanitize=thread)
endif()

# Pure algorithms: no third-party dependencies.
add_library(classos_core
    src/bankers.cpp
    src/timetable.cpp
    src/scheduler.cpp
)
target_include_directories(classos_core PUBLIC include)
target_link_libraries(classos_core PUBLIC Threads::Threads)

if(MSVC)
    target_compile_options(classos_core PRIVATE /W4)
else()
    target_compile_options(classos_core PRIVATE -Wall -Wextra -Wpedantic)
endif()

enable_testing()
foreach(t bankers timetable scheduler)
    add_executable(test_${t} tests/test_${t}.cpp)
    target_link_libraries(test_${t} PRIVATE classos_core)
    add_test(NAME ${t} COMMAND test_${t})
endforeach()

# Offline benchmarks for the evaluation chapter (no external libraries).
add_executable(classos_bench tools/bench.cpp)
target_link_libraries(classos_bench PRIVATE classos_core)

# HTTP service. Downloads cpp-httplib and nlohmann/json on first configure.
option(CLASSOS_BUILD_SERVER "Build the HTTP solver service" ON)
if(CLASSOS_BUILD_SERVER)
    include(FetchContent)
    set(HTTPLIB_USE_OPENSSL_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
    set(HTTPLIB_USE_ZLIB_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
    set(HTTPLIB_USE_BROTLI_IF_AVAILABLE OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(httplib
        URL https://github.com/yhirose/cpp-httplib/archive/refs/tags/v0.15.3.tar.gz)
    FetchContent_Declare(json
        URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz)
    FetchContent_MakeAvailable(httplib json)

    add_executable(classos_solver_server server/main.cpp server/timetable_json.cpp server/scheduler_json.cpp)
    target_include_directories(classos_solver_server PRIVATE server)
    target_link_libraries(classos_solver_server PRIVATE classos_core httplib::httplib nlohmann_json::nlohmann_json)
endif()
