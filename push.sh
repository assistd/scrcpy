#!/bin/bash

adb push scrcpy-server-v1.24 /data/local/tmp/scrcpy-server.jar
adb forward tcp:21344 localabstract:scrcpy
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 1.24 log_level=info bit_rate=8000000 tunnel_forward=true control=false
