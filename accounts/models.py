from django.contrib.auth.models import AbstractUser, BaseUserManager
from django.db import models
from django.utils import timezone
from datetime import timedelta

# Custom User Manager
class CustomUserManager(BaseUserManager):
    def create_user(self, email, first_name=None, last_name=None, password=None):
        if not email:
            raise ValueError("The Email field must be set")
        email = self.normalize_email(email)
        user = self.model(email=email, first_name=first_name, last_name=last_name)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email, first_name=None, last_name=None, password=None):
        user = self.create_user(email, first_name, last_name, password)
        user.is_staff = True  # Required for Django admin
        user.is_superuser = True  # Required for Django admin
        user.save(using=self._db)
        return user

# Custom User Model
class CustomUser(AbstractUser):
    username = models.CharField(max_length=50, unique=True, blank=True, null=True)
    email = models.EmailField(unique=True, db_index=True)
    first_name = models.CharField(max_length=100, blank=True, null=True)  
    last_name = models.CharField(max_length=100, blank=True, null=True)  
    mobile = models.CharField(max_length=15, blank=True, null=True)      
    state = models.CharField(max_length=100, blank=True, null=True)  
    enabled = models.BooleanField(default=False)
    profile_picture = models.ImageField(upload_to="profile_pictures/", blank=True, null=True)
    createdAt = models.DateTimeField(auto_now_add=True)

    objects = CustomUserManager()

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS = []

    def __str__(self):
        return self.email

# Model for OTP verification
class AccountVerification(models.Model):
    user = models.ForeignKey(CustomUser, on_delete=models.CASCADE, db_index=True)
    otp = models.CharField(max_length=6)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()

    def save(self, *args, **kwargs):
        if not self.expires_at:
            self.expires_at = timezone.now() + timedelta(minutes=10)  # OTP expires in 10 minutes
        super().save(*args, **kwargs)

    def is_expired(self):
        return timezone.now() > self.expires_at

    def __str__(self):
        return f"OTP for {self.user.email}"
