cmake_minimum_required(VERSION 3.22.1)
project("abyssnative")

add_library(
        abyssnative
        SHARED
        native-lib.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        abyssnative
        ${log-lib})
