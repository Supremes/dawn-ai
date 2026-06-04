"""认证服务"""

from typing import Optional
from datetime import datetime, timedelta
from jose import JWTError, jwt
from passlib.context import CryptContext
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from .models import User, Token, TokenData
from ..config import get_settings


class AuthService:
    """认证服务"""

    def __init__(self):
        settings = get_settings()
        self.secret_key = settings.secret_key or "your-secret-key-here"
        self.algorithm = "HS256"
        self.access_token_expire_minutes = 30

        self.pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
        self.oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")

        # 用户存储（实际应该用数据库）
        self._users = {}

    def verify_password(self, plain_password: str, hashed_password: str) -> bool:
        """验证密码"""
        return self.pwd_context.verify(plain_password, hashed_password)

    def get_password_hash(self, password: str) -> str:
        """获取密码哈希"""
        return self.pwd_context.hash(password)

    async def create_user(self, username: str, email: str, password: str) -> User:
        """创建用户"""
        if username in self._users:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="用户名已存在",
            )

        user = User(
            id=username,
            username=username,
            email=email,
            hashed_password=self.get_password_hash(password),
            created_at=datetime.now(),
        )

        self._users[username] = user
        return user

    async def authenticate_user(self, username: str, password: str) -> Optional[User]:
        """认证用户"""
        user = self._users.get(username)
        if not user:
            return None
        if not self.verify_password(password, user.hashed_password):
            return None
        return user

    def create_access_token(self, data: dict, expires_delta: timedelta = None) -> str:
        """创建访问Token"""
        to_encode = data.copy()
        if expires_delta:
            expire = datetime.utcnow() + expires_delta
        else:
            expire = datetime.utcnow() + timedelta(minutes=self.access_token_expire_minutes)

        to_encode.update({"exp": expire})
        encoded_jwt = jwt.encode(to_encode, self.secret_key, algorithm=self.algorithm)
        return encoded_jwt

    async def get_current_user(self, token: str = Depends(OAuth2PasswordBearer(tokenUrl="token"))) -> User:
        """获取当前用户"""
        credentials_exception = HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无法验证凭据",
            headers={"WWW-Authenticate": "Bearer"},
        )

        try:
            payload = jwt.decode(token, self.secret_key, algorithms=[self.algorithm])
            username: str = payload.get("sub")
            if username is None:
                raise credentials_exception
            token_data = TokenData(username=username)
        except JWTError:
            raise credentials_exception

        user = self._users.get(token_data.username)
        if user is None:
            raise credentials_exception

        return user

    async def get_current_active_user(self, current_user: User = Depends(get_current_user)) -> User:
        """获取当前活跃用户"""
        if not current_user.is_active:
            raise HTTPException(status_code=400, detail="用户已禁用")
        return current_user
