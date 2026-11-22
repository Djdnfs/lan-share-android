FROM mcr.microsoft.com/devcontainers/base:ubuntu

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt update && apt install -y wget unzip openjdk-17-jdk

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
&& cd $ANDROID_HOME/cmdline-tools \
&& wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
&& unzip commandlinetools-linux-11076708_latest.zip \
&& mv cmdline-tools latest \
&& rm commandlinetools-linux-11076708_latest.zip

RUN sdkmanager --licenses <<EOF
y
y
y
y
y
y
y
y
y
EOF
RUN sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
