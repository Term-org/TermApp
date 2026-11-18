cmake_minimum_required(VERSION 3.22.1)
project(termone_pty C)

add_library(termone_pty SHARED term_pty.c)

target_compile_options(termone_pty PRIVATE -Wall -O2)
target_link_options(termone_pty PRIVATE -Wl,-z,max-page-size=16384)