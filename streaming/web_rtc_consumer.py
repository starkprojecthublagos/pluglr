import json
import redis
import secrets
import jwt
import aiohttp
from django.conf import settings
from channels.generic.websocket import AsyncWebsocketConsumer
from urllib.parse import parse_qs

# Redis connection
redis_client = redis.StrictRedis(
    host='localhost', port=6379, db=0, decode_responses=True)


class WebRTCConsumer(AsyncWebsocketConsumer):
    async def connect(self):
        """Handles WebSocket connection for both broadcasters & participants."""
        
        # Initialize user_id early to prevent AttributeError in disconnect
        self.user_id = None  # Default to None in case of early return

        # Extract token from query params
        query_params = parse_qs(self.scope["query_string"].decode())
        token = query_params.get("token", [None])[0]

        if not token:
            await self.accept()
            await self.send(json.dumps({"error": "invalid token"}))
            await self.close()
            return

        try:
            # Decode the JWT token
            payload = jwt.decode(
                token, settings.JWT_SECRET_KEY, algorithms=['HS256'])
            self.user_id = payload.get("user_id")  # Set self.user_id early

            if not self.user_id:
                await self.send(json.dumps({"error": "invalid token"}))
                await self.close()
                return

        except (jwt.ExpiredSignatureError, jwt.InvalidTokenError):
            await self.send(json.dumps({"error": "invalid token"}))
            await self.close()
            return

        # Fetch user from Redis or authentication service
        redis_user = redis_client.get(f"user:{self.user_id}")

        if redis_user:
            user_info = json.loads(redis_user)
        else:
            async with aiohttp.ClientSession() as session:
                async with session.get(f"http://localhost:8000/api/v1/user/id/{self.user_id}") as response:
                    if response.status != 200:
                        await self.send(json.dumps({"error": "user not found"}))
                        await self.close()
                        return

                    user_data = await response.json()
                    user_info = user_data.get("data", {})

            fetched_user_id = str(user_info.get("id"))

            if fetched_user_id != str(self.user_id):
                await self.send(json.dumps({"error": "user not found"}))
                await self.close()
                return

            redis_client.setex(f"user:{self.user_id}",
                               3600, json.dumps(user_info))

        # Accept WebSocket connection
        await self.accept()

        self.username = user_info.get("lastname", "Unknown")
        self.room_id = query_params.get("room_id", [None])[0]

        if self.room_id:
            room_data = redis_client.get(f"room:{self.room_id}")
            if not room_data:
                await self.send(json.dumps({"error": "room not found"}))
                await self.close()
                return

            self.room_group_name = f"webrtc_{self.room_id}"
        else:
            self.room_id = f"room_{secrets.token_urlsafe(30)}"
            self.room_group_name = f"webrtc_{self.room_id}"
            redis_client.setex(f"room:{self.room_id}", 3600, json.dumps(
                {"host_id": self.user_id, "host_username": self.username}))

            await self.send(json.dumps({
                "type": "room_created", "room_id": self.room_id, "username": self.username, "id": self.user_id
            }))

        await self.channel_layer.group_add(self.room_group_name, self.channel_name)

    async def disconnect(self, close_code):
        """Handles WebSocket disconnection."""
        if self.user_id:  # Prevent AttributeError
            redis_client.delete(f"user:{self.user_id}")

            if hasattr(self, "room_id"):  # Ensure room_id is set before using it
                room_data = redis_client.get(f"room:{self.room_id}")
                if room_data:
                    host_data = json.loads(room_data)
                    if host_data["host_id"] == self.user_id:
                        redis_client.delete(f"room:{self.room_id}")
                    else:
                        if hasattr(self, "room_group_name"):  # Ensure room_group_name exists
                            await self.channel_layer.group_send(
                                self.room_group_name,
                                {
                                    "type": "user_left",
                                    "username": self.username,
                                    "user_id": self.user_id,
                                },
                            )

        if hasattr(self, "room_group_name"):  # Prevent AttributeError
            await self.channel_layer.group_discard(self.room_group_name, self.channel_name)


    async def user_left(self, event):
        """Notify room when a user leaves."""
        await self.send(json.dumps({"type": "user_left", "username": event["username"]}))

    async def receive(self, text_data):
        """Handles signaling messages for WebRTC."""
        data = json.loads(text_data)

        if data["type"] in ["offer", "answer", "candidate"]:
            await self.channel_layer.group_send(
                self.room_group_name, {"type": "webrtc_message", "message": data}
            )

    async def webrtc_message(self, event):
        """Broadcasts WebRTC signaling messages to all clients."""
        await self.send(json.dumps(event["message"]))

    async def user_joined(self, event):
        """Notifies room that a new user has joined."""
        await self.send(json.dumps({"type": "user_joined", "username": event["username"]}))
