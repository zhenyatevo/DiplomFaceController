import mediapipe as mp
import cv2
import socket
import struct
import numpy as np

mp_face = mp.solutions.face_mesh
face_mesh = mp_face.FaceMesh(
    static_image_mode=False,
    max_num_faces=1,
    refine_landmarks=True,   # включает радужку (landmarks 468-477)
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5
)

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('localhost', 5678))
server.listen(1)
print("MediaPipe server started on port 5678...")

conn, addr = server.accept()
print(f"Java connected: {addr}")

while True:
    try:
        # Читаем размер JPEG от Java (4 байта big-endian)
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
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        results = face_mesh.process(rgb)

        if results.multi_face_landmarks:
            lm = results.multi_face_landmarks[0].landmark
            # 478 точек × 3 координаты (x, y, z) = 1434 float32
            arr = np.array([[p.x, p.y, p.z] for p in lm], dtype=np.float32)
            payload = arr.tobytes()
            conn.sendall(struct.pack('>I', len(payload)) + payload)
        else:
            # Лицо не найдено — шлём 0
            conn.sendall(struct.pack('>I', 0))

    except Exception as e:
        print(f"Error: {e}")
        break

conn.close()
server.close()
print("Server stopped")