# SPIKE: native USB monitor-mode probe (devourer + libusb, both built from
# source at pinned submodule commits). Throwaway feasibility code — see
# usbmon/UsbMonitorNative.kt.
cmake_minimum_required(VERSION 3.22.1)
project(whyfiusb C CXX)

# --- libusb 1.0.27 (LGPL-2.1), source list from libusb/android/jni/libusb.mk
set(LIBUSB ${CMAKE_SOURCE_DIR}/libusb)
add_library(usb1 STATIC
        ${LIBUSB}/libusb/core.c
        ${LIBUSB}/libusb/descriptor.c
        ${LIBUSB}/libusb/hotplug.c
        ${LIBUSB}/libusb/io.c
        ${LIBUSB}/libusb/sync.c
        ${LIBUSB}/libusb/strerror.c
        ${LIBUSB}/libusb/os/linux_usbfs.c
        ${LIBUSB}/libusb/os/events_posix.c
        ${LIBUSB}/libusb/os/threads_posix.c
        ${LIBUSB}/libusb/os/linux_netlink.c)
target_include_directories(usb1 PRIVATE ${LIBUSB}/android ${LIBUSB}/libusb/os)
target_include_directories(usb1 PUBLIC ${LIBUSB}/libusb)
target_compile_options(usb1 PRIVATE -pthread)
target_link_libraries(usb1 PUBLIC log)

# --- devourer (GPL-2.0) at bb03774, the commit PixelPilot ships on Android
# (newer master needs std::jthread, experimental in NDK 27 libc++).
# Generation-agnostic core + Jaguar1 only
# (RTL8812AU / 8811AU / 8821AU). Lists mirror devourer/CMakeLists.txt's
# add_library(devourer ...) core and if(DEVOURER_JAGUAR1) groups; its own
# CMakeLists can't be used directly because it resolves libusb via
# pkg-config, which the NDK build doesn't have.
set(DVR ${CMAKE_SOURCE_DIR}/devourer)

# whyfi: 0bda:0811 belongs in is_rtl8821a_pid(). Linux rtw88 (rtw8821au.c)
# maps it to rtw8821a_hw_spec, and on a real 0bda:0811 stick the 8812
# power-on flow left the chip stuck in CARDEMU (REG_SYS_CLKR=0x30,
# REG_CR=0xEA). Compiled from a patched copy so the submodule stays clean
# and a fresh checkout gets the fix without a manual step.
set(DVR_EEPROM ${DVR}/src/jaguar1/EepromManager.cpp)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${DVR_EEPROM})
file(READ ${DVR_EEPROM} DVR_EEPROM_SRC)
string(FIND "${DVR_EEPROM_SRC}" "  case 0x0BDA0820:\n" DVR_EEPROM_ANCHOR)
if(DVR_EEPROM_ANCHOR EQUAL -1)
    message(FATAL_ERROR "devourer EepromManager.cpp changed: 0bda:0811 anchor not found")
endif()
string(REPLACE "  case 0x0BDA0820:\n" "  case 0x0BDA0811:\n  case 0x0BDA0820:\n"
        DVR_EEPROM_SRC "${DVR_EEPROM_SRC}")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/EepromManager.cpp "${DVR_EEPROM_SRC}")
add_library(devourer STATIC
        ${DVR}/src/HalmacCfgParam.cpp
        ${DVR}/src/ParsedRadioPacket.cpp
        ${DVR}/src/Radiotap.c
        ${DVR}/src/RadiotapBuilder.cpp
        ${DVR}/src/RtlAdapter.cpp
        ${DVR}/src/UsbTransport.cpp
        ${DVR}/src/UsbDeviceLock.cpp
        ${DVR}/src/UsbOpen.cpp
        ${DVR}/src/TxMode.cpp
        ${DVR}/src/TxPower.cpp
        ${DVR}/src/LinkHealth.cpp
        ${DVR}/src/chanmig/ChannelScore.cpp
        ${DVR}/src/chanmig/MigGate.cpp
        ${DVR}/src/SignalStop.cpp
        ${DVR}/src/WiFiDriver.cpp
        ${DVR}/src/LaCapture.cpp
        ${DVR}/src/PhyTableLoader.cpp
        ${DVR}/hal/hal8812a_fw.c
        ${DVR}/hal/hal8821a_fw.c
        ${DVR}/hal/Hal8812PwrSeq.c
        ${DVR}/hal/Hal8821APwrSeq.c
        ${DVR}/hal/Hal8812a_TxPwrTrack.cpp
        ${DVR}/src/jaguar1/RtlJaguarDevice.cpp
        ${DVR}/src/jaguar1/HalModule.cpp
        ${DVR}/src/jaguar1/RadioManagementModule.cpp
        ${CMAKE_CURRENT_BINARY_DIR}/EepromManager.cpp
        ${DVR}/src/jaguar1/FirmwareManager.cpp
        ${DVR}/src/jaguar1/Iqk8812a.cpp
        ${DVR}/src/jaguar1/PhydmWatchdog.cpp
        ${DVR}/src/jaguar1/PowerTracking8812a.cpp
        ${DVR}/src/jaguar1/FrameParser.cpp
        ${DVR}/src/jaguar1/BbDbgportReader.cpp)
target_compile_features(devourer PUBLIC cxx_std_20)
target_compile_definitions(devourer PUBLIC DEVOURER_HAVE_JAGUAR1=1)
target_include_directories(devourer PUBLIC ${DVR}/hal ${DVR}/src)
# The patched EepromManager.cpp copy resolves its quoted includes from here.
target_include_directories(devourer PRIVATE ${DVR}/src/jaguar1)
target_link_libraries(devourer PUBLIC usb1 log)

# --- the JNI bridge
add_library(whyfiusb SHARED whyfiusb.cpp)
target_compile_features(whyfiusb PRIVATE cxx_std_20)
target_compile_options(whyfiusb PRIVATE -fno-omit-frame-pointer)
target_link_libraries(whyfiusb devourer usb1 log)
