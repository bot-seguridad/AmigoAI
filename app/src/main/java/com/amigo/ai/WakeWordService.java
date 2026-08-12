package com.amigo.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class WakeWordService extends Service implements RecognitionListener {
    public static final String ACTION_STOP = "com.amigo.ai.STOP_WAKE_WORD";
    private static final String CHANNEL_ID = "amigo_wake_word";
    private static final int NOTIFICATION_ID = 77;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private TextToSpeech tts;
    private ToneGenerator tone;
    private SharedPreferences prefs;
    private String deviceId;
    private boolean listening = false;
    private boolean awaitingCommand = false;
    private boolean speaking = false;
    private boolean stopped = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("amigo", MODE_PRIVATE);
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Escuchando “Hey Amigo”"));

        tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        initTts();
        initRecognizer();
        handler.postDelayed(this::startListening, 700);
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "AR"));
                tts.setSpeechRate(1.0f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { speaking = true; }
                    @Override public void onDone(String utteranceId) {
                        speaking = false;
                        awaitingCommand = false;
                        handler.postDelayed(WakeWordService.this::startListening, 500);
                    }
                    @Override public void onError(String utteranceId) {
                        speaking = false;
                        awaitingCommand = false;
                        handler.postDelayed(WakeWordService.this::startListening, 500);
                    }
                });
            }
        });
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Reconocimiento de voz no disponible");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } else {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
        recognizer.setRecognitionListener(this);

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    }

    private void startListening() {
        if (stopped || speaking || recognizer == null || listening) return;
        try {
            listening = true;
            updateNotification(awaitingCommand ? "Te escucho…" : "Escuchando “Hey Amigo”");
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            listening = false;
            retryListen(1200);
        }
    }

    private void stopRecognizer() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void retryListen(long delayMs) {
        if (!stopped && !speaking) handler.postDelayed(this::startListening, delayMs);
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9ñ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return n;
    }

    private void processRecognizedText(String original) {
        String n = normalize(original);
        if (n.isEmpty()) {
            retryListen(400);
            return;
        }

        if (awaitingCommand) {
            awaitingCommand = false;
            sendCommand(original.trim());
            return;
        }

        int pos = n.indexOf("hey amigo");
        if (pos < 0) pos = n.indexOf("ey amigo");
        if (pos < 0) {
            retryListen(250);
            return;
        }

        // If the user says the command in the same phrase, extract everything after "amigo".
        String lower = original.toLowerCase(Locale.ROOT);
        int amigo = lower.indexOf("amigo");
        String remainder = amigo >= 0 ? original.substring(amigo + 5).trim() : "";
        remainder = remainder.replaceFirst("^[,.:;!?\\-\\s]+", "").trim();

        if (!remainder.isEmpty()) {
            beep();
            sendCommand(remainder);
        } else {
            awaitingCommand = true;
            beep();
            updateNotification("Te escucho…");
            retryListen(300);
        }
    }

    private void beep() {
        if (tone != null) {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130); } catch (Exception ignored) {}
        }
    }

    private String baseUrl() {
        String server = prefs.getString("server", "http://192.168.1.3:8765");
        return server == null ? "http://192.168.1.3:8765" : server.trim().replaceAll("/+$", "");
    }

    private void sendCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            awaitingCommand = true;
            retryListen(300);
            return;
        }
        stopRecognizer();
        updateNotification("Pensando…");

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("text", command.trim());
                body.put("session_id", "home");

                JSONObject response = new JSONObject(postJson(baseUrl() + "/api/text-chat", body.toString()));
                String reply = response.optString("reply", "").trim();
                if (reply.isEmpty()) reply = "No recibí una respuesta.";
                String finalReply = reply;
                handler.post(() -> speak(finalReply));
            } catch (Exception e) {
                handler.post(() -> speak("No pude comunicarme con el servidor."));
            }
        }).start();
    }

    private void speak(String text) {
        stopRecognizer();
        speaking = true;
        updateNotification("Respondiendo…");
        if (tts != null) {
            Bundle params = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "amigo_reply");
        } else {
            speaking = false;
            retryListen(700);
        }
    }

    private String postJson(String target, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return sb.toString();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Hey Amigo",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantiene activo el micrófono para detectar Hey Amigo");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Amigo AI")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopped = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        stopped = false;
        handler.postDelayed(this::startListening, 300);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }

    @Override
    public void onError(int error) {
        listening = false;
        long wait = 350;
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) wait = 1000;
        if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) wait = 2500;
        retryListen(wait);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) processRecognizedText(list.get(0));
        else retryListen(300);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        // We intentionally wait for the final result to avoid sending partial commands twice.
    }

    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        stopRecognizer();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        if (tone != null) {
            try { tone.release(); } catch (Exception ignored) {}
            tone = null;
        }
        super.onDestroy();
    }
}
