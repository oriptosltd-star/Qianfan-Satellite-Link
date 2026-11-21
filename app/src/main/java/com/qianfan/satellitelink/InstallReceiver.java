package com.qianfan.satellitelink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;
import android.content.SharedPreferences;

public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.qianfan.satellitelink.INSTALL_COMPLETE".equals(intent.getAction())) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(context, "Plugin installed successfully!", Toast.LENGTH_SHORT).show();
                
                if (packageName != null) {
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                    }
                }
            } else {
                Toast.makeText(context, "Install failed: " + message, Toast.LENGTH_LONG).show();
            }
        }
    }
}
