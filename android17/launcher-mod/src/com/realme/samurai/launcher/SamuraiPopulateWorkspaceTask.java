package com.realme.samurai.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.icons.cache.CacheLookupFlag;
import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.ModelTaskController;
import com.android.launcher3.model.ModelWriter;
import com.android.launcher3.model.WorkspaceItemSpaceFinder;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceData;
import com.android.launcher3.model.data.WorkspaceItemCoordinates;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.IntSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * iOS 26 SpringBoard Home Screen Auto-Populator (v1.6.5):
 * Since iOS has no vertical slide-up App Drawer, every launchable system and user app
 * in AllAppsList must live directly on left-right Home Screen pages (Page 1, Page 2, ...),
 * while strictly preserving existing Page 0, Dock (Hotseat), and Folder placements (0 duplicates):
 * 1. Consolidates IosDialer ("Phone") & IosMessages ("Messages") into Hotseat Dock slots 0 & 1.
 * 2. Replaces stock com.android.settings on Page 0 with com.realme.samurai.iossettings ("Settings")
 *    and suppresses duplicate stock Settings placement.
 * 3. Purges retired v2.0.0 apps & third-party legacy dialers from workspace.
 */
public final class SamuraiPopulateWorkspaceTask implements LauncherModel.ModelUpdateTask {
    private static final String TAG = "SamuraiIos26Pages";
    private static final int CONTAINER_DESKTOP = -100;
    private static final int CONTAINER_HOTSEAT = -101;
    private static final int ITEM_TYPE_APPLICATION = 0;

    private static final String PREFS_NAME = "samurai_ios26_prefs";
    private static final String KEY_V175_COMMS_DOCK_MIGRATED = "v175_ios26_comms_dock_migrated";

    private static final String PKG_IOS_DIALER = "com.realme.samurai.dialer";
    private static final String PKG_LEGACY_ICALL_DIALER = "com.mayoh.contact.icontact";
    private static final String PKG_GOOGLE_DIALER = "com.google.android.dialer";
    private static final String PKG_GOOGLE_MESSAGES = "com.google.android.apps.messaging";
    private static final String PKG_IOS_MESSAGES = "com.realme.samurai.messages";
    private static final String PKG_IOS_SETTINGS = "com.realme.samurai.iossettings";
    private static final String PKG_STOCK_SETTINGS = "com.android.settings";

    private static final String LABEL_IOS_PHONE = "Phone";
    private static final String LABEL_IOS_MESSAGES = "Messages";
    private static final String LABEL_IOS_SETTINGS = "Settings";

    private static final Set<String> RETIRED_PACKAGES = Set.of(
            PKG_LEGACY_ICALL_DIALER,
            "com.realme.samurai.clock",
            "com.realme.samurai.iosclock",
            "com.realme.samurai.calculator",
            "com.realme.samurai.ioscalculator",
            "com.realme.samurai.notes",
            "com.realme.samurai.iosnotes",
            "com.realme.samurai.weather",
            "com.realme.samurai.iosweather");

    private static final class MigrationOutcome {
        final Set<String> newlyPlacedPackages = new HashSet<>(4);
        final Set<Integer> deletedItemIds = new HashSet<>(8);
    }

    @Override
    public void execute(ModelTaskController taskController, BgDataModel dataModel, AllAppsList apps) {
        populateMissingApps(taskController, dataModel, apps);
    }

    public static void populateMissingApps(
            ModelTaskController controller,
            BgDataModel dataModel,
            AllAppsList allAppsList) {
        if (controller == null || dataModel == null || allAppsList == null) {
            return;
        }
        try {
            ArrayList<AppInfo> rawApps = allAppsList.data;
            if (rawApps == null || rawApps.isEmpty()) {
                return;
            }
            Context ctx = controller.context;
            if (ctx == null || controller.model == null) {
                return;
            }

            UserHandle primaryUser = Process.myUserHandle();
            AppInfo iosDialerApp = null;
            AppInfo iosMessagesApp = null;
            AppInfo iosSettingsApp = null;

            for (int i = 0; i < rawApps.size(); i++) {
                AppInfo app = rawApps.get(i);
                if (app == null || app.componentName == null) {
                    continue;
                }
                String pkg = app.componentName.getPackageName();
                if (PKG_IOS_DIALER.equals(pkg)) {
                    app.title = LABEL_IOS_PHONE;
                    if (iosDialerApp == null
                            || (primaryUser.equals(app.user) && !primaryUser.equals(iosDialerApp.user))) {
                        iosDialerApp = app;
                    }
                } else if (PKG_IOS_MESSAGES.equals(pkg)) {
                    app.title = LABEL_IOS_MESSAGES;
                    if (iosMessagesApp == null
                            || (primaryUser.equals(app.user) && !primaryUser.equals(iosMessagesApp.user))) {
                        iosMessagesApp = app;
                    }
                } else if (PKG_IOS_SETTINGS.equals(pkg)) {
                    app.title = LABEL_IOS_SETTINGS;
                    if (iosSettingsApp == null
                            || (primaryUser.equals(app.user) && !primaryUser.equals(iosSettingsApp.user))) {
                        iosSettingsApp = app;
                    }
                }
            }

            // Snapshot and count launchable activities per (user, packageName), filtering retired apps
            // and hiding stock com.android.settings when com.realme.samurai.iossettings is installed.
            ArrayList<AppInfo> candidates = new ArrayList<>(rawApps.size());
            Map<String, Integer> pkgActivityCounts = new HashMap<>();
            for (int i = 0; i < rawApps.size(); i++) {
                AppInfo app = rawApps.get(i);
                if (app == null || app.componentName == null) {
                    continue;
                }
                String appPkg = app.componentName.getPackageName();
                if (RETIRED_PACKAGES.contains(appPkg)) {
                    continue;
                }
                if (iosSettingsApp != null && PKG_STOCK_SETTINGS.equals(appPkg)) {
                    continue;
                }
                if (iosDialerApp != null && PKG_GOOGLE_DIALER.equals(appPkg)) {
                    continue;
                }
                if (iosMessagesApp != null && PKG_GOOGLE_MESSAGES.equals(appPkg)) {
                    continue;
                }
                candidates.add(app);
                String pk = makePackageKey(app.user, appPkg);
                Integer prev = pkgActivityCounts.get(pk);
                pkgActivityCounts.put(pk, prev == null ? 1 : (prev + 1));
            }
            if (candidates.isEmpty()) {
                return;
            }

            // Sort alphabetically by visible app title so Page 1..N are clean and deterministic
            Collections.sort(candidates, (a, b) -> {
                String ta = safeTitle(a);
                String tb = safeTitle(b);
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(ta, tb);
                if (cmp != 0) {
                    return cmp;
                }
                return a.componentName.flattenToShortString()
                        .compareTo(b.componentName.flattenToShortString());
            });

            synchronized (dataModel) {
                WorkspaceData.MutableWorkspaceData mwd = dataModel.itemsIdMap;
                SparseArray<ItemInfo> map = mwd != null ? mwd.itemsIdMap : null;
                if (map == null) {
                    return;
                }

                MigrationOutcome outcome = new MigrationOutcome();

                // 1. Consolidate IosDialer ("Phone") & IosMessages ("Messages") into Hotseat Dock slots 0 & 1
                //    BEFORE purging retired packages so legacy Hotseat slot 0 swaps in-place cleanly!
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (!prefs.getBoolean(KEY_V175_COMMS_DOCK_MIGRATED, false)
                        && (iosDialerApp != null || iosMessagesApp != null)) {
                    migrateHotseatCommsOnce(
                            ctx, controller, map, prefs, iosDialerApp, iosMessagesApp, outcome);
                }

                // 2. Purge retired workspace items & replace stock Settings on Page 0 with IosSettings
                purgeRetiredAndSyncIosSettings(ctx, controller, map, iosSettingsApp, outcome);

                Set<String> existingPackages = new HashSet<>();
                Set<String> existingComponents = new HashSet<>();
                if (outcome.newlyPlacedPackages.contains(PKG_IOS_DIALER) && iosDialerApp != null) {
                    existingPackages.add(makePackageKey(iosDialerApp.user, PKG_IOS_DIALER));
                    existingComponents.add(makeComponentKey(iosDialerApp.user, iosDialerApp.componentName));
                }
                if (outcome.newlyPlacedPackages.contains(PKG_IOS_MESSAGES) && iosMessagesApp != null) {
                    existingPackages.add(makePackageKey(iosMessagesApp.user, PKG_IOS_MESSAGES));
                    existingComponents.add(makeComponentKey(iosMessagesApp.user, iosMessagesApp.componentName));
                }
                if (outcome.newlyPlacedPackages.contains(PKG_IOS_SETTINGS) && iosSettingsApp != null) {
                    existingPackages.add(makePackageKey(iosSettingsApp.user, PKG_IOS_SETTINGS));
                    existingComponents.add(makeComponentKey(iosSettingsApp.user, iosSettingsApp.componentName));
                }

                boolean hasPageZeroDesktopItems = false;

                int existingSize = map.size();
                for (int i = 0; i < existingSize; i++) {
                    ItemInfo item = map.valueAt(i);
                    if (item == null || outcome.deletedItemIds.contains(item.id)) {
                        continue;
                    }
                    if (item.container == CONTAINER_DESKTOP && item.screenId == 0) {
                        hasPageZeroDesktopItems = true;
                    }
                    if (item.itemType == ITEM_TYPE_APPLICATION) {
                        String tPkg = item.getTargetPackage();
                        if (tPkg != null && !tPkg.isEmpty()) {
                            existingPackages.add(makePackageKey(item.user, tPkg));
                            if (item instanceof WorkspaceItemInfo) {
                                WorkspaceItemInfo wi = (WorkspaceItemInfo) item;
                                String desiredLabel = desiredCustomLabel(tPkg);
                                if (desiredLabel != null
                                        && !desiredLabel.contentEquals(wi.title != null ? wi.title : "")) {
                                    wi.title = desiredLabel;
                                    wi.appTitle = desiredLabel;
                                    ModelWriter w = controller.getModelWriter();
                                    if (w != null) {
                                        w.updateItemInDatabase(wi);
                                    }
                                    controller.bindUpdatedWorkspaceItems(Collections.singletonList(wi));
                                }
                            }
                        }
                        ComponentName tComp = item.getTargetComponent();
                        if (tComp != null) {
                            existingComponents.add(makeComponentKey(item.user, tComp));
                        }
                    }
                }

                Object idpObj = InvariantDeviceProfile.INSTANCE.get(ctx);
                if (!(idpObj instanceof InvariantDeviceProfile)) {
                    return;
                }
                WorkspaceItemSpaceFinder spaceFinder = new WorkspaceItemSpaceFinder();
                spaceFinder.mDataModel = dataModel;
                spaceFinder.mIDP = (InvariantDeviceProfile) idpObj;
                spaceFinder.mModel = controller.model;

                // Preserve user's curated Page 0 layout; place all unplaced apps onto Page 1, Page 2, ...
                IntSet ignoreScreens = hasPageZeroDesktopItems ? IntSet.wrap(0) : new IntSet();
                ArrayList<ItemInfo> addedItems = new ArrayList<>();

                for (int i = 0; i < candidates.size(); i++) {
                    AppInfo app = candidates.get(i);
                    ComponentName comp = app.componentName;
                    String pkg = comp.getPackageName();
                    String pkgKey = makePackageKey(app.user, pkg);
                    String compKey = makeComponentKey(app.user, comp);

                    Integer actCount = pkgActivityCounts.get(pkgKey);
                    boolean multiActivityPkg = actCount != null && actCount > 1;
                    if (multiActivityPkg) {
                        if (existingComponents.contains(compKey)) {
                            continue;
                        }
                    } else {
                        if (existingPackages.contains(pkgKey) || existingComponents.contains(compKey)) {
                            continue;
                        }
                    }

                    WorkspaceItemInfo wi = app.makeWorkspaceItem(ctx);
                    if (wi == null) {
                        continue;
                    }
                    ensureTitleAndIconLoaded(controller, wi);
                    if (wi.spanX <= 0) wi.spanX = 1;
                    if (wi.spanY <= 0) wi.spanY = 1;

                    WorkspaceItemCoordinates coords = spaceFinder.findSpaceForItem(
                            addedItems,
                            wi.spanX,
                            wi.spanY,
                            ignoreScreens,
                            new WorkspaceItemCoordinates(0, 0, 0));
                    if (coords == null) {
                        continue;
                    }
                    coords.applyTo(wi);
                    addedItems.add(wi);
                    existingPackages.add(pkgKey);
                    existingComponents.add(compKey);
                }

                if (!addedItems.isEmpty()) {
                    ModelWriter writer = controller.getModelWriter();
                    if (writer != null) {
                        writer.addItemsToDatabase(addedItems);
                        Log.i(TAG, "Auto-placed " + addedItems.size()
                                + " launchable apps onto left-right Home Screen pages");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "populateMissingApps skipped cleanly", t);
        }
    }

    private static void purgeRetiredAndSyncIosSettings(
            Context ctx,
            ModelTaskController controller,
            SparseArray<ItemInfo> map,
            AppInfo iosSettingsApp,
            MigrationOutcome outcome) {
        ModelWriter writer = controller.getModelWriter();
        ArrayList<WorkspaceItemInfo> stockSettingsItems = new ArrayList<>(2);
        ArrayList<WorkspaceItemInfo> iosSettingsItems = new ArrayList<>(2);

        int sz = map.size();
        for (int i = 0; i < sz; i++) {
            ItemInfo item = map.valueAt(i);
            if (item == null || item.itemType != ITEM_TYPE_APPLICATION) {
                continue;
            }
            String tPkg = item.getTargetPackage();
            if (tPkg == null || tPkg.isEmpty()) {
                continue;
            }
            if (RETIRED_PACKAGES.contains(tPkg) && item instanceof WorkspaceItemInfo) {
                if (writer != null) {
                    writer.deleteItemFromDatabase(item);
                    outcome.deletedItemIds.add(item.id);
                    Log.i(TAG, "Purged retired v2.0.0 workspace item id=" + item.id + " pkg=" + tPkg);
                }
                continue;
            }
            if (iosSettingsApp != null
                    && item instanceof WorkspaceItemInfo
                    && Objects.equals(item.user, iosSettingsApp.user)) {
                if (PKG_STOCK_SETTINGS.equals(tPkg)) {
                    stockSettingsItems.add((WorkspaceItemInfo) item);
                } else if (PKG_IOS_SETTINGS.equals(tPkg)) {
                    iosSettingsItems.add((WorkspaceItemInfo) item);
                }
            }
        }

        if (iosSettingsApp == null || (stockSettingsItems.isEmpty() && iosSettingsItems.size() <= 1)) {
            return;
        }

        boolean hasIosSettingsOnPageZero = false;
        for (int i = 0; i < iosSettingsItems.size(); i++) {
            WorkspaceItemInfo wi = iosSettingsItems.get(i);
            if (wi.container == CONTAINER_DESKTOP && wi.screenId == 0) {
                hasIosSettingsOnPageZero = true;
                break;
            }
        }

        WorkspaceItemInfo preferredStockSlot = null;
        for (int i = 0; i < stockSettingsItems.size(); i++) {
            WorkspaceItemInfo wi = stockSettingsItems.get(i);
            if (wi.container == CONTAINER_DESKTOP && wi.screenId == 0) {
                preferredStockSlot = wi;
                break;
            }
        }
        if (preferredStockSlot == null && iosSettingsItems.isEmpty() && !stockSettingsItems.isEmpty()) {
            preferredStockSlot = stockSettingsItems.get(0);
        }

        if (preferredStockSlot != null && (!hasIosSettingsOnPageZero || iosSettingsItems.isEmpty())) {
            if (replaceWorkspaceItemInPlace(ctx, controller, preferredStockSlot, iosSettingsApp)) {
                if (writer != null) {
                    writer.updateItemInDatabase(preferredStockSlot);
                }
                controller.bindUpdatedWorkspaceItems(Collections.singletonList(preferredStockSlot));
                outcome.newlyPlacedPackages.add(PKG_IOS_SETTINGS);
                Log.i(TAG, "Replaced stock Settings slot (screen=" + preferredStockSlot.screenId
                        + ", cellX=" + preferredStockSlot.cellX + ", cellY=" + preferredStockSlot.cellY
                        + ") with com.realme.samurai.iossettings");

                if (writer != null) {
                    for (int i = 0; i < stockSettingsItems.size(); i++) {
                        WorkspaceItemInfo st = stockSettingsItems.get(i);
                        if (st.id != preferredStockSlot.id) {
                            writer.deleteItemFromDatabase(st);
                            outcome.deletedItemIds.add(st.id);
                        }
                    }
                    deleteDesktopDuplicates(writer, iosSettingsItems, 0, outcome.deletedItemIds);
                }
                return;
            }
        }

        // Otherwise keep one IosSettings item (preferring Page 0 if present) and delete duplicates + stock Settings
        if (!iosSettingsItems.isEmpty() && writer != null) {
            int keepIdx = 0;
            for (int i = 0; i < iosSettingsItems.size(); i++) {
                WorkspaceItemInfo wi = iosSettingsItems.get(i);
                if (wi.container == CONTAINER_DESKTOP && wi.screenId == 0) {
                    keepIdx = i;
                    break;
                }
            }
            for (int i = 0; i < iosSettingsItems.size(); i++) {
                if (i != keepIdx) {
                    WorkspaceItemInfo dup = iosSettingsItems.get(i);
                    writer.deleteItemFromDatabase(dup);
                    outcome.deletedItemIds.add(dup.id);
                }
            }
            deleteDesktopDuplicates(writer, stockSettingsItems, 0, outcome.deletedItemIds);
        }
    }

    private static void migrateHotseatCommsOnce(
            Context ctx,
            ModelTaskController controller,
            SparseArray<ItemInfo> map,
            SharedPreferences prefs,
            AppInfo iosDialerApp,
            AppInfo iosMessagesApp,
            MigrationOutcome outcome) {
        boolean hasIosDialerInHotseat = false;
        boolean hasIosDialerPlaced = false;
        boolean hasIosMessagesInHotseat = false;
        boolean hasIosMessagesPlaced = false;
        WorkspaceItemInfo googleDialerSlot = null;
        WorkspaceItemInfo googleMessagesSlot = null;
        ArrayList<WorkspaceItemInfo> desktopIosDialerItems = new ArrayList<>(1);
        ArrayList<WorkspaceItemInfo> desktopIosMessagesItems = new ArrayList<>(1);
        Set<Integer> occupiedHotseatSlots = new HashSet<>();

        int sz = map.size();
        for (int i = 0; i < sz; i++) {
            ItemInfo item = map.valueAt(i);
            if (item == null || outcome.deletedItemIds.contains(item.id)) {
                continue;
            }
            if (item.container == CONTAINER_HOTSEAT) {
                if (item.screenId >= 0) {
                    occupiedHotseatSlots.add(item.screenId);
                }
                if (item.cellX >= 0) {
                    occupiedHotseatSlots.add(item.cellX);
                }
            }
            if (item.itemType != ITEM_TYPE_APPLICATION) {
                continue;
            }
            String tPkg = item.getTargetPackage();
            if (tPkg == null || tPkg.isEmpty()) {
                continue;
            }
            if (iosDialerApp != null
                    && PKG_IOS_DIALER.equals(tPkg)
                    && Objects.equals(item.user, iosDialerApp.user)) {
                hasIosDialerPlaced = true;
                if (item.container == CONTAINER_HOTSEAT) {
                    hasIosDialerInHotseat = true;
                } else if (item.container == CONTAINER_DESKTOP && item instanceof WorkspaceItemInfo) {
                    desktopIosDialerItems.add((WorkspaceItemInfo) item);
                }
            }
            if (iosMessagesApp != null
                    && PKG_IOS_MESSAGES.equals(tPkg)
                    && Objects.equals(item.user, iosMessagesApp.user)) {
                hasIosMessagesPlaced = true;
                if (item.container == CONTAINER_HOTSEAT) {
                    hasIosMessagesInHotseat = true;
                } else if (item.container == CONTAINER_DESKTOP && item instanceof WorkspaceItemInfo) {
                    desktopIosMessagesItems.add((WorkspaceItemInfo) item);
                }
            }
            if (item.container == CONTAINER_HOTSEAT && item instanceof WorkspaceItemInfo) {
                WorkspaceItemInfo wi = (WorkspaceItemInfo) item;
                if (iosDialerApp != null
                        && googleDialerSlot == null
                        && (PKG_GOOGLE_DIALER.equals(tPkg) || PKG_LEGACY_ICALL_DIALER.equals(tPkg))
                        && Objects.equals(wi.user, iosDialerApp.user)) {
                    googleDialerSlot = wi;
                } else if (iosMessagesApp != null
                        && googleMessagesSlot == null
                        && PKG_GOOGLE_MESSAGES.equals(tPkg)
                        && Objects.equals(wi.user, iosMessagesApp.user)) {
                    googleMessagesSlot = wi;
                }
            }
        }

        ModelWriter writer = controller.getModelWriter();
        ArrayList<WorkspaceItemInfo> updatedHotseatItems = new ArrayList<>(2);

        // 1. Phone (com.realme.samurai.dialer, "Phone") -> Hotseat Dock (preferring slot 0)
        if (iosDialerApp != null) {
            if (hasIosDialerInHotseat) {
                deleteDesktopDuplicates(writer, desktopIosDialerItems, 0, outcome.deletedItemIds);
            } else if (googleDialerSlot != null) {
                if (replaceWorkspaceItemInPlace(ctx, controller, googleDialerSlot, iosDialerApp)) {
                    updatedHotseatItems.add(googleDialerSlot);
                    deleteDesktopDuplicates(writer, desktopIosDialerItems, 0, outcome.deletedItemIds);
                }
            } else {
                int freeSlot = findPreferredFreeHotseatSlot(occupiedHotseatSlots, 0);
                if (freeSlot >= 0) {
                    if (!desktopIosDialerItems.isEmpty()) {
                        WorkspaceItemInfo moved = desktopIosDialerItems.get(0);
                        moveExistingItemToHotseatSlot(controller, moved, freeSlot);
                        updatedHotseatItems.add(moved);
                        occupiedHotseatSlots.add(freeSlot);
                        deleteDesktopDuplicates(writer, desktopIosDialerItems, 1, outcome.deletedItemIds);
                        Log.i(TAG, "Consolidated Desktop Phone into Hotseat slot " + freeSlot);
                    } else if (!hasIosDialerPlaced) {
                        WorkspaceItemInfo fresh = iosDialerApp.makeWorkspaceItem(ctx);
                        if (fresh != null && writer != null) {
                            ensureTitleAndIconLoaded(controller, fresh);
                            fresh.container = CONTAINER_HOTSEAT;
                            fresh.screenId = freeSlot;
                            fresh.cellX = freeSlot;
                            fresh.cellY = 0;
                            fresh.spanX = 1;
                            fresh.spanY = 1;
                            fresh.rank = freeSlot;
                            writer.addItemsToDatabase(Collections.singletonList(fresh));
                            occupiedHotseatSlots.add(freeSlot);
                            outcome.newlyPlacedPackages.add(PKG_IOS_DIALER);
                            Log.i(TAG, "Placed iOS 26 Phone into empty Hotseat slot " + freeSlot);
                        }
                    }
                }
            }
        }

        // 2. Messages (com.realme.samurai.messages, "Messages") -> Hotseat Dock (preferring slot 1)
        if (iosMessagesApp != null) {
            if (hasIosMessagesInHotseat) {
                deleteDesktopDuplicates(writer, desktopIosMessagesItems, 0, outcome.deletedItemIds);
            } else if (googleMessagesSlot != null) {
                if (replaceWorkspaceItemInPlace(ctx, controller, googleMessagesSlot, iosMessagesApp)) {
                    updatedHotseatItems.add(googleMessagesSlot);
                    deleteDesktopDuplicates(writer, desktopIosMessagesItems, 0, outcome.deletedItemIds);
                }
            } else {
                int freeSlot = findPreferredFreeHotseatSlot(occupiedHotseatSlots, 1);
                if (freeSlot >= 0) {
                    if (!desktopIosMessagesItems.isEmpty()) {
                        WorkspaceItemInfo moved = desktopIosMessagesItems.get(0);
                        moveExistingItemToHotseatSlot(controller, moved, freeSlot);
                        updatedHotseatItems.add(moved);
                        occupiedHotseatSlots.add(freeSlot);
                        deleteDesktopDuplicates(writer, desktopIosMessagesItems, 1, outcome.deletedItemIds);
                        Log.i(TAG, "Consolidated Desktop Messages into Hotseat slot " + freeSlot);
                    } else if (!hasIosMessagesPlaced) {
                        WorkspaceItemInfo fresh = iosMessagesApp.makeWorkspaceItem(ctx);
                        if (fresh != null && writer != null) {
                            ensureTitleAndIconLoaded(controller, fresh);
                            fresh.container = CONTAINER_HOTSEAT;
                            fresh.screenId = freeSlot;
                            fresh.cellX = freeSlot;
                            fresh.cellY = 0;
                            fresh.spanX = 1;
                            fresh.spanY = 1;
                            fresh.rank = freeSlot;
                            writer.addItemsToDatabase(Collections.singletonList(fresh));
                            occupiedHotseatSlots.add(freeSlot);
                            outcome.newlyPlacedPackages.add(PKG_IOS_MESSAGES);
                            Log.i(TAG, "Placed iOS 26 Messages into empty Hotseat slot " + freeSlot);
                        }
                    }
                }
            }
        }

        if (!updatedHotseatItems.isEmpty()) {
            if (writer != null) {
                for (int i = 0; i < updatedHotseatItems.size(); i++) {
                    WorkspaceItemInfo wi = updatedHotseatItems.get(i);
                    writer.updateItemInDatabase(wi);
                    Log.i(TAG, "Migrated Hotseat slot (screen=" + wi.screenId
                            + ", cellX=" + wi.cellX + ", cellY=" + wi.cellY + ") -> "
                            + wi.getTargetComponent());
                }
            }
            controller.bindUpdatedWorkspaceItems(updatedHotseatItems);
        }

        if (iosDialerApp != null && iosMessagesApp != null) {
            prefs.edit().putBoolean(KEY_V175_COMMS_DOCK_MIGRATED, true).apply();
        }
    }

    private static int findPreferredFreeHotseatSlot(Set<Integer> occupiedHotseatSlots, int preferredSlot) {
        if (preferredSlot >= 0 && preferredSlot < 4 && !occupiedHotseatSlots.contains(preferredSlot)) {
            return preferredSlot;
        }
        for (int s = 0; s < 4; s++) {
            if (!occupiedHotseatSlots.contains(s)) {
                return s;
            }
        }
        return -1;
    }

    private static void moveExistingItemToHotseatSlot(
            ModelTaskController controller,
            WorkspaceItemInfo item,
            int slot) {
        ensureTitleAndIconLoaded(controller, item);
        item.container = CONTAINER_HOTSEAT;
        item.screenId = slot;
        item.cellX = slot;
        item.cellY = 0;
        item.spanX = 1;
        item.spanY = 1;
        item.rank = slot;
    }

    private static void deleteDesktopDuplicates(
            ModelWriter writer,
            ArrayList<WorkspaceItemInfo> desktopItems,
            int startIndex,
            Set<Integer> deletedIdsOut) {
        if (writer == null || desktopItems == null) {
            return;
        }
        for (int i = startIndex; i < desktopItems.size(); i++) {
            WorkspaceItemInfo dup = desktopItems.get(i);
            if (dup != null) {
                writer.deleteItemFromDatabase(dup);
                deletedIdsOut.add(dup.id);
                Log.i(TAG, "Removed duplicate workspace item id=" + dup.id
                        + " pkg=" + dup.getTargetPackage());
            }
        }
    }

    private static boolean replaceWorkspaceItemInPlace(
            Context ctx,
            ModelTaskController controller,
            WorkspaceItemInfo targetSlot,
            AppInfo replacementApp) {
        WorkspaceItemInfo fresh = replacementApp.makeWorkspaceItem(ctx);
        if (fresh == null) {
            return false;
        }
        ensureTitleAndIconLoaded(controller, fresh);

        int savedId = targetSlot.id;
        int savedContainer = targetSlot.container;
        int savedScreenId = targetSlot.screenId;
        int savedCellX = targetSlot.cellX;
        int savedCellY = targetSlot.cellY;
        int savedSpanX = targetSlot.spanX > 0 ? targetSlot.spanX : 1;
        int savedSpanY = targetSlot.spanY > 0 ? targetSlot.spanY : 1;
        int savedRank = targetSlot.rank;

        targetSlot.copyFrom(fresh);

        targetSlot.id = savedId;
        targetSlot.container = savedContainer;
        targetSlot.screenId = savedScreenId;
        targetSlot.cellX = savedCellX;
        targetSlot.cellY = savedCellY;
        targetSlot.spanX = savedSpanX;
        targetSlot.spanY = savedSpanY;
        targetSlot.rank = savedRank;
        targetSlot.title = fresh.title;
        targetSlot.appTitle = fresh.appTitle;

        targetSlot.bitmap = fresh.bitmap;
        targetSlot.mProgressLevel = fresh.mProgressLevel;
        targetSlot.runtimeStatusFlags = fresh.runtimeStatusFlags;

        targetSlot.intent = fresh.intent;
        targetSlot.mShortcutInfo = fresh.mShortcutInfo;
        targetSlot.options = fresh.options;
        targetSlot.status = fresh.status;
        targetSlot.personKeys = fresh.personKeys;
        return true;
    }

    private static String desiredCustomLabel(String pkg) {
        if (PKG_IOS_DIALER.equals(pkg)) {
            return LABEL_IOS_PHONE;
        }
        if (PKG_IOS_MESSAGES.equals(pkg)) {
            return LABEL_IOS_MESSAGES;
        }
        if (PKG_IOS_SETTINGS.equals(pkg)) {
            return LABEL_IOS_SETTINGS;
        }
        return null;
    }

    private static void ensureTitleAndIconLoaded(ModelTaskController controller, WorkspaceItemInfo wi) {
        if (controller == null || controller.iconCache == null || wi == null) {
            return;
        }
        if (wi.bitmap == null
                || wi.bitmap.isLowRes()
                || wi.title == null
                || wi.title.length() == 0) {
            controller.iconCache.getTitleAndIcon(wi, CacheLookupFlag.DEFAULT_LOOKUP_FLAG);
        }
        String desired = desiredCustomLabel(wi.getTargetPackage());
        if (desired != null) {
            wi.title = desired;
            wi.appTitle = desired;
        }
    }

    private static String safeTitle(AppInfo app) {
        if (app.title != null) {
            String s = app.title.toString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return app.componentName != null ? app.componentName.getPackageName() : "";
    }

    private static String makePackageKey(UserHandle user, String pkg) {
        int u = user != null ? user.hashCode() : 0;
        return u + "|" + pkg;
    }

    private static String makeComponentKey(UserHandle user, ComponentName comp) {
        int u = user != null ? user.hashCode() : 0;
        return u + "|" + comp.flattenToShortString();
    }
}
