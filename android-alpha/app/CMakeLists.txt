cmake_minimum_required(VERSION 3.22.1)
project(alpha_native)

add_library(
    alpha_watchdog
    SHARED
    src/main/cpp/watchdog.c
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    alpha_watchdog
    ${log-lib}
    ${android-lib}
)

target_compile_options(alpha_watchdog PRIVATE
    -O2
    -fvisibility=hidden
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
)
