# Start with Ubuntu 24.04 as requested
FROM ubuntu:24.04

# Prevent interactive prompts during apt installations
ENV DEBIAN_FRONTEND=noninteractive

# Install prerequisites for the dbgsym keyring and repository management
RUN apt-get update && apt-get install -y \
    ubuntu-dbgsym-keyring \
    gnupg \
    wget \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Add the ddebs repository for Ubuntu 24.04 (Noble Numbat)
RUN echo "deb http://ddebs.ubuntu.com noble main restricted universe multiverse" > /etc/apt/sources.list.d/ddebs.list && \
    echo "deb http://ddebs.ubuntu.com noble-updates main restricted universe multiverse" >> /etc/apt/sources.list.d/ddebs.list

# Update package lists to include the ddebs repository, and install libhyperscan5 along with its debug symbols
RUN apt-get update && apt-get install -y \
    libhyperscan5 \
    libhyperscan5-dbgsym \
    && rm -rf /var/lib/apt/lists/*