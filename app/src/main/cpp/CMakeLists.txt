cmake_minimum_required(VERSION 3.22.1)

project(rawlite_writer LANGUAGES CXX)

add_library(rawlite_writer SHARED rawlite_writer.cpp)

target_compile_features(rawlite_writer PRIVATE cxx_std_20)
target_link_options(
    rawlite_writer
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(rawlite_writer PRIVATE log jnigraphics)
