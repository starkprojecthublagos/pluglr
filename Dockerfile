FROM python:3.10-slim

# Set environment variables
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Set work directory
WORKDIR /app

# Install system dependencies including MySQL/MariaDB client and Pillow dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    mariadb-client \
    libmariadb-dev \
    pkg-config \
    build-essential \
    gcc \
    libjpeg-dev \
    zlib1g-dev \
    libfreetype6-dev \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements file
COPY requirements.txt /app/

# Install Python dependencies
RUN pip install --upgrade pip && \
    pip install -r requirements.txt && \
    pip install channels daphne

# Copy project
COPY . /app/

# Run the application
CMD ["python", "manage.py", "runserver", "0.0.0.0:8001"]
