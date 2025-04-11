from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import logging
import os
import shutil

logging.basicConfig(level=logging.INFO)

UPLOAD_DIR = 'Server/Potholes'
CONSOLIDATED_DIR = 'Server/Pothole'
os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(CONSOLIDATED_DIR, exist_ok=True)
CONSOLIDATED_GPS_LOG = os.path.join(CONSOLIDATED_DIR, 'consolidated_gps_log.txt')
DETECTION_COUNTER_FILE = os.path.join(UPLOAD_DIR, 'detection_counter.txt')

# Initialize detection counter
if not os.path.exists(DETECTION_COUNTER_FILE):
    with open(DETECTION_COUNTER_FILE, 'w') as f:
        f.write('0')

class MyHTTPRequestHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_type = self.headers.get('Content-Type')
        detection_id = self.headers.get('Detection-ID')

        if detection_id is None:
            self.send_error_response(400, 'Detection-ID header missing')
            return

        if content_type == 'application/json':
            self.handle_json_post(detection_id)
        elif content_type == 'image/jpeg':
            self.handle_image_post(detection_id)
        else:
            self.send_error_response(400, 'Unsupported content type')

    def handle_json_post(self, detection_id):
        try:
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)

            if not post_data:
                raise ValueError('Empty JSON data received')

            logging.info(f"Received POST data: {post_data}")
            data = json.loads(post_data.decode())

            if 'latitude' in data and 'longitude' in data:
                self.write_to_file(os.path.join(UPLOAD_DIR, 'gps_log.txt'), data)
                self.write_to_file(CONSOLIDATED_GPS_LOG, data)
                self.send_success_response('gps')
            elif 'type' in data:
                sensor_type = data['type']
                if sensor_type == 'accelerometer':
                    self.write_to_file(os.path.join(CONSOLIDATED_DIR, f'accelerometer_log_{detection_id}.txt'), data)
                    self.send_success_response('accelerometer')
                elif sensor_type == 'gyroscope':
                    self.write_to_file(os.path.join(CONSOLIDATED_DIR, f'gyroscope_log_{detection_id}.txt'), data)
                    self.send_success_response('gyroscope')
                else:
                    raise ValueError('Invalid sensor data type')
            else:
                raise ValueError('Invalid data format')
        except Exception as e:
            logging.error(f"Error processing POST request: {e}")
            self.send_error_response(400, str(e))

    def handle_image_post(self, detection_id):
        try:
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)

            if not post_data:
                raise ValueError('Empty image data received')

            filename = f"{detection_id}.jpg"
            filepath = os.path.join(CONSOLIDATED_DIR, filename)

            with open(filepath, 'wb') as f:
                f.write(post_data)

            logging.info(f"Saved image as {filename}")
            self.send_success_response('image', filename)
        except Exception as e:
            logging.error(f"Error processing image POST request: {e}")
            self.send_error_response(400, str(e))

    def do_GET(self):
        try:
            gps_data, accelerometer_data, gyroscope_data = [], [], []

            for root, _, files in os.walk(CONSOLIDATED_DIR):
                for file in files:
                    file_path = os.path.join(root, file)
                    if file == 'consolidated_gps_log.txt':
                        self.read_and_append_data(file_path, gps_data)
                    elif file.startswith('accelerometer_log_'):
                        self.read_and_append_data(file_path, accelerometer_data)
                    elif file.startswith('gyroscope_log_'):
                        self.read_and_append_data(file_path, gyroscope_data)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({
                'gps_log': gps_data,
                'accelerometer_log': accelerometer_data,
                'gyroscope_log': gyroscope_data
            }).encode())
        except Exception as e:
            logging.error(f"Error processing GET request: {e}")
            self.send_error_response(500, 'Server error')

    def write_to_file(self, filepath, data):
        try:
            with open(filepath, 'a') as file:
                file.write(json.dumps(data) + '\n')
        except Exception as e:
            logging.error(f"Error writing to file {filepath}: {e}")

    def read_and_append_data(self, filepath, data_list):
        try:
            with open(filepath, 'r') as file:
                for line in file:
                    line = line.strip()
                    if line:  # Ensure the line is not empty
                        try:
                            data_list.append(json.loads(line))
                        except json.JSONDecodeError as e:
                            logging.error(f"JSON decoding error in file {filepath}: {e}")
        except Exception as e:
            logging.error(f"Error reading file {filepath}: {e}")

    def send_success_response(self, response_type, filename=None):
        response = {'status': 'success', 'type': response_type}
        if filename:
            response['filename'] = filename
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def send_error_response(self, status_code, message):
        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'status': 'error', 'message': message}).encode())

def get_next_detection_id():
    with open(DETECTION_COUNTER_FILE, 'r+') as f:
        counter = int(f.read().strip())
        next_id = f"detection#{counter + 1}"
        f.seek(0)
        f.write(str(counter + 1))
        f.truncate()
    return next_id

def classify_pothole(max_accel, max_gyro):
    max_reading = max(max_accel, max_gyro)
    if max_reading > 25:
        return "Severe Pothole"
    elif max_reading > 13:
        return "Moderate Pothole"
    else:
        return "Minor Pothole"

def consolidate_data():
    for detection_id in os.listdir(UPLOAD_DIR):
        detection_dir = os.path.join(UPLOAD_DIR, detection_id)
        if not os.path.isdir(detection_dir):
            continue

        gps_log = os.path.join(CONSOLIDATED_DIR, 'consolidated_gps_log.txt')
        accelerometer_log = os.path.join(CONSOLIDATED_DIR, f'accelerometer_log_{detection_id}.txt')
        gyroscope_log = os.path.join(CONSOLIDATED_DIR, f'gyroscope_log_{detection_id}.txt')
        image_file = os.path.join(CONSOLIDATED_DIR, f"{detection_id}.jpg")

        # Check if all required files are present
        if not (os.path.exists(gps_log) and os.path.exists(accelerometer_log) and os.path.exists(gyroscope_log) and os.path.exists(image_file)):
            logging.info(f"Skipping {detection_id} due to missing data")
            continue

        with open(gps_log, 'r') as f:
            gps_data = json.loads(f.readline().strip())

        with open(accelerometer_log, 'r') as f:
            acc_data = [json.loads(line.strip()) for line in f.readlines()]
        
        with open(gyroscope_log, 'r') as f:
            gyro_data = [json.loads(line.strip()) for line in f.readlines()]

        max_acc = max([max(d['x'], d['y'], d['z']) for d in acc_data])
        max_gyro = max([max(d['x'], d['y'], d['z']) for d in gyro_data])
        severity = classify_pothole(max_acc, max_gyro)

        summary_data = {
            'latitude': gps_data['latitude'],
            'longitude': gps_data['longitude'],
            'severity': severity,
            'image': os.path.basename(image_file)
        }

        with open(os.path.join(CONSOLIDATED_DIR, f"summary_{detection_id}.json"), 'w') as f:
            json.dump(summary_data, f, indent=4)

def start_server(port=8000):
    server_address = ('', port)
    httpd = HTTPServer(server_address, MyHTTPRequestHandler)
    logging.info(f'Server running on port {port}')
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        logging.info('Server stopped.')

if __name__ == "__main__":
    consolidate_data()  # Call this function before starting the server to consolidate existing data
    start_server()
