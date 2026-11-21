package org.inaturalist.android;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.widget.Toast;

import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for handing things over to other apps (iNaturalist, browsers, etc.).
 */
public class OriginalAppUtils {
    private static final String TAG = "OriginalAppUtils";

    // Package of the original (unmodified) iNaturalist app. Written as a concatenation on purpose, so that a
    // project-wide search & replace of the package name doesn't change it to this mod's package.
    private static final String ORIGINAL_PACKAGE = "org.inaturalist" + ".android";

    // Well-known apps to offer even if Android doesn't list them for the link
    // (the chooser silently drops the ones that aren't installed)
    private static final String[] KNOWN_PACKAGES = {
            ORIGINAL_PACKAGE,
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.duckduckgo.mobile.android",
            "org.mozilla.fenix",
            "com.vivaldi.browser",
    };

    /**
     * Opens an observation page by always showing the app chooser, so the user can pick which app opens it
     * (iNaturalist, a browser, etc.). The list of apps is built by hand, because with a plain link intent
     * Android 12+ can skip the chooser and open the link straight in an app that is approved for the domain
     * (which, in this modded app, is the mod itself).
     */
    public static void openObservationInOriginalApp(Context context, int observationId) {
        Uri uri = Uri.parse(INaturalistService.HOST + "/observations/" + observationId);
        Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri);
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);

        PackageManager pm = context.getPackageManager();
        List<Intent> targets = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();

        // Apps that can open this specific link
        addTargets(pm, viewIntent, viewIntent, targets, seenPackages);

        // All browsers (any web link)
        Intent browserProbe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
        browserProbe.addCategory(Intent.CATEGORY_BROWSABLE);
        addTargets(pm, browserProbe, viewIntent, targets, seenPackages);

        // Original iNaturalist app: target its observation screen directly, because Android 12+ hides apps
        // that aren't approved for the link's domain from the chooser
        if (seenPackages.add(ORIGINAL_PACKAGE)) {
            Intent originalApp = new Intent(viewIntent);
            originalApp.setComponent(new ComponentName(ORIGINAL_PACKAGE, ORIGINAL_PACKAGE + ".ObservationViewerActivity"));
            targets.add(originalApp);
        }

        // Well-known apps
        for (String pkg : KNOWN_PACKAGES) {
            if (seenPackages.add(pkg)) {
                Intent intent = new Intent(viewIntent);
                intent.setPackage(pkg);
                targets.add(intent);
            }
        }

        // Launch each app in its own task, so it doesn't open on top of this app's task
        for (Intent target : targets) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        Intent chooser;
        if (targets.isEmpty()) {
            chooser = Intent.createChooser(viewIntent, context.getString(R.string.open_in_original_inaturalist));
        } else {
            chooser = Intent.createChooser(targets.remove(0), context.getString(R.string.open_in_original_inaturalist));
            if (!targets.isEmpty()) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.toArray(new Parcelable[0]));
            }
        }

        try {
            context.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Logger.tag(TAG).warn("No app can open the link: " + e);
            Toast.makeText(context, R.string.original_inaturalist_not_installed, Toast.LENGTH_LONG).show();
        }
    }

    private static void addTargets(PackageManager pm, Intent probe, Intent viewIntent, List<Intent> targets, Set<String> seenPackages) {
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PackageManager.MATCH_ALL : 0;
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(probe, flags);
        } catch (Exception e) {
            Logger.tag(TAG).warn("Failed querying activities: " + e);
            return;
        }

        for (ResolveInfo info : infos) {
            if ((info.activityInfo == null) || !info.activityInfo.exported) continue;
            String pkg = info.activityInfo.packageName;
            if (!seenPackages.add(pkg)) continue;

            Intent intent = new Intent(viewIntent);
            intent.setComponent(new ComponentName(pkg, info.activityInfo.name));
            targets.add(intent);
        }
    }
}
