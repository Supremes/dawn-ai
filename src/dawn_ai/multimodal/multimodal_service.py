"""多模态服务"""

from typing import List, Dict, Optional
import base64
from pathlib import Path
from ..config import get_settings


class MultimodalService:
    """多模态服务"""

    def __init__(self):
        settings = get_settings()
        self.supported_image_types = [".jpg", ".jpeg", ".png", ".gif", ".webp"]
        self.supported_audio_types = [".mp3", ".wav", ".m4a"]

    async def process_image(self, image_path: str) -> Dict:
        """处理图像"""
        path = Path(image_path)
        if not path.exists():
            raise FileNotFoundError(f"图像文件不存在: {image_path}")

        if path.suffix.lower() not in self.supported_image_types:
            raise ValueError(f"不支持的图像格式: {path.suffix}")

        # 读取图像
        with open(image_path, "rb") as f:
            image_data = f.read()

        # 转换为base64
        image_base64 = base64.b64encode(image_data).decode("utf-8")

        return {
            "type": "image",
            "format": path.suffix.lower(),
            "data": image_base64,
            "size": len(image_data),
        }

    async def process_audio(self, audio_path: str) -> Dict:
        """处理音频"""
        path = Path(audio_path)
        if not path.exists():
            raise FileNotFoundError(f"音频文件不存在: {audio_path}")

        if path.suffix.lower() not in self.supported_audio_types:
            raise ValueError(f"不支持的音频格式: {path.suffix}")

        # 读取音频
        with open(audio_path, "rb") as f:
            audio_data = f.read()

        # 转换为base64
        audio_base64 = base64.b64encode(audio_data).decode("utf-8")

        return {
            "type": "audio",
            "format": path.suffix.lower(),
            "data": audio_base64,
            "size": len(audio_data),
        }

    async def create_multimodal_message(self, text: str, media: List[Dict] = None) -> Dict:
        """创建多模态消息"""
        message = {"text": text}

        if media:
            message["media"] = media

        return message

    def get_supported_formats(self) -> Dict:
        """获取支持的格式"""
        return {
            "image": self.supported_image_types,
            "audio": self.supported_audio_types,
        }
