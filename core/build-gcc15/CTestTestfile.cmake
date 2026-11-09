# CMake generated Testfile for 
# Source directory: C:/Codes/ClassOS/core
# Build directory: C:/Codes/ClassOS/core/build-gcc15
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
add_test("bankers" "C:/Codes/ClassOS/core/build-gcc15/test_bankers.exe")
set_tests_properties("bankers" PROPERTIES  _CMAKE_TEST_BUILD_DEPENDS "C:/Codes/ClassOS/core/build-gcc15/test_bankers.exe" _BACKTRACE_TRIPLES "C:/Codes/ClassOS/core/CMakeLists.txt;48;add_test;C:/Codes/ClassOS/core/CMakeLists.txt;0;")
add_test("timetable" "C:/Codes/ClassOS/core/build-gcc15/test_timetable.exe")
set_tests_properties("timetable" PROPERTIES  _CMAKE_TEST_BUILD_DEPENDS "C:/Codes/ClassOS/core/build-gcc15/test_timetable.exe" _BACKTRACE_TRIPLES "C:/Codes/ClassOS/core/CMakeLists.txt;48;add_test;C:/Codes/ClassOS/core/CMakeLists.txt;0;")
add_test("scheduler" "C:/Codes/ClassOS/core/build-gcc15/test_scheduler.exe")
set_tests_properties("scheduler" PROPERTIES  _CMAKE_TEST_BUILD_DEPENDS "C:/Codes/ClassOS/core/build-gcc15/test_scheduler.exe" _BACKTRACE_TRIPLES "C:/Codes/ClassOS/core/CMakeLists.txt;48;add_test;C:/Codes/ClassOS/core/CMakeLists.txt;0;")
subdirs("_deps/httplib-build")
subdirs("_deps/json-build")
