FROM python:3.12-slim

WORKDIR /app

# 安装uv
RUN pip install uv

# 复制依赖文件
COPY pyproject.toml uv.lock ./

# 安装依赖
RUN uv sync --frozen

# 复制源码
COPY src ./src

# 暴露端口
EXPOSE 8000

# 启动应用
CMD ["uv", "run", "dawn-ai"]
