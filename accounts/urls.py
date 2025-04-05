from django.urls import path, include
from .views import RegisterAPI, RestPasswordAPI, VerifyOTPAPI, UpdateProfileView, UploadProfilePictureView, AuthUserChangePasswordAPI, PasswordUpdateAPI, CompleteProfileAPI, LoginAPI, FindUserByIdAPI, ResendOTPAPI, SendWelcomeEmailAPI, GenerateRefreshTokenAPI

urlpatterns = [
    path('register/', RegisterAPI.as_view(), name='register'),
    path('login/', LoginAPI.as_view(), name='login'),
    path('verify-otp/', VerifyOTPAPI.as_view(), name='verify_otp'),
    path('resend-otp/', ResendOTPAPI.as_view(), name='resend_otp'),
    path('token/refresh/', GenerateRefreshTokenAPI.as_view(), name='token_refresh'),
    path('send-welcome-email/', SendWelcomeEmailAPI.as_view(), name='send_welcome_email'),
    path('create-new-password/', PasswordUpdateAPI.as_view(), name='create_new_password'),
    
    path('user/reset-password/', RestPasswordAPI.as_view(), name='reset_password'),
    path('user/update-password/', AuthUserChangePasswordAPI.as_view(), name='update_password'),
    path('user/complete-profile/', CompleteProfileAPI.as_view(), name='complete_profile'),
    path('user/id/<int:user_id>/', FindUserByIdAPI.as_view(), name='find_user_by_id'),
    path('user/upload-profile-picture/', UploadProfilePictureView.as_view(), name='upload_profile_picture'),
    path('user/update-profile/', UpdateProfileView.as_view(), name='update_profile'),
]
