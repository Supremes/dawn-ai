"""认证模型"""

from typing import Optional
from pydantic import BaseModel
from datetime import datetime


class User(BaseModel):
    """用户模型"""
    id: str
    username: str
    email: str
    hashed_password: str
    is_active: bool = True
    is_admin: bool = False
    created_at: datetime = None
    updated_at: datetime = None


class Token(BaseModel):
    """Token模型"""
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class TokenData(BaseModel):
    """Token数据"""
    username: str = None
    user_id: str = None
