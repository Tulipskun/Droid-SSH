cmake_minimum_required(VERSION 3.22.1)
project(droidssh_pty C)

add_library(droidssh_pty SHARED pty.c)

# 16 KB page-size compatible (Android 15+ requirement, มาตรฐานเดียวกับ Droid-MCP)
target_link_options(droidssh_pty PRIVATE "LINKER:-z,max-page-size=16384")
