import socket
import struct
import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.vision import FaceLandmarkerOptions, FaceLandmarker, RunningMode
import time
import os

# Путь к модели — ищем рядом со скриптом
script_dir = os.path.dirname(os.path.abspath(__file__))
model_path = os.path.join(script_dir, "face_landmarker.task")

if not os.path.exists(model_path):
    print(f"ERROR: face_landmarker.task not found at {model_path}")
    print("Download it from: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task")
    exit(1)

print(f"Loading model from: {model_path}")

# Новый API MediaPipe 0.10+
base_options = python.BaseOptions(model_asset_path=model_path)
options = FaceLandmarkerOptions(
    base_options=base_options,
    running_mode=RunningMode.IMAGE,   # IMAGE — самый стабильный для нашего случая
    num_faces=1,
    min_face_detection_confidence=0.5,
    min_face_presence_confidence=0.5,
    min_tracking_confidence=0.5,
    output_face_blendshapes=False,
    output_facial_transformation_matrixes=False
)

face_landmarker = FaceLandmarker.create_from_options(options)
print("MediaPipe FaceLandmarker loaded successfully")

# Для стабилизации
last_valid_landmarks = None
frame_count = 0

# Сервер
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('localhost', 5678))
server.listen(1)
print("MediaPipe server started on port 5678...")

conn, addr = server.accept()
print(f"Java connected: {addr}")

while True:
    try:
        # Читаем размер кадра
        raw_size = conn.recv(4)
        if not raw_size or len(raw_size) < 4:
            break
        size = struct.unpack('>I', raw_size)[0]
        if size == 0:
            break

        # Читаем JPEG байты
        data = b''
        while len(data) < size:
            chunk = conn.recv(min(size - len(data), 65536))
            if not chunk:
                break
            data += chunk

        # Декодируем кадр
        frame = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            conn.sendall(struct.pack('>I', 0))
            continue

        frame_count += 1

        # BGR -> RGB и создаём MediaPipe Image
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

        # Детекция
        result = face_landmarker.detect(mp_image)

        if result.face_landmarks:
            lm_list = result.face_landmarks[0]
            arr = np.array([[lm.x, lm.y, lm.z] for lm in lm_list], dtype=np.float32)

            # Проверяем что нашли именно глаза, а не ноздри
            # Радужка landmarks 468 (левый) и 473 (правый)
            if len(arr) >= 478:
                l_x, l_y = arr[468][0], arr[468][1]
                r_x, r_y = arr[473][0], arr[473][1]

                # Глаза должны быть: в верхней половине лица, не у краёв,
                # примерно на одной высоте, и разнесены по горизонтали
                valid = (
                    0.05 < l_x < 0.95 and
                    0.05 < l_y < 0.65 and
                    0.05 < r_x < 0.95 and
                    0.05 < r_y < 0.65 and
                    abs(l_y - r_y) < 0.12 and   # на одной высоте
                    abs(l_x - r_x) > 0.05        # разнесены горизонтально
                )

                if valid:
                    last_valid_landmarks = arr
                    payload = arr.tobytes()
                    conn.sendall(struct.pack('>I', len(payload)) + payload)
                    if frame_count % 60 == 0:
                        print(f"[OK]       L=({l_x:.3f},{l_y:.3f}) R=({r_x:.3f},{r_y:.3f})")
                else:
                    # Подозрительно — шлём последние валидные
                    if last_valid_landmarks is not None:
                        payload = last_valid_landmarks.tobytes()
                        conn.sendall(struct.pack('>I', len(payload)) + payload)
                    else:
                        conn.sendall(struct.pack('>I', 0))
                    if frame_count % 60 == 0:
                        print(f"[FILTERED] L=({l_x:.3f},{l_y:.3f}) R=({r_x:.3f},{r_y:.3f})")
            else:
                # Старая модель без радужки (< 478 точек) — не подходит
                print("WARNING: Model does not have iris landmarks (need 478 points, got", len(arr), ")")
                conn.sendall(struct.pack('>I', 0))
        else:
            # Лицо не найдено
            if frame_count % 60 == 0:
                print("[NO FACE]")
            conn.sendall(struct.pack('>I', 0))

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
        break

conn.close()
server.close()
print("Server stopped")