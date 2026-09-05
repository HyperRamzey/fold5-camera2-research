#!/usr/bin/env pwsh
# AGC patch toolchain environment
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:APKTOOL = "G:\projects\burn in\tools\apktool_3.0.3.jar"
$env:BT37 = "C:\Users\admin\AppData\Local\Android\Sdk\build-tools\37.0.0"
java -version 2>&1 | Select-Object -First 1