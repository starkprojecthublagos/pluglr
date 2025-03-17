from django.urls import re_path
from .consumers import VideoLiveStreamConsumer
from .web_rtc_consumer import WebRTCConsumer

websocket_urlpatterns = [
    re_path(r'ws/video/stream/(?P<event_id>\w+)/$', VideoLiveStreamConsumer.as_asgi()),
    re_path(r"ws/webrtc/$", WebRTCConsumer.as_asgi()),
    re_path(r'/ws/camera/stream/(?P<user_id>\w+)/$', WebRTCConsumer.as_asgi())
]
