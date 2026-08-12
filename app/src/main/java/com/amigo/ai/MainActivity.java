package com.amigo.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10;
    private static final int REQ_CAMERA = 11;

    private EditText serverEdit;
    private EditText roomEdit;
    private EditText textEdit;
    private TextView statusView;
    private TextView responseView;
    private Button recordButton;

    private SharedPreferences prefs;
    private String deviceId;
    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording = false;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("amigo", MODE_PRIVATE);
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }

        buildUi();
        requestPermissionsIfNeeded();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "AR"));
                tts.setSpeechRate(1.0f);
            }
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AMIGO AI");
        title.setTextSize(28f);
        root.addView(title);

        serverEdit = new EditText(this);
        serverEdit.setHint("Servidor, ej: http://192.168.1.3:8765");
        serverEdit.setText(prefs.getString("server", "http://192.168.1.3:8765"));
        root.addView(serverEdit);

        roomEdit = new EditText(this);
        roomEdit.setHint("Nombre del dispositivo / habitación");
        roomEdit.setText(prefs.getString("room", "Sala"));
        root.addView(roomEdit);

        Button connectButton = new Button(this);
        connectButton.setText("Guardar y conectar");
        connectButton.setOnClickListener(v -> connect());
        root.addView(connectButton);

        statusView = new TextView(this);
        statusView.setText("Servidor: sin comprobar");
        statusView.setTextSize(18f);
        root.addView(statusView);

        recordButton = new Button(this);
        recordButton.setText("Empezar a hablar");
        recordButton.setOnClickListener(v -> toggleRecording());
        root.addView(recordButton);

        Button cameraButton = new Button(this);
        cameraButton.setText("Cámara / analizar foto");
        cameraButton.setOnClickListener(v -> openCamera());
        root.addView(cameraButton);

        textEdit = new EditText(this);
        textEdit.setHint("También podés escribirle algo...");
        textEdit.setMinLines(2);
        root.addView(textEdit);

        Button sendTextButton = new Button(this);
        sendTextButton.setText("Enviar texto");
        sendTextButton.setOnClickListener(v -> sendText());
        root.addView(sendTextButton);

        TextView label = new TextView(this);
        label.setText("Respuesta:");
        label.setTextSize(18f);
        root.addView(label);

        responseView = new TextView(this);
        responseView.setTextSize(17f);
        responseView.setTextIsSelectable(true);
        root.addView(responseView);

        setContentView(scroll);
    }

    private void requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_PERMS);
            }
        }
    }

    private String baseUrl() {
        return serverEdit.getText().toString().trim().replaceAll("/+$", "");
    }

    private void connect() {
        String server = baseUrl();
        String room = roomEdit.getText().toString().trim();
        if (server.isEmpty() || room.isEmpty()) {
            statusView.setText("Falta servidor o nombre");
            return;
        }
        prefs.edit().putString("server", server).putString("room", room).apply();
        statusView.setText("Conectando...");

        new Thread(() -> {
            try {
                HttpURLConnection health = (HttpURLConnection) new URL(server + "/health").openConnection();
                health.setConnectTimeout(5000);
                health.setReadTimeout(5000);
                if (health.getResponseCode() != 200) throw new Exception("Health HTTP " + health.getResponseCode());
                health.disconnect();

                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("name", room);
                body.put("platform", "android");
                JSONObject caps = new JSONObject();
                caps.put("microphone", true);
                caps.put("camera", true);
                caps.put("speaker", true);
                body.put("capabilities", caps);

                String result = postJson(server + "/api/register", body.toString());
                runOnUiThread(() -> statusView.setText("Servidor: conectado ✓"));
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void toggleRecording() {
        if (!recording) startRecording(); else stopRecordingAndSend();
    }

    private void startRecording() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
            return;
        }
        try {
            audioFile = new File(getCacheDir(), "amigo_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordButton.setText("Enviar voz");
            statusView.setText("Escuchando...");
        } catch (Exception e) {
            statusView.setText("Error micrófono: " + e.getMessage());
            releaseRecorder();
        }
    }

    private void stopRecordingAndSend() {
        try {
            recorder.stop();
        } catch (Exception ignored) {}
        releaseRecorder();
        recording = false;
        recordButton.setText("Empezar a hablar");
        statusView.setText("Pensando...");
        sendAudio(audioFile);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private void sendAudio(File file) {
        new Thread(() -> {
            try {
                String json = multipart(baseUrl() + "/api/audio-chat", "audio", file, "audio/mp4", null);
                JSONObject obj = new JSONObject(json);
                String transcript = obj.optString("transcript");
                String reply = obj.optString("reply");
                showReply("Vos: " + transcript + "\n\nAmigo: " + reply, reply);
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void sendText() {
        String text = textEdit.getText().toString().trim();
        if (text.isEmpty()) return;
        statusView.setText("Pensando...");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("text", text);
                body.put("session_id", "home");
                JSONObject obj = new JSONObject(postJson(baseUrl() + "/api/text-chat", body.toString()));
                String reply = obj.optString("reply");
                showReply("Vos: " + text + "\n\nAmigo: " + reply, reply);
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void openCamera() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMS);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) startActivityForResult(intent, REQ_CAMERA);
        else statusView.setText("No hay app de cámara disponible");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK && data != null) {
            Object extra = data.getExtras() != null ? data.getExtras().get("data") : null;
            if (extra instanceof Bitmap) {
                try {
                    File image = new File(getCacheDir(), "camera_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(image);
                    ((Bitmap) extra).compress(Bitmap.CompressFormat.JPEG, 92, fos);
                    fos.close();
                    statusView.setText("Analizando imagen...");
                    sendImage(image);
                } catch (Exception e) {
                    statusView.setText("Error guardando foto: " + e.getMessage());
                }
            }
        }
    }

    private void sendImage(File image) {
        new Thread(() -> {
            try {
                String json = multipart(baseUrl() + "/api/vision-chat", "image", image, "image/jpeg", "Describe qué ves y responde en español de forma clara.");
                String reply = new JSONObject(json).optString("reply");
                showReply("Amigo: " + reply, reply);
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Error imagen: " + e.getMessage()));
            }
        }).start();
    }

    private void showReply(String display, String speech) {
        runOnUiThread(() -> {
            responseView.setText(display);
            statusView.setText("Listo");
            if (tts != null && !speech.isEmpty()) tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "amigo");
        });
    }

    private String postJson(String target, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        c.getOutputStream().write(bytes);
        return readConnection(c);
    }

    private String multipart(String target, String fileField, File file, String mime, String prompt) throws Exception {
        String boundary = "----Amigo" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        DataOutputStream out = new DataOutputStream(c.getOutputStream());
        writeField(out, boundary, "device_id", deviceId);
        writeField(out, boundary, "session_id", "home");
        if (prompt != null) writeField(out, boundary, "prompt", prompt);

        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = fis.read(buffer)) > 0) out.write(buffer, 0, n);
        fis.close();
        out.writeBytes("\r\n--" + boundary + "--\r\n");
        out.flush();
        out.close();
        return readConnection(c);
    }

    private void writeField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private String readConnection(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb);
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        releaseRecorder();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
