#!/bin/sh
cd "$(dirname "$0")"
exec java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
