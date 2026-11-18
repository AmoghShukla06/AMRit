# source this: sets JAVA_HOME / ANDROID_HOME for command-line builds on this machine
export JAVA_HOME="$(ls -d "/c/Program Files/Eclipse Adoptium/"jdk-17* | head -1)"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
