#!/data/data/com.termux/files/usr/bin/bash

# Update and install OpenJDK (Gradle requires Java)
echo "Installing OpenJDK 17..."
pkg update && pkg upgrade -y
pkg install openjdk-17 wget unzip -y

# Set Gradle Version
GRADLE_VERSION="8.5"
INSTALL_DIR="$HOME/gradle"

# Create installation directory
mkdir -p $INSTALL_DIR

# Download Gradle
echo "Downloading Gradle $GRADLE_VERSION..."
wget -q https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip -P /tmp

# Unzip to installation directory
echo "Extracting Gradle..."
unzip -q /tmp/gradle-$GRADLE_VERSION-bin.zip -d $INSTALL_DIR

# Add Gradle to PATH
echo "Configuring PATH..."
if ! grep -q "gradle" ~/.bashrc; then
    echo "export PATH=\$PATH:$INSTALL_DIR/gradle-$GRADLE_VERSION/bin" >> ~/.bashrc
    source ~/.bashrc
fi

# Verify Installation
echo "Verifying installation..."
gradle -v
