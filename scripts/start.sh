#!/bin/bash

# 启动脚本

set -e

echo "启动Dawn AI..."

# 检查.env文件
if [ ! -f .env ]; then
    echo "复制.env.example到.env..."
    cp .env.example .env
    echo "请编辑.env文件配置API密钥等"
    exit 1
fi

# 启动依赖
echo "启动依赖服务..."
docker compose up -d

# 等待服务就绪
echo "等待服务就绪..."
sleep 5

# 启动应用
echo "启动应用..."
uv run dawn-ai
