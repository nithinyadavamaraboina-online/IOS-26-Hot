#!/usr/bin/env python3
"""
Build & Sign Patched Pixel Launcher (NexusLauncherRelease.apk v1.4, versionCode=914):
1. Decodes pristine stock NexusLauncherRelease.apk smali via apktool.jar (-r, keeping resources untouched).
2. Applies surgical smali hooks across 6 classes:
   - QsbWidgetFactory.smali (100% bottom Google Search Bar removal)
   - HotseatSpecsProvider.smali (hotseatQsbSpace=0 + dock/pill clearance)
   - Hotseat.smali (concentric dock padding + compact "Q Search" water-droplet pill)
   - CellLayout.smali (90Hz AGSL Dual-Buffer Toroidal Liquid Glass Dock pre-dispatchDraw)
   - IconProvider.smali (Universal iOS 26 Tier-1 Bespoke + Tier-2 Parametric icon pipeline)
   - LauncherIconProviderImpl.smali & LauncherIconProviderImpl_Factory.smali (PersistedItemState + live reload)
3. Compiles pure-Java android17/launcher-mod/src -> classes3.dex via javac 17 + d8.
4. Patches binary AXML AndroidManifest.xml cleanly:
   - android:versionCode = 914
   - android:versionName = "17.4-ios26"
   - <uses-permission android:name="android.permission.READ_WALLPAPER_INTERNAL"/>
5. Repacks directly from pristine stock APK (preserving uncompressed resources.arsc & lib/*.so),
   4-byte page-aligns via zipalign, signs with samurai-ota-release.jks (v1+v2+v3), and verifies.
"""

import argparse
import glob
import os
import shutil
import struct
import subprocess
import sys
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
A17_ROOT = os.path.dirname(SCRIPT_DIR)
REPO_ROOT = os.path.dirname(A17_ROOT)

WORK_ROOT = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
STOCK_APK = os.environ.get(
    "STOCK_NEXUS_LAUNCHER_APK",
    os.path.join(WORK_ROOT, "downloads/pixel_a17_cp3a/NexusLauncherRelease.apk"),
)
OUT_APK = os.environ.get(
    "OUT_NEXUS_LAUNCHER_APK",
    os.path.join(WORK_ROOT, "out/apks/NexusLauncherRelease.apk"),
)
BUILD_DIR = os.path.join(WORK_ROOT, "work/apk-build/NexusLauncherMod")

JAVA_HOME = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")
JAVA = os.path.join(JAVA_HOME, "bin/java")
JAVAC = os.path.join(JAVA_HOME, "bin/javac")

APKTOOL_JAR = os.path.join(WORK_ROOT, "tools/apktool.jar")
ANDROID_SDK = os.path.join(WORK_ROOT, "tools/android-sdk")
BUILD_TOOLS = os.path.join(ANDROID_SDK, "build-tools/35.0.0")
PLATFORM_JAR = os.path.join(ANDROID_SDK, "platforms/android-35/android.jar")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2")
D8 = os.path.join(BUILD_TOOLS, "d8")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner")

KEYSTORE = os.path.join(REPO_ROOT, "work/a17/keys/samurai-ota-release.jks")
IOS26_ICON_ASSETS_DIR = os.environ.get(
    "IOS26_ICON_ASSETS_DIR",
    os.path.join(WORK_ROOT, "work/ios26_v15_assets/assets/ios26_icons"),
)

TARGET_VERSION_CODE = 934
TARGET_VERSION_NAME = "17.7.0-ios26"
ADDED_PERMISSION = "android.permission.READ_WALLPAPER_INTERNAL"


def run(cmd, **kwargs):
    res = subprocess.run(cmd, text=True, capture_output=True, **kwargs)
    if res.returncode != 0:
        sys.stderr.write(f"COMMAND FAILED ({res.returncode}): {' '.join(cmd)}\n")
        sys.stderr.write(res.stdout + "\n" + res.stderr + "\n")
        raise RuntimeError(f"Command failed: {cmd[0]}")
    return res.stdout


def find_smali(decoded_dir: str, rel_path: str) -> str:
    for sub in ("smali", "smali_classes2"):
        candidate = os.path.join(decoded_dir, sub, rel_path)
        if os.path.isfile(candidate):
            return candidate
    raise FileNotFoundError(f"Smali file not found: {rel_path}")


def replace_exact_once(path: str, old: str, new: str):
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    cnt = src.count(old)
    if cnt != 1:
        raise RuntimeError(f"Expected exactly 1 match in {os.path.basename(path)}, found {cnt}:\n{old}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))


def strip_final_instance_fields(path: str):
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    cnt = src.count(".field public final ")
    if cnt < 1:
        raise RuntimeError(f"Expected .field public final declarations in {os.path.basename(path)}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(".field public final ", ".field public "))


def promote_quickstep_fields_public(decoded_dir: str):
    targets = {
        "com/android/quickstep/FullscreenDrawParams.smali": [
            "currentCornerRadius:F",
            "taskCornerRadius:F",
            "windowCornerRadius:F",
        ],
        "com/android/quickstep/views/OverviewActionsView.smali": [
            "mActionButtons:Landroid/widget/LinearLayout;",
        ],
        "com/android/quickstep/views/IconAppChipView.smali": [
            "iconView:Lcom/android/quickstep/views/IconView;",
            "appTitle:Landroid/widget/TextView;",
            "iconArrowView:Landroid/widget/ImageView;",
            "menuAnchorView:Landroid/view/View;",
        ],
        "com/android/quickstep/views/RecentsView.smali": [
            "mFullscreenProgress:F",
            "mTaskModalness:F",
            "mGridProgress:F",
            "mContentAlpha:F",
        ],
    }
    for rel_path, descriptors in targets.items():
        s_path = find_smali(decoded_dir, rel_path)
        with open(s_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        matched = {desc: 0 for desc in descriptors}
        for idx, line in enumerate(lines):
            if not line.startswith(".field "):
                continue
            for desc in descriptors:
                if f" {desc}" in line and (line.rstrip().endswith(desc)):
                    if line.startswith(".field private "):
                        lines[idx] = ".field public " + line[len(".field private ") :]
                    elif line.startswith(".field protected "):
                        lines[idx] = ".field public " + line[len(".field protected ") :]
                    elif not line.startswith(".field public "):
                        raise RuntimeError(f"Unexpected field modifiers in {rel_path}: {line.strip()}")
                    matched[desc] += 1
        for desc, cnt in matched.items():
            if cnt != 1:
                raise RuntimeError(f"Expected exactly 1 field '{desc}' in {rel_path}, found {cnt}")
        with open(s_path, "w", encoding="utf-8") as f:
            f.writelines(lines)


def apply_smali_hooks(decoded_dir: str):
    promote_quickstep_fields_public(decoded_dir)
    # 1. QsbWidgetFactory.smali -> 100% bottom Google QSB removal
    qsb_path = find_smali(decoded_dir, "com/android/launcher3/qsb/QsbWidgetFactory.smali")
    with open(qsb_path, "r", encoding="utf-8") as f:
        qsb_src = f.read()
    m_start = qsb_src.find(".method public createView(Landroid/view/ViewGroup;)Landroid/view/View;")
    m_end = qsb_src.find(".end method", m_start)
    if m_start < 0 or m_end < 0:
        raise RuntimeError("QsbWidgetFactory.createView not found")
    new_create_view = (
        ".method public createView(Landroid/view/ViewGroup;)Landroid/view/View;\n"
        "    .locals 1\n\n"
        "    sget v0, Lcom/android/launcher3/R$id;->search_container_hotseat:I\n\n"
        "    invoke-static {p1, v0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->createHiddenQsbView(Landroid/view/ViewGroup;I)Landroid/view/View;\n\n"
        "    move-result-object p0\n\n"
        "    return-object p0\n"
        ".end method"
    )
    qsb_patched = qsb_src[:m_start] + new_create_view + qsb_src[m_end + len(".end method"):]
    with open(qsb_path, "w", encoding="utf-8") as f:
        f.write(qsb_patched)

    # 2. HotseatSpecsProvider.smali -> zero hotseatQsbSpace & reserve dock/pill edgePadding
    hsp_path = find_smali(decoded_dir, "com/android/launcher3/responsive/HotseatSpecsProvider.smali")
    replace_exact_once(
        hsp_path,
        "    iput p1, p0, Lcom/android/launcher3/responsive/CalculatedHotseatSpec;->edgePadding:I\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V",
        "    iput p1, p0, Lcom/android/launcher3/responsive/CalculatedHotseatSpec;->edgePadding:I\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->adjustCalculatedHotseatSpec(Lcom/android/launcher3/responsive/CalculatedHotseatSpec;)V\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V",
    )

    # 3. Hotseat.smali -> <init>, setInsets, onMeasure, onLayout
    hs_path = find_smali(decoded_dir, "com/android/launcher3/Hotseat.smali")
    replace_exact_once(
        hs_path,
        "    iput-object p2, p0, Lcom/android/launcher3/Hotseat;->mQsbAlphaChannels:Lcom/android/launcher3/util/MultiValueAlpha;\n\n"
        "    invoke-virtual {p2, v0}, Lcom/android/launcher3/util/MultiValueAlpha;->setUpdateVisibility(Z)V\n\n"
        "    return-void\n"
        ".end method",
        "    iput-object p2, p0, Lcom/android/launcher3/Hotseat;->mQsbAlphaChannels:Lcom/android/launcher3/util/MultiValueAlpha;\n\n"
        "    invoke-virtual {p2, v0}, Lcom/android/launcher3/util/MultiValueAlpha;->setUpdateVisibility(Z)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onHotseatInit(Lcom/android/launcher3/Hotseat;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        hs_path,
        "    invoke-virtual {p0, v2, v3, v4, v1}, Landroid/view/ViewGroup;->setPadding(IIII)V\n\n"
        "    invoke-virtual {p0, v0}, Landroid/view/ViewGroup;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V\n\n"
        "    invoke-static {p0, p1}, Lcom/android/launcher3/InsettableFrameLayout;->dispatchInsets(Landroid/view/ViewGroup;Landroid/graphics/Rect;)V",
        "    invoke-virtual {p0, v2, v3, v4, v1}, Landroid/view/ViewGroup;->setPadding(IIII)V\n\n"
        "    invoke-virtual {p0, v0}, Landroid/view/ViewGroup;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->adjustHotseatLayout(Lcom/android/launcher3/Hotseat;Landroid/graphics/Rect;)V\n\n"
        "    invoke-static {p0, p1}, Lcom/android/launcher3/InsettableFrameLayout;->dispatchInsets(Landroid/view/ViewGroup;Landroid/graphics/Rect;)V",
    )
    replace_exact_once(
        hs_path,
        ".method public final onMeasure(II)V\n"
        "    .locals 1\n\n"
        "    invoke-super {p0, p1, p2}, Lcom/android/launcher3/CellLayout;->onMeasure(II)V",
        ".method public final onMeasure(II)V\n"
        "    .locals 1\n\n"
        "    invoke-super {p0, p1, p2}, Lcom/android/launcher3/CellLayout;->onMeasure(II)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onHotseatMeasure(Lcom/android/launcher3/Hotseat;)V",
    )
    replace_exact_once(
        hs_path,
        ".method public final onLayout(ZIIII)V\n"
        "    .locals 3\n\n"
        "    invoke-super/range {p0 .. p5}, Lcom/android/launcher3/CellLayout;->onLayout(ZIIII)V",
        ".method public final onLayout(ZIIII)V\n"
        "    .locals 3\n\n"
        "    invoke-super/range {p0 .. p5}, Lcom/android/launcher3/CellLayout;->onLayout(ZIIII)V\n\n"
        "    invoke-static {p0, p2, p3, p4, p5}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onHotseatLayout(Lcom/android/launcher3/Hotseat;IIII)V",
    )

    # 4. CellLayout.smali -> 90Hz Liquid Glass Dock pre-dispatchDraw
    cl_path = find_smali(decoded_dir, "com/android/launcher3/CellLayout.smali")
    replace_exact_once(
        cl_path,
        ".method public final dispatchDraw(Landroid/graphics/Canvas;)V\n"
        "    .locals 5\n\n"
        "    invoke-super {p0, p1}, Landroid/view/ViewGroup;->dispatchDraw(Landroid/graphics/Canvas;)V",
        ".method public final dispatchDraw(Landroid/graphics/Canvas;)V\n"
        "    .locals 5\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onCellLayoutPreDispatchDraw(Lcom/android/launcher3/CellLayout;Landroid/graphics/Canvas;)V\n\n"
        "    invoke-super {p0, p1}, Landroid/view/ViewGroup;->dispatchDraw(Landroid/graphics/Canvas;)V",
    )

    # 5. IconProvider.smali -> Universal iOS 26 Icon Pipeline
    ip_path = find_smali(decoded_dir, "com/android/launcher3/icons/IconProvider.smali")
    replace_exact_once(
        ip_path,
        "    :goto_9\n"
        "    invoke-direct {v0, v2, v1, v11}, Landroid/graphics/drawable/AdaptiveIconDrawable;-><init>"
        "(Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;)V\n\n"
        "    :cond_f\n"
        "    return-object v0\n"
        ".end method",
        "    :goto_9\n"
        "    invoke-direct {v0, v2, v1, v11}, Landroid/graphics/drawable/AdaptiveIconDrawable;-><init>"
        "(Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;)V\n\n"
        "    :cond_f\n"
        "    move-object/from16 v1, p0\n\n"
        "    move-object/from16 v2, p1\n\n"
        "    move-object/from16 v3, p2\n\n"
        "    move/from16 v4, p3\n\n"
        "    invoke-static {v1, v2, v3, v4, v0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->transformIcon(Lcom/android/launcher3/icons/IconProvider;Landroid/content/pm/PackageItemInfo;"
        "Landroid/content/pm/ApplicationInfo;ILandroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;\n\n"
        "    move-result-object v0\n\n"
        "    return-object v0\n"
        ".end method",
    )

    # 6. LauncherIconProviderImpl.smali & LauncherIconProviderImpl_Factory.smali
    lip_path = find_smali(decoded_dir, "com/android/launcher3/icons/LauncherIconProviderImpl.smali")
    replace_exact_once(
        lip_path,
        "    invoke-static/range {v0 .. v5}, Lcom/android/launcher3/icons/PersistedItemState;->copy$default"
        "(Lcom/android/launcher3/icons/PersistedItemState;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)"
        "Lcom/android/launcher3/icons/PersistedItemState;\n\n"
        "    move-result-object v0\n\n"
        "    iput-object v0, p0, Lcom/android/launcher3/icons/IconProvider;->mSystemState:Lcom/android/launcher3/icons/PersistedItemState;",
        "    invoke-static/range {v0 .. v5}, Lcom/android/launcher3/icons/PersistedItemState;->copy$default"
        "(Lcom/android/launcher3/icons/PersistedItemState;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)"
        "Lcom/android/launcher3/icons/PersistedItemState;\n\n"
        "    move-result-object v0\n\n"
        "    invoke-static {p0, v0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->appendIconSystemState(Lcom/android/launcher3/icons/LauncherIconProviderImpl;"
        "Lcom/android/launcher3/icons/PersistedItemState;)Lcom/android/launcher3/icons/PersistedItemState;\n\n"
        "    move-result-object v0\n\n"
        "    iput-object v0, p0, Lcom/android/launcher3/icons/IconProvider;->mSystemState:Lcom/android/launcher3/icons/PersistedItemState;",
    )

    lipf_path = find_smali(decoded_dir, "com/android/launcher3/icons/LauncherIconProviderImpl_Factory.smali")
    replace_exact_once(
        lipf_path,
        "    invoke-virtual {p0, v0}, Lcom/android/launcher3/util/DaggerSingletonTracker;->addCloseable(Lcom/android/launcher3/util/SafeCloseable;)V\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V\n\n"
        "    return-object v6\n"
        ".end method",
        "    invoke-virtual {p0, v0}, Lcom/android/launcher3/util/DaggerSingletonTracker;->addCloseable(Lcom/android/launcher3/util/SafeCloseable;)V\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V\n\n"
        "    invoke-static {v6}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onIconProviderCreated(Lcom/android/launcher3/icons/LauncherIconProviderImpl;)V\n\n"
        "    return-object v6\n"
        ".end method",
    )

    # 7. StashedHandleView.smali -> Globally hide bottom gesture pill by default + live Settings toggle
    shv_path = find_smali(decoded_dir, "com/android/launcher3/taskbar/StashedHandleView.smali")
    replace_exact_once(
        shv_path,
        "    iput p1, p0, Lcom/android/launcher3/taskbar/StashedHandleView;->mStashedHandleDarkColor:I\n\n"
        "    return-void\n"
        ".end method",
        "    iput p1, p0, Lcom/android/launcher3/taskbar/StashedHandleView;->mStashedHandleDarkColor:I\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onStashedHandleInit(Landroid/view/View;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        shv_path,
        "# virtual methods\n"
        ".method public final updateHandleColor(ZZ)V",
        "# virtual methods\n"
        ".method public draw(Landroid/graphics/Canvas;)V\n"
        "    .locals 0\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->drawIosStashedHandleIfNeeded(Landroid/view/View;Landroid/graphics/Canvas;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final updateHandleColor(ZZ)V",
    )

    # 8. AbstractStateChangeTouchController.smali -> Disable vertical slide-up All Apps gesture (iOS left-right only)
    astc_path = find_smali(decoded_dir, "com/android/launcher3/touch/AbstractStateChangeTouchController.smali")
    replace_exact_once(
        astc_path,
        ".method public shouldOpenAllApps(Z)Z\n"
        "    .locals 1\n\n"
        "    if-eqz p1, :cond_0\n\n"
        "    iget-boolean v0, p0, Lcom/android/launcher3/touch/AbstractStateChangeTouchController;->mIsTrackpadReverseScroll:Z\n\n"
        "    if-eqz v0, :cond_1\n\n"
        "    :cond_0\n"
        "    if-nez p1, :cond_2\n\n"
        "    iget-boolean p0, p0, Lcom/android/launcher3/touch/AbstractStateChangeTouchController;->mIsTrackpadReverseScroll:Z\n\n"
        "    if-eqz p0, :cond_2\n\n"
        "    :cond_1\n"
        "    const/4 p0, 0x1\n\n"
        "    return p0\n\n"
        "    :cond_2\n"
        "    const/4 p0, 0x0\n\n"
        "    return p0\n"
        ".end method",
        ".method public shouldOpenAllApps(Z)Z\n"
        "    .locals 0\n\n"
        "    const/4 p0, 0x0\n\n"
        "    return p0\n"
        ".end method",
    )

    # 9. AllAppsEduView.smali -> Suppress "Swipe up to open All Apps" tutorial overlay
    edu_path = find_smali(decoded_dir, "com/android/quickstep/views/AllAppsEduView.smali")
    with open(edu_path, "r", encoding="utf-8") as f:
        edu_src = f.read()
    e_start = edu_src.find(".method public static show(Lcom/android/launcher3/Launcher;)V")
    e_end = edu_src.find(".end method", e_start)
    if e_start < 0 or e_end < 0:
        raise RuntimeError("AllAppsEduView.show not found")
    edu_patched = (
        edu_src[:e_start]
        + ".method public static show(Lcom/android/launcher3/Launcher;)V\n"
        "    .locals 0\n\n"
        "    return-void\n"
        ".end method"
        + edu_src[e_end + len(".end method") :]
    )
    with open(edu_path, "w", encoding="utf-8") as f:
        f.write(edu_patched)

    # 10. Workspace.smali -> 90Hz horizontal scroll hook + uniform spacing pre-setInsets sync
    ws_path = find_smali(decoded_dir, "com/android/launcher3/Workspace.smali")
    replace_exact_once(
        ws_path,
        ".method public final onScrollChanged(IIII)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1, p2, p3, p4}, Lcom/android/launcher3/PagedView;->onScrollChanged(IIII)V",
        ".method public final onScrollChanged(IIII)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1, p2, p3, p4}, Lcom/android/launcher3/PagedView;->onScrollChanged(IIII)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onWorkspaceScrolled(Lcom/android/launcher3/Workspace;)V",
    )
    replace_exact_once(
        ws_path,
        ".method public final setInsets(Landroid/graphics/Rect;)V\n"
        "    .locals 8\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/Workspace;->mLauncher:Lcom/android/launcher3/Launcher;",
        ".method public final setInsets(Landroid/graphics/Rect;)V\n"
        "    .locals 8\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onWorkspacePreSetInsets(Lcom/android/launcher3/Workspace;Landroid/graphics/Rect;)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/Workspace;->mLauncher:Lcom/android/launcher3/Launcher;",
    )

    # 11. LauncherModel$LoaderTransaction.smali -> Auto-populate all unplaced apps onto left-right pages on load commit
    lt_path = find_smali(decoded_dir, "com/android/launcher3/LauncherModel$LoaderTransaction.smali")
    replace_exact_once(
        lt_path,
        "    iget-object v1, v1, Lcom/android/launcher3/LauncherModel;->mLoadCompleteFuture:Ljava/util/concurrent/CompletableFuture;\n\n"
        "    sget-object v4, Lqc/i;->a:Lqc/i;\n\n"
        "    invoke-virtual {v1, v4}, Ljava/util/concurrent/CompletableFuture;->complete(Ljava/lang/Object;)Z",
        "    iget-object v1, v1, Lcom/android/launcher3/LauncherModel;->mLoadCompleteFuture:Ljava/util/concurrent/CompletableFuture;\n\n"
        "    sget-object v4, Lqc/i;->a:Lqc/i;\n\n"
        "    invoke-virtual {v1, v4}, Ljava/util/concurrent/CompletableFuture;->complete(Ljava/lang/Object;)Z\n\n"
        "    iget-object v1, p0, Lcom/android/launcher3/LauncherModel$LoaderTransaction;->mTask:Lcom/android/launcher3/model/LoaderTask;\n\n"
        "    invoke-static {v1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLoaderTaskComplete(Lcom/android/launcher3/model/LoaderTask;)V",
    )

    # 12. PackageUpdatedTask.smali -> Auto-place newly installed / enabled apps onto left-right pages immediately
    put_path = find_smali(decoded_dir, "com/android/launcher3/model/tasks/PackageUpdatedTask.smali")
    replace_exact_once(
        put_path,
        "    invoke-virtual/range {p1 .. p2}, Lcom/android/launcher3/model/ModelTaskController;->bindUpdatedWidgets(Lcom/android/launcher3/model/BgDataModel;)V\n\n"
        "    :cond_b\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual/range {p1 .. p2}, Lcom/android/launcher3/model/ModelTaskController;->bindUpdatedWidgets(Lcom/android/launcher3/model/BgDataModel;)V\n\n"
        "    :cond_b\n"
        "    invoke-static/range {p1 .. p3}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onPackageUpdatedTaskComplete(Lcom/android/launcher3/model/ModelTaskController;"
        "Lcom/android/launcher3/model/BgDataModel;Lcom/android/launcher3/model/AllAppsList;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 13. DeviceProfile.smali, WorkspaceProfile.smali & HotseatProfile.smali -> Uniform G_1 == G_2 == G_3 Home Screen gaps
    wp_path = find_smali(decoded_dir, "com/android/launcher3/deviceprofile/WorkspaceProfile.smali")
    strip_final_instance_fields(wp_path)

    hp_path = find_smali(decoded_dir, "com/android/launcher3/deviceprofile/HotseatProfile.smali")
    strip_final_instance_fields(hp_path)

    dp_path = find_smali(decoded_dir, "com/android/launcher3/DeviceProfile.smali")
    replace_exact_once(
        dp_path,
        "    iput-object p14, p0, Lcom/android/launcher3/DeviceProfile;->dropTargetProfile:Lcom/android/launcher3/deviceprofile/DropTargetProfile;\n\n"
        "    iput-object p15, p0, Lcom/android/launcher3/DeviceProfile;->taskbarProfile:Lcom/android/launcher3/deviceprofile/TaskbarProfile;\n\n"
        "    return-void\n"
        ".end method",
        "    iput-object p14, p0, Lcom/android/launcher3/DeviceProfile;->dropTargetProfile:Lcom/android/launcher3/deviceprofile/DropTargetProfile;\n\n"
        "    iput-object p15, p0, Lcom/android/launcher3/DeviceProfile;->taskbarProfile:Lcom/android/launcher3/deviceprofile/TaskbarProfile;\n\n"
        "    invoke-static/range {p0 .. p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onDeviceProfileCreated(Lcom/android/launcher3/DeviceProfile;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 14. MultiValueAlpha.smali -> Invalidate parent Hotseat & SearchPill whenever mShortcutsAndWidgets alpha updates
    mva_path = find_smali(decoded_dir, "com/android/launcher3/util/MultiValueAlpha.smali")
    replace_exact_once(
        mva_path,
        ".method public apply(F)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory;->apply(F)V\n\n"
        "    iget-boolean p1, p0, Lcom/android/launcher3/util/MultiValueAlpha;->mUpdateVisibility:Z\n\n"
        "    if-eqz p1, :cond_0\n\n"
        "    iget-object p1, p0, Lcom/android/launcher3/util/MultiPropertyFactory;->mTarget:Ljava/lang/Object;\n\n"
        "    check-cast p1, Landroid/view/View;\n\n"
        "    iget p0, p0, Lcom/android/launcher3/util/MultiValueAlpha;->mHiddenVisibility:I\n\n"
        "    invoke-static {p1, p0}, Lcom/android/launcher3/anim/AlphaUpdateListener;->updateVisibility(Landroid/view/View;I)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
        ".method public apply(F)V\n"
        "    .locals 1\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory;->apply(F)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/util/MultiPropertyFactory;->mTarget:Ljava/lang/Object;\n\n"
        "    iget-boolean p1, p0, Lcom/android/launcher3/util/MultiValueAlpha;->mUpdateVisibility:Z\n\n"
        "    if-eqz p1, :cond_0\n\n"
        "    iget-object p1, p0, Lcom/android/launcher3/util/MultiPropertyFactory;->mTarget:Ljava/lang/Object;\n\n"
        "    check-cast p1, Landroid/view/View;\n\n"
        "    iget p0, p0, Lcom/android/launcher3/util/MultiValueAlpha;->mHiddenVisibility:I\n\n"
        "    invoke-static {p1, p0}, Lcom/android/launcher3/anim/AlphaUpdateListener;->updateVisibility(Landroid/view/View;I)V\n\n"
        "    :cond_0\n"
        "    invoke-static {v0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onMultiValueAlphaApplied(Ljava/lang/Object;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 15. ScalingWorkspaceRevealAnim.smali -> Keep Hotseat in direct window-buffer LAYER_TYPE_NONE during 1000ms app-close reveal
    swra_path = find_smali(decoded_dir, "com/android/quickstep/util/ScalingWorkspaceRevealAnim.smali")
    replace_exact_once(
        swra_path,
        "    invoke-virtual {v4, v10, v1}, Landroid/view/ViewGroup;->setLayerType(ILandroid/graphics/Paint;)V\n\n"
        "    invoke-virtual {v6, v10, v1}, Landroid/view/ViewGroup;->setLayerType(ILandroid/graphics/Paint;)V\n\n"
        "    new-instance v1, Lcom/android/quickstep/util/ScalingWorkspaceRevealAnim$3;",
        "    invoke-virtual {v4, v10, v1}, Landroid/view/ViewGroup;->setLayerType(ILandroid/graphics/Paint;)V\n\n"
        "    new-instance v1, Lcom/android/quickstep/util/ScalingWorkspaceRevealAnim$3;",
    )

    # 16. WorkspaceItemSpaceFinder.smali -> Restore R8-stripped public default <init>()V so SamuraiPopulateWorkspaceTask can instantiate it directly
    wisf_path = find_smali(decoded_dir, "com/android/launcher3/model/WorkspaceItemSpaceFinder.smali")
    replace_exact_once(
        wisf_path,
        "# virtual methods\n"
        ".method public final findNextAvailableIconSpaceInScreen(Ljava/util/List;IIII[I)Z",
        "# direct methods\n"
        ".method public constructor <init>()V\n"
        "    .locals 0\n\n"
        "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n\n"
        "    return-void\n"
        ".end method\n\n"
        "# virtual methods\n"
        ".method public final findNextAvailableIconSpaceInScreen(Ljava/util/List;IIII[I)Z",
    )

    # 17. LauncherRootView.smali -> 80% Home Screen Wallpaper vs 100% App Icons z-depth separation
    lrv_path = find_smali(decoded_dir, "com/android/launcher3/LauncherRootView.smali")
    replace_exact_once(
        lrv_path,
        ".method public final dispatchDraw(Landroid/graphics/Canvas;)V\n"
        "    .locals 6\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/LauncherRootView;->mSysUiScrim:Lcom/android/launcher3/graphics/SysUiScrim;",
        ".method public final dispatchDraw(Landroid/graphics/Canvas;)V\n"
        "    .locals 6\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/LauncherRootView;->mStatefulContainer:Lcom/android/launcher3/statemanager/StatefulContainer;\n\n"
        "    invoke-static {p0, v0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherRootPreDispatchDraw(Landroid/view/View;Ljava/lang/Object;Landroid/graphics/Canvas;)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/LauncherRootView;->mSysUiScrim:Lcom/android/launcher3/graphics/SysUiScrim;",
    )

    # 18. ModelWriter.smali -> Restore R8-inlined deleteItemFromDatabase overloads for duplicate consolidation
    mw_path = find_smali(decoded_dir, "com/android/launcher3/model/ModelWriter.smali")
    replace_exact_once(
        mw_path,
        ".method public final updateItemInDatabase(Lcom/android/launcher3/model/data/ItemInfo;)V\n"
        "    .locals 2\n\n"
        "    new-instance v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;\n\n"
        "    const/4 v1, 0x1\n\n"
        "    invoke-direct {v0, v1}, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    iput-object p1, v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;->e:Lcom/android/launcher3/model/data/ItemInfo;\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/launcher3/model/ModelWriter;->execute(Led/c;)V\n\n"
        "    return-void\n"
        ".end method",
        ".method public final updateItemInDatabase(Lcom/android/launcher3/model/data/ItemInfo;)V\n"
        "    .locals 2\n\n"
        "    new-instance v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;\n\n"
        "    const/4 v1, 0x1\n\n"
        "    invoke-direct {v0, v1}, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    iput-object p1, v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda0;->e:Lcom/android/launcher3/model/data/ItemInfo;\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/launcher3/model/ModelWriter;->execute(Led/c;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final deleteItemFromDatabase(Lcom/android/launcher3/model/data/ItemInfo;Ljava/lang/String;)V\n"
        "    .locals 2\n\n"
        "    if-nez p1, :cond_0\n\n"
        "    return-void\n\n"
        "    :cond_0\n"
        "    new-instance v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda12;\n\n"
        "    const/4 v1, 0x1\n\n"
        "    invoke-direct {v0, v1}, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda12;-><init>(I)V\n\n"
        "    iput-object p1, v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda12;->f:Ljava/lang/Object;\n\n"
        "    iput-object p2, v0, Lcom/android/launcher3/model/ModelWriter$$ExternalSyntheticLambda12;->e:Ljava/lang/String;\n\n"
        "    invoke-static {}, Ljava/lang/invoke/VarHandle;->storeStoreFence()V\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/launcher3/model/ModelWriter;->execute(Led/c;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final deleteItemFromDatabase(Lcom/android/launcher3/model/data/ItemInfo;)V\n"
        "    .locals 1\n\n"
        "    const-string v0, \"SamuraiIos26DockConsolidation\"\n\n"
        "    invoke-virtual {p0, p1, v0}, Lcom/android/launcher3/model/ModelWriter;->deleteItemFromDatabase"
        "(Lcom/android/launcher3/model/data/ItemInfo;Ljava/lang/String;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 19. StatusBarTouchController$$ExternalSyntheticLambda0.smali -> Right-half Home Screen swipe opens iOS 26 Control Center
    sbtc_path = find_smali(
        decoded_dir,
        "com/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController$$ExternalSyntheticLambda0.smali",
    )
    replace_exact_once(
        sbtc_path,
        ".method public final accept(Ljava/lang/Object;)V\n"
        "    .locals 1\n\n"
        "    iget-object p0, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController$$ExternalSyntheticLambda0;->a:Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;\n\n"
        "    check-cast p1, Landroid/view/MotionEvent;\n\n"
        "    iget-object p0, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;->mSystemUiProxy:Lcom/android/quickstep/SystemUiProxy;\n\n"
        "    invoke-virtual {p0}, Lcom/android/quickstep/SystemUiProxy;->isActive()Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_0\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/SystemUiProxy;->onStatusBarTouchEvent(Landroid/view/MotionEvent;)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
        ".method public final accept(Ljava/lang/Object;)V\n"
        "    .locals 3\n\n"
        "    iget-object p0, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController$$ExternalSyntheticLambda0;->a:Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;\n\n"
        "    check-cast p1, Landroid/view/MotionEvent;\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;->mLauncher:Lcom/android/launcher3/BaseActivity;\n\n"
        "    iget-object v1, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;->mDownEvent:Landroid/graphics/PointF;\n\n"
        "    invoke-static {v0, v1, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->handleHomeStatusBarSwipe(Landroid/content/Context;Landroid/graphics/PointF;Landroid/view/MotionEvent;)Z\n\n"
        "    move-result v2\n\n"
        "    if-eqz v2, :cond_samurai_noti\n\n"
        "    return-void\n\n"
        "    :cond_samurai_noti\n"
        "    iget-object p0, p0, Lcom/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController;->mSystemUiProxy:Lcom/android/quickstep/SystemUiProxy;\n\n"
        "    invoke-virtual {p0}, Lcom/android/quickstep/SystemUiProxy;->isActive()Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_0\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/SystemUiProxy;->onStatusBarTouchEvent(Landroid/view/MotionEvent;)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
    )

    # 20. FullscreenDrawParams.smali -> 30dp iOS task card corner radius & >= 12dp window transition radius
    fdp_path = find_smali(decoded_dir, "com/android/quickstep/FullscreenDrawParams.smali")
    replace_exact_once(
        fdp_path,
        ".method public final updateCornerRadius(Landroid/content/Context;)V\n"
        "    .locals 1\n\n"
        "    iget-object v0, p0, Lcom/android/quickstep/FullscreenDrawParams;->taskCornerRadiusProvider:Led/c;",
        ".method public final updateCornerRadius(Landroid/content/Context;)V\n"
        "    .locals 2\n\n"
        "    move-object v1, p1\n\n"
        "    iget-object v0, p0, Lcom/android/quickstep/FullscreenDrawParams;->taskCornerRadiusProvider:Led/c;",
    )
    replace_exact_once(
        fdp_path,
        "    iput p1, p0, Lcom/android/quickstep/FullscreenDrawParams;->windowDividerInnerCornerRadius:F\n\n"
        "    return-void\n"
        ".end method",
        "    iput p1, p0, Lcom/android/quickstep/FullscreenDrawParams;->windowDividerInnerCornerRadius:F\n\n"
        "    invoke-static {p0, v1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onUpdateCornerRadius(Lcom/android/quickstep/FullscreenDrawParams;Landroid/content/Context;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 21. RecentsView.smali -> Stacked-Deck App Switcher curve (entry-dominating register saves)
    rv_path = find_smali(decoded_dir, "com/android/quickstep/views/RecentsView.smali")
    replace_exact_once(
        rv_path,
        ".method public updateCurveProperties()V\n"
        "    .locals 3\n\n"
        "    invoke-virtual {p0}, Lcom/android/launcher3/PagedView;->getPageCount()I",
        ".method public updateCurveProperties()V\n"
        "    .locals 4\n\n"
        "    move-object v3, p0\n\n"
        "    invoke-virtual {p0}, Lcom/android/launcher3/PagedView;->getPageCount()I",
    )
    replace_exact_once(
        rv_path,
        "    sub-float/2addr v1, p0\n\n"
        "    invoke-virtual {v0, v1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V\n\n"
        "    :cond_1\n"
        "    :goto_0\n"
        "    return-void\n"
        ".end method",
        "    sub-float/2addr v1, p0\n\n"
        "    invoke-virtual {v0, v1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V\n\n"
        "    :cond_1\n"
        "    :goto_0\n"
        "    invoke-static {v3}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onRecentsUpdateCurve(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        ".method public setFullscreenProgress(F)V\n"
        "    .locals 6\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mFullscreenProgress:F",
        ".method public setFullscreenProgress(F)V\n"
        "    .locals 7\n\n"
        "    move-object v6, p0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mFullscreenProgress:F",
    )
    replace_exact_once(
        rv_path,
        "    invoke-static/range {v0 .. v5}, Lcom/android/launcher3/Utilities;->mapToRange(FFFFFLandroid/view/animation/Interpolator;)F\n\n"
        "    move-result p1\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V\n\n"
        "    return-void\n"
        ".end method",
        "    invoke-static/range {v0 .. v5}, Lcom/android/launcher3/Utilities;->mapToRange(FFFFFLandroid/view/animation/Interpolator;)F\n\n"
        "    move-result p1\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V\n\n"
        "    invoke-static {v6}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onRecentsUpdateCurve(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        ".method public resetTaskVisuals(Lcom/android/quickstep/views/TaskView;)V\n"
        "    .locals 1\n\n"
        "    .line 76\n"
        "    invoke-virtual {p1}, Lcom/android/quickstep/views/TaskView;->resetViewTransforms()V",
        ".method public resetTaskVisuals(Lcom/android/quickstep/views/TaskView;)V\n"
        "    .locals 2\n\n"
        "    move-object v1, p0\n\n"
        "    .line 76\n"
        "    invoke-virtual {p1}, Lcom/android/quickstep/views/TaskView;->resetViewTransforms()V",
    )
    replace_exact_once(
        rv_path,
        "    invoke-virtual {p1, p0}, Lcom/android/quickstep/views/DesktopTaskView;->setExplodeProgress(F)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual {p1, p0}, Lcom/android/quickstep/views/DesktopTaskView;->setExplodeProgress(F)V\n\n"
        "    :cond_0\n"
        "    invoke-static {v1, p1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onResetTaskVisuals(Lcom/android/quickstep/views/RecentsView;Lcom/android/quickstep/views/TaskView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        "    :cond_3\n"
        "    iget-boolean p1, p0, Lcom/android/quickstep/views/RecentsView;->mFreezeViewVisibility:Z\n\n"
        "    if-nez p1, :cond_4\n\n"
        "    const/4 p1, 0x4\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/views/RecentsView;->setVisibility(I)V\n\n"
        "    :cond_4\n"
        "    :goto_1\n"
        "    invoke-static {v1, v2}, Landroid/os/Trace;->traceEnd(J)V\n\n"
        "    return-void\n"
        ".end method",
        "    :cond_3\n"
        "    iget-boolean p1, p0, Lcom/android/quickstep/views/RecentsView;->mFreezeViewVisibility:Z\n\n"
        "    if-nez p1, :cond_4\n\n"
        "    const/4 p1, 0x4\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/views/RecentsView;->setVisibility(I)V\n\n"
        "    :cond_4\n"
        "    :goto_1\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onRecentsUpdateCurve(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    invoke-static {v1, v2}, Landroid/os/Trace;->traceEnd(J)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        ".method public onDismissAnimationEnds()V\n"
        "    .locals 1\n\n"
        "    invoke-virtual {p0}, Landroid/view/ViewGroup;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object p0\n\n"
        "    const-string v0, \"TAPL_DISMISS_ANIMATION_ENDS\"\n\n"
        "    invoke-static {p0, v0}, Lcom/android/launcher3/compat/AccessibilityManagerCompat;->sendTestProtocolEventToTest(Landroid/content/Context;Ljava/lang/String;)V\n\n"
        "    return-void\n"
        ".end method",
        ".method public onDismissAnimationEnds()V\n"
        "    .locals 2\n\n"
        "    move-object v1, p0\n\n"
        "    invoke-virtual {p0}, Landroid/view/ViewGroup;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object p0\n\n"
        "    const-string v0, \"TAPL_DISMISS_ANIMATION_ENDS\"\n\n"
        "    invoke-static {p0, v0}, Lcom/android/launcher3/compat/AccessibilityManagerCompat;->sendTestProtocolEventToTest(Landroid/content/Context;Ljava/lang/String;)V\n\n"
        "    invoke-static {v1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onDismissAnimationEnds(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        ".method private setTaskModalness(F)V\n"
        "    .locals 2\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mTaskModalness:F",
        ".method private setTaskModalness(F)V\n"
        "    .locals 3\n\n"
        "    move-object v2, p0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mTaskModalness:F",
    )
    replace_exact_once(
        rv_path,
        "    :goto_1\n"
        "    invoke-virtual {v0, v1, p0}, Lcom/android/quickstep/views/OverviewActionsView;->updateHiddenFlags(IZ)V\n\n"
        "    return-void\n"
        ".end method",
        "    :goto_1\n"
        "    invoke-virtual {v0, v1, p0}, Lcom/android/quickstep/views/OverviewActionsView;->updateHiddenFlags(IZ)V\n\n"
        "    invoke-static {v2}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onRecentsUpdateCurve(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        rv_path,
        ".method private setGridProgress(F)V\n"
        "    .locals 2\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mGridProgress:F",
        ".method private setGridProgress(F)V\n"
        "    .locals 3\n\n"
        "    move-object v2, p0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/RecentsView;->mGridProgress:F",
    )
    replace_exact_once(
        rv_path,
        "    iget-object p0, p0, Lcom/android/quickstep/views/RecentsView;->mClearAllButton:Lcom/android/quickstep/views/ClearAllButton;\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/views/ClearAllButton;->setGridProgress(F)V\n\n"
        "    return-void\n"
        ".end method",
        "    iget-object p0, p0, Lcom/android/quickstep/views/RecentsView;->mClearAllButton:Lcom/android/quickstep/views/ClearAllButton;\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/quickstep/views/ClearAllButton;->setGridProgress(F)V\n\n"
        "    invoke-static {v2}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onRecentsUpdateCurve(Lcom/android/quickstep/views/RecentsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 21b. TaskView.smali -> Stacked-Deck visual scale/tx hooks without mutating nonGridTranslationX/mPageScrolls
    tv_path = find_smali(decoded_dir, "com/android/quickstep/views/TaskView.smali")
    replace_exact_once(
        tv_path,
        "    mul-float/2addr v1, v0\n\n"
        "    invoke-virtual {p0, v1}, Landroid/widget/FrameLayout;->setScaleX(F)V",
        "    mul-float/2addr v1, v0\n\n"
        "    invoke-static {p0, v1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->adjustTaskScale(Lcom/android/quickstep/views/TaskView;F)F\n\n"
        "    move-result v1\n\n"
        "    invoke-virtual {p0, v1}, Landroid/widget/FrameLayout;->setScaleX(F)V",
    )
    replace_exact_once(
        tv_path,
        "    iget v0, p0, Lcom/android/quickstep/views/TaskView;->animateToIconTranslationX:F\n\n"
        "    add-float/2addr v1, v0\n\n"
        "    invoke-virtual {p0, v1}, Landroid/widget/FrameLayout;->setTranslationX(F)V",
        "    iget v0, p0, Lcom/android/quickstep/views/TaskView;->animateToIconTranslationX:F\n\n"
        "    add-float/2addr v1, v0\n\n"
        "    invoke-static {p0, v1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->adjustTaskTranslationX(Lcom/android/quickstep/views/TaskView;F)F\n\n"
        "    move-result v1\n\n"
        "    invoke-virtual {p0, v1}, Landroid/widget/FrameLayout;->setTranslationX(F)V",
    )
    replace_exact_once(
        tv_path,
        ".method private final setDismissTranslationX(F)V\n"
        "    .locals 0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/TaskView;->dismissTranslationX:F\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyTranslationX()V\n\n"
        "    return-void\n"
        ".end method",
        ".method private final setDismissTranslationX(F)V\n"
        "    .locals 0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/TaskView;->dismissTranslationX:F\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyTranslationX()V\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onTaskDismissTranslationX(Lcom/android/quickstep/views/TaskView;F)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        tv_path,
        ".method private final setDismissTranslationY(F)V\n"
        "    .locals 0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/TaskView;->dismissTranslationY:F\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyTranslationY()V\n\n"
        "    return-void\n"
        ".end method",
        ".method private final setDismissTranslationY(F)V\n"
        "    .locals 0\n\n"
        "    iput p1, p0, Lcom/android/quickstep/views/TaskView;->dismissTranslationY:F\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyTranslationY()V\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onTaskDismissTranslationY(Lcom/android/quickstep/views/TaskView;F)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        tv_path,
        ".method public final resetViewTransforms()V\n"
        "    .locals 3\n\n"
        "    iget-boolean v0, p0, Lcom/android/quickstep/views/TaskView;->isBeingDraggedForDismissal:Z",
        ".method public final resetViewTransforms()V\n"
        "    .locals 3\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->clearTaskDeckState(Lcom/android/quickstep/views/TaskView;)V\n\n"
        "    iget-boolean v0, p0, Lcom/android/quickstep/views/TaskView;->isBeingDraggedForDismissal:Z",
    )
    replace_exact_once(
        tv_path,
        "    invoke-virtual {p0, v1}, Lcom/android/quickstep/views/TaskView;->setAnimateToIconTranslationX(F)V\n\n"
        "    invoke-virtual {p0, v1}, Lcom/android/quickstep/views/TaskView;->setAnimateToIconTranslationY(F)V\n\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual {p0, v1}, Lcom/android/quickstep/views/TaskView;->setAnimateToIconTranslationX(F)V\n\n"
        "    invoke-virtual {p0, v1}, Lcom/android/quickstep/views/TaskView;->setAnimateToIconTranslationY(F)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onTaskResetViewTransforms(Lcom/android/quickstep/views/TaskView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        tv_path,
        ".method public final setBeingDismissed(Z)V\n"
        "    .locals 0\n\n"
        "    iput-boolean p1, p0, Lcom/android/quickstep/views/TaskView;->isBeingDismissed:Z\n\n"
        "    return-void\n"
        ".end method",
        ".method public final setBeingDismissed(Z)V\n"
        "    .locals 0\n\n"
        "    iput-boolean p1, p0, Lcom/android/quickstep/views/TaskView;->isBeingDismissed:Z\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onTaskBeingDismissedChanged(Lcom/android/quickstep/views/TaskView;Z)V\n\n"
        "    return-void\n"
        ".end method",
    )
    with open(tv_path, "r", encoding="utf-8") as f_tv:
        tv_smali = f_tv.read()
    if "applySamuraiDeckTransform()V" not in tv_smali:
        tv_smali = (
            tv_smali.rstrip()
            + "\n\n.method public final applySamuraiDeckTransform()V\n"
            + "    .locals 0\n\n"
            + "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyTranslationX()V\n\n"
            + "    invoke-direct {p0}, Lcom/android/quickstep/views/TaskView;->applyScale()V\n\n"
            + "    return-void\n"
            + ".end method\n"
        )
        with open(tv_path, "w", encoding="utf-8") as f_tv:
            f_tv.write(tv_smali)

    # 21c. TaskViewDismissTouchController.smali -> Keep dragged card elevated above stacked deck (19.2dp vs 0.1f)
    tvdtc_path = find_smali(
        decoded_dir,
        "com/android/launcher3/uioverrides/touchcontrollers/TaskViewDismissTouchController.smali",
    )
    replace_exact_once(
        tvdtc_path,
        "    iput-object v14, v0, Lcom/android/launcher3/uioverrides/touchcontrollers/TaskViewDismissTouchController;->taskDragDisplacementValue:Lcom/android/mechanics/view/ViewMotionValue;\n\n"
        "    const v0, 0x3dcccccd    # 0.1f\n\n"
        "    invoke-virtual {v1, v0}, Landroid/widget/FrameLayout;->setTranslationZ(F)V\n\n"
        "    return-void",
        "    iput-object v14, v0, Lcom/android/launcher3/uioverrides/touchcontrollers/TaskViewDismissTouchController;->taskDragDisplacementValue:Lcom/android/mechanics/view/ViewMotionValue;\n\n"
        "    invoke-static {v1}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->elevateDraggedTaskZ(Lcom/android/quickstep/views/TaskView;)V\n\n"
        "    return-void",
    )

    # 22. OverviewActionsView.smali -> Hide Android "Screenshot | Select" action bar
    oav_path = find_smali(decoded_dir, "com/android/quickstep/views/OverviewActionsView.smali")
    replace_exact_once(
        oav_path,
        "    invoke-virtual {v0, p0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V\n\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual {v0, p0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onOverviewActionsInflated(Lcom/android/quickstep/views/OverviewActionsView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        oav_path,
        ".method public updateHiddenFlags(IZ)V\n"
        "    .locals 1\n\n"
        "    iget v0, p0, Lcom/android/quickstep/views/OverviewActionsView;->mHiddenFlags:I",
        ".method public updateHiddenFlags(IZ)V\n"
        "    .locals 1\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->onOverviewActionsHiddenFlags(Lcom/android/quickstep/views/OverviewActionsView;)V\n\n"
        "    iget v0, p0, Lcom/android/quickstep/views/OverviewActionsView;->mHiddenFlags:I",
    )
    replace_exact_once(
        oav_path,
        "    if-eqz p1, :cond_2\n\n"
        "    const/4 p1, 0x0\n\n"
        "    goto :goto_2\n\n"
        "    :cond_2\n"
        "    const/high16 p1, 0x3f800000    # 1.0f\n\n"
        "    :goto_2\n"
        "    invoke-virtual {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V",
        "    if-eqz p1, :cond_2\n\n"
        "    const/4 p1, 0x0\n\n"
        "    goto :goto_2\n\n"
        "    :cond_2\n"
        "    const/4 p1, 0x0\n\n"
        "    :goto_2\n"
        "    invoke-virtual {p0, p1}, Lcom/android/launcher3/util/MultiPropertyFactory$MultiProperty;->setValue(F)V",
    )

    # 23. IconAppChipView.smali -> Clean [Icon] App Title header without Material chip or dropdown chevron
    iacv_path = find_smali(decoded_dir, "com/android/quickstep/views/IconAppChipView.smali")
    replace_exact_once(
        iacv_path,
        "    iput-object v0, p0, Lcom/android/quickstep/views/IconAppChipView;->menuAnchorView:Landroid/view/View;\n\n"
        "    return-void\n"
        ".end method",
        "    iput-object v0, p0, Lcom/android/quickstep/views/IconAppChipView;->menuAnchorView:Landroid/view/View;\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->styleIconAppChip(Lcom/android/quickstep/views/IconAppChipView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        iacv_path,
        ".method public final reset()V\n"
        "    .locals 1\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setText(Ljava/lang/CharSequence;)V\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setDrawable(Landroid/graphics/drawable/Drawable;)V\n\n"
        "    const/4 v0, 0x1\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setIsExpandArrowVisible(Z)V\n\n"
        "    return-void\n"
        ".end method",
        ".method public final reset()V\n"
        "    .locals 1\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setText(Ljava/lang/CharSequence;)V\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setDrawable(Landroid/graphics/drawable/Drawable;)V\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->setIsExpandArrowVisible(Z)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->styleIconAppChip(Lcom/android/quickstep/views/IconAppChipView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        iacv_path,
        "    :cond_1\n"
        "    const/4 v0, 0x0\n\n"
        "    :goto_1\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/TextView;->setEllipsize(Landroid/text/TextUtils$TruncateAt;)V",
        "    :cond_1\n"
        "    sget-object v0, Landroid/text/TextUtils$TruncateAt;->END:Landroid/text/TextUtils$TruncateAt;\n\n"
        "    :goto_1\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/TextView;->setEllipsize(Landroid/text/TextUtils$TruncateAt;)V",
    )
    replace_exact_once(
        iacv_path,
        "    invoke-virtual {p0, p1, v0}, Landroid/widget/FrameLayout;->measure(II)V\n\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual {p0, p1, v0}, Landroid/widget/FrameLayout;->measure(II)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->styleIconAppChip(Lcom/android/quickstep/views/IconAppChipView;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        iacv_path,
        ".method private final sendToBack()V\n"
        "    .locals 1\n\n"
        "    iget v0, p0, Lcom/android/quickstep/views/IconAppChipView;->iconMenuElevation:F\n\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/FrameLayout;->setZ(F)V\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-direct {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->updateParentZIndex(F)V\n\n"
        "    return-void\n"
        ".end method",
        ".method private final sendToBack()V\n"
        "    .locals 1\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/FrameLayout;->setZ(F)V\n\n"
        "    invoke-direct {p0, v0}, Lcom/android/quickstep/views/IconAppChipView;->updateParentZIndex(F)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 24. ClearAllButton.smali -> Tag-guarded capsule styling
    cab_path = find_smali(decoded_dir, "com/android/quickstep/views/ClearAllButton.smali")
    replace_exact_once(
        cab_path,
        ".method public final onRecentsViewScroll(IZ)V\n"
        "    .locals 5\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/ClearAllButton;->getRecentsView()Lcom/android/quickstep/views/RecentsView;",
        ".method public final onRecentsViewScroll(IZ)V\n"
        "    .locals 5\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiRecentsController;"
        "->styleClearAllButton(Lcom/android/quickstep/views/ClearAllButton;)V\n\n"
        "    invoke-direct {p0}, Lcom/android/quickstep/views/ClearAllButton;->getRecentsView()Lcom/android/quickstep/views/RecentsView;",
    )

    # 25. Launcher.smali & QuickstepLauncher.smali -> Workspace & Recents Home Bar state sync
    ln_path = find_smali(decoded_dir, "com/android/launcher3/Launcher.smali")
    replace_exact_once(
        ln_path,
        "    invoke-super/range {p0 .. p1}, Lcom/android/launcher3/statemanager/StatefulActivity;->onCreate(Landroid/os/Bundle;)V\n\n"
        "    new-instance v2, Lcom/android/launcher3/util/WallpaperThemeManager;",
        "    invoke-super/range {p0 .. p1}, Lcom/android/launcher3/statemanager/StatefulActivity;->onCreate(Landroid/os/Bundle;)V\n\n"
        "    invoke-static/range {p0 .. p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherCreated(Lcom/android/launcher3/Launcher;)V\n\n"
        "    new-instance v2, Lcom/android/launcher3/util/WallpaperThemeManager;",
    )
    replace_exact_once(
        ln_path,
        ".method public final onStateSetStart(Lcom/android/launcher3/statemanager/BaseState;)V\n"
        "    .locals 4\n\n"
        "    check-cast p1, Lcom/android/launcher3/LauncherState;\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/statemanager/StatefulActivity;->onStateSetStart(Lcom/android/launcher3/statemanager/BaseState;)V",
        ".method public final onStateSetStart(Lcom/android/launcher3/statemanager/BaseState;)V\n"
        "    .locals 4\n\n"
        "    check-cast p1, Lcom/android/launcher3/LauncherState;\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/statemanager/StatefulActivity;->onStateSetStart(Lcom/android/launcher3/statemanager/BaseState;)V\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherStateChanged(Lcom/android/launcher3/Launcher;Lcom/android/launcher3/statemanager/BaseState;)V",
    )

    qsl_path = find_smali(decoded_dir, "com/android/launcher3/uioverrides/QuickstepLauncher.smali")
    replace_exact_once(
        qsl_path,
        ".method public final onStateSetEnd(Lcom/android/launcher3/statemanager/BaseState;)V\n"
        "    .locals 9\n\n"
        "    check-cast p1, Lcom/android/launcher3/LauncherState;\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/Launcher;->mAppWidgetHolder:Lcom/android/launcher3/widget/LauncherWidgetHolder;",
        ".method public final onStateSetEnd(Lcom/android/launcher3/statemanager/BaseState;)V\n"
        "    .locals 9\n\n"
        "    check-cast p1, Lcom/android/launcher3/LauncherState;\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherStateChanged(Lcom/android/launcher3/Launcher;Lcom/android/launcher3/statemanager/BaseState;)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/Launcher;->mAppWidgetHolder:Lcom/android/launcher3/widget/LauncherWidgetHolder;",
    )
    replace_exact_once(
        qsl_path,
        ".method public final onResume()V\n"
        "    .locals 3\n\n"
        "    invoke-super {p0}, Lcom/android/launcher3/Launcher;->onResume()V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->mLauncherUnfoldAnimationController:Lcom/android/quickstep/util/LauncherUnfoldAnimationController;",
        ".method public final onResume()V\n"
        "    .locals 3\n\n"
        "    invoke-super {p0}, Lcom/android/launcher3/Launcher;->onResume()V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherResumed(Lcom/android/launcher3/Launcher;)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->mLauncherUnfoldAnimationController:Lcom/android/quickstep/util/LauncherUnfoldAnimationController;",
    )
    replace_exact_once(
        qsl_path,
        "    :cond_0\n"
        "    invoke-super {p0}, Lcom/android/launcher3/Launcher;->onPause()V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->mSplitSelectStateController:Lcom/android/quickstep/split/SplitSelectStateController;",
        "    :cond_0\n"
        "    invoke-super {p0}, Lcom/android/launcher3/Launcher;->onPause()V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherPaused(Lcom/android/launcher3/Launcher;)V\n\n"
        "    iget-object v0, p0, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->mSplitSelectStateController:Lcom/android/quickstep/split/SplitSelectStateController;",
    )
    replace_exact_once(
        qsl_path,
        ".method public final onWindowFocusChanged(Z)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/Launcher;->onWindowFocusChanged(Z)V\n\n"
        "    invoke-virtual {p0}, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->shouldShowHomeBehindDesktop()Z",
        ".method public final onWindowFocusChanged(Z)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1}, Lcom/android/launcher3/Launcher;->onWindowFocusChanged(Z)V\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/launcher/SamuraiLauncherHooks;"
        "->onLauncherWindowFocusChanged(Lcom/android/launcher3/Launcher;Z)V\n\n"
        "    invoke-virtual {p0}, Lcom/android/launcher3/uioverrides/QuickstepLauncher;->shouldShowHomeBehindDesktop()Z",
    )


def decode_utf16le_string_pool(sp_chunk: bytes):
    ctype, hdr_size, csize = struct.unpack_from("<HHI", sp_chunk, 0)
    scount, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", sp_chunk, 8)
    assert ctype == 0x0001 and style_count == 0 and (flags & 0x100) == 0, "Expected UTF-16LE unstyled StringPool"
    offsets = struct.unpack_from(f"<{scount}I", sp_chunk, hdr_size)
    strings = []
    for off in offsets:
        pos = strings_start + off
        u16_len = struct.unpack_from("<H", sp_chunk, pos)[0]
        if u16_len & 0x8000:
            hi = u16_len & 0x7FFF
            lo = struct.unpack_from("<H", sp_chunk, pos + 2)[0]
            u16_len = (hi << 16) | lo
            pos += 4
        else:
            pos += 2
        raw = sp_chunk[pos : pos + u16_len * 2]
        strings.append(raw.decode("utf-16le"))
    return strings, flags


def encode_utf16le_string_pool(strings, flags: int) -> bytes:
    hdr_size = 28
    scount = len(strings)
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        enc = s.encode("utf-16le")
        u16_len = len(enc) // 2
        assert u16_len < 0x8000
        blob += struct.pack("<H", u16_len) + enc + b"\x00\x00"
    while len(blob) % 4 != 0:
        blob += b"\x00"
    strings_start = hdr_size + scount * 4
    csize = strings_start + len(blob)
    header = struct.pack(
        "<HHIIIIII",
        0x0001,
        hdr_size,
        csize,
        scount,
        0,
        flags & ~0x001,  # clear SORTED_FLAG since we append at end
        strings_start,
        0,
    )
    off_bytes = struct.pack(f"<{scount}I", *offsets)
    return header + off_bytes + bytes(blob)


def patch_binary_axml(axml: bytes) -> bytes:
    magic, total_size = struct.unpack_from("<II", axml, 0)
    assert magic == 0x00080003 and total_size == len(axml)

    # Parse top-level chunks
    chunks = []
    off = 8
    while off < len(axml):
        ctype, hdr_size, csize = struct.unpack_from("<HHI", axml, off)
        chunks.append((ctype, bytearray(axml[off : off + csize])))
        off += csize

    # 1. Append new strings to StringPool preserving all existing string indices 0..N-1!
    assert chunks[0][0] == 0x0001
    strings, sp_flags = decode_utf16le_string_pool(bytes(chunks[0][1]))
    idx_ver_name = len(strings)
    strings.append(TARGET_VERSION_NAME)
    idx_perm_val = len(strings)
    strings.append(ADDED_PERMISSION)
    chunks[0] = (0x0001, bytearray(encode_utf16le_string_pool(strings, sp_flags)))

    # 2. Read Resource ID map (maps string index -> 0x0101xxxx android attr resource IDs)
    assert chunks[1][0] == 0x0180
    rm_hdr = struct.unpack_from("<HHI", chunks[1][1], 0)[1]
    rm_count = (len(chunks[1][1]) - rm_hdr) // 4
    res_ids = struct.unpack_from(f"<{rm_count}I", chunks[1][1], rm_hdr)

    RES_NAME = 0x01010003
    RES_VERSION_CODE = 0x0101021B
    RES_VERSION_NAME = 0x0101021C

    idx_manifest_elem = strings.index("manifest")
    idx_uses_perm_elem = strings.index("uses-permission")

    patched_manifest = False
    first_uses_perm_idx = None

    for ci, (ctype, cbuf) in enumerate(chunks):
        if ctype != 0x0102:  # RES_XML_START_ELEMENT_TYPE
            continue
        ns_idx, elem_name_idx, attr_start, attr_size, attr_count = struct.unpack_from(
            "<iiHHH", cbuf, 16
        )
        if elem_name_idx == idx_manifest_elem and not patched_manifest:
            assert attr_size == 20
            base = 16 + attr_start
            vc_done = False
            vn_done = False
            for ai in range(attr_count):
                aoff = base + ai * 20
                a_ns, a_name, a_raw, tv_size, tv_z, tv_type, tv_data = struct.unpack_from(
                    "<iiiHBBI", cbuf, aoff
                )
                rid = res_ids[a_name] if 0 <= a_name < len(res_ids) else 0
                if rid == RES_VERSION_CODE:
                    struct.pack_into("<iiiHBBI", cbuf, aoff, a_ns, a_name, -1, 8, 0, 0x10, TARGET_VERSION_CODE)
                    vc_done = True
                elif rid == RES_VERSION_NAME:
                    struct.pack_into("<iiiHBBI", cbuf, aoff, a_ns, a_name, idx_ver_name, 8, 0, 0x03, idx_ver_name)
                    vn_done = True
            if not (vc_done and vn_done):
                raise RuntimeError(f"Failed to patch manifest version attrs: vc={vc_done} vn={vn_done}")
            patched_manifest = True
        elif elem_name_idx == idx_uses_perm_elem and first_uses_perm_idx is None:
            first_uses_perm_idx = ci

    if not patched_manifest or first_uses_perm_idx is None:
        raise RuntimeError("Failed to locate <manifest> or <uses-permission> in binary AXML")

    # Clone the first <uses-permission android:name="..."/> START_ELEMENT + END_ELEMENT pair
    start_type, start_orig = chunks[first_uses_perm_idx]
    end_type, end_orig = chunks[first_uses_perm_idx + 1]
    assert start_type == 0x0102 and end_type == 0x0103

    new_start = bytearray(start_orig)
    ns_idx, elem_name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<iiHHH", new_start, 16)
    assert attr_count == 1 and attr_size == 20
    aoff = 16 + attr_start
    a_ns, a_name, a_raw, tv_size, tv_z, tv_type, tv_data = struct.unpack_from("<iiiHBBI", new_start, aoff)
    assert 0 <= a_name < len(res_ids) and res_ids[a_name] == RES_NAME
    struct.pack_into("<iiiHBBI", new_start, aoff, a_ns, a_name, idx_perm_val, 8, 0, 0x03, idx_perm_val)
    new_end = bytearray(end_orig)

    chunks.insert(first_uses_perm_idx, (0x0103, new_end))
    chunks.insert(first_uses_perm_idx, (0x0102, new_start))

    body = b"".join(bytes(c[1]) for c in chunks)
    out = struct.pack("<II", 0x00080003, 8 + len(body)) + body
    return out


def augment_merged_stubs(merged_stubs: str):
    extra_files = {
        "com/android/launcher3/icons/BitmapInfo.java": (
            "package com.android.launcher3.icons;\n"
            "public final class BitmapInfo {\n"
            "    public static final BitmapInfo LOW_RES_INFO = null;\n"
            "    public final boolean isLowRes() { return false; }\n"
            "}\n"
        ),
        "com/android/launcher3/icons/cache/CacheLookupFlag.java": (
            "package com.android.launcher3.icons.cache;\n"
            "public final class CacheLookupFlag {\n"
            "    public static final CacheLookupFlag DEFAULT_LOOKUP_FLAG = null;\n"
            "}\n"
        ),
        "com/android/launcher3/statemanager/BaseState.java": (
            "package com.android.launcher3.statemanager;\n"
            "public interface BaseState {}\n"
        ),
        "com/android/launcher3/LauncherState.java": (
            "package com.android.launcher3;\n"
            "import com.android.launcher3.statemanager.BaseState;\n"
            "public class LauncherState implements BaseState {\n"
            "    public int ordinal;\n"
            "    public boolean isRecentsViewVisible;\n"
            "}\n"
        ),
        "com/android/quickstep/views/IconView.java": (
            "package com.android.quickstep.views;\n"
            "import android.content.Context;\n"
            "import android.view.View;\n"
            "public class IconView extends View {\n"
            "    public IconView(Context c) { super(c); }\n"
            "}\n"
        ),
        "com/android/quickstep/views/IconAppChipView.java": (
            "package com.android.quickstep.views;\n"
            "import android.content.Context;\n"
            "import android.util.AttributeSet;\n"
            "import android.view.View;\n"
            "import android.widget.FrameLayout;\n"
            "import android.widget.ImageView;\n"
            "import android.widget.TextView;\n"
            "public final class IconAppChipView extends FrameLayout {\n"
            "    public IconView iconView;\n"
            "    public TextView appTitle;\n"
            "    public ImageView iconArrowView;\n"
            "    public View menuAnchorView;\n"
            "    public IconAppChipView(Context c, AttributeSet a) { super(c, a); }\n"
            "}\n"
        ),
        "com/android/quickstep/views/ClearAllButton.java": (
            "package com.android.quickstep.views;\n"
            "import android.content.Context;\n"
            "import android.util.AttributeSet;\n"
            "import android.widget.Button;\n"
            "public final class ClearAllButton extends Button {\n"
            "    public ClearAllButton(Context c, AttributeSet a) { super(c, a); }\n"
            "}\n"
        ),
    }
    for rel_path, content in extra_files.items():
        dst = os.path.join(merged_stubs, rel_path)
        if not os.path.isfile(dst):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "w", encoding="utf-8") as f:
                f.write(content)

    injections = {
        "com/android/launcher3/icons/IconCache.java": [
            (
                "getTitleAndIcon(",
                "    public final synchronized void getTitleAndIcon(\n"
                "            com.android.launcher3.model.data.ItemInfoWithIcon info,\n"
                "            com.android.launcher3.icons.cache.CacheLookupFlag lookupFlag) {}\n",
            ),
        ],
        "com/android/launcher3/model/ModelWriter.java": [
            (
                "updateItemInDatabase(",
                "    public final void updateItemInDatabase(com.android.launcher3.model.data.ItemInfo item) {}\n",
            ),
            (
                "deleteItemFromDatabase(com.android.launcher3.model.data.ItemInfo item)",
                "    public final void deleteItemFromDatabase(com.android.launcher3.model.data.ItemInfo item) {}\n",
            ),
            (
                "deleteItemFromDatabase(com.android.launcher3.model.data.ItemInfo item, String reason)",
                "    public final void deleteItemFromDatabase(com.android.launcher3.model.data.ItemInfo item, String reason) {}\n",
            ),
        ],
        "com/android/launcher3/model/ModelTaskController.java": [
            (
                "iconCache",
                "    public com.android.launcher3.icons.IconCache iconCache;\n",
            ),
            (
                "bindUpdatedWorkspaceItems(",
                "    public final void bindUpdatedWorkspaceItems(java.util.Collection<?> updated) {}\n",
            ),
        ],
        "com/android/launcher3/model/data/ItemInfo.java": [
            ("appTitle", "    public CharSequence appTitle;\n"),
            ("contentDescription", "    public CharSequence contentDescription;\n"),
            ("rank", "    public int rank;\n"),
            (
                "copyFrom(",
                "    public void copyFrom(com.android.launcher3.model.data.ItemInfo info) {}\n",
            ),
        ],
        "com/android/launcher3/model/data/ItemInfoWithIcon.java": [
            ("bitmap", "    public com.android.launcher3.icons.BitmapInfo bitmap;\n"),
            ("mProgressLevel", "    public int mProgressLevel;\n"),
            ("runtimeStatusFlags", "    public int runtimeStatusFlags;\n"),
        ],
        "com/android/launcher3/model/data/WorkspaceItemInfo.java": [
            ("intent", "    public android.content.Intent intent;\n"),
            ("mShortcutInfo", "    public android.content.pm.ShortcutInfo mShortcutInfo;\n"),
            ("options", "    public int options;\n"),
            ("personKeys", "    public String[] personKeys;\n"),
            ("status", "    public int status;\n"),
        ],
        "com/android/quickstep/views/TaskView.java": [
            ("setStableAlpha(", "    public void setStableAlpha(float a) {}\n"),
            ("applySamuraiDeckTransform(", "    public final void applySamuraiDeckTransform() {}\n"),
        ],
        "com/android/quickstep/views/RecentsView.java": [
            ("mGridProgress", "    public float mGridProgress;\n"),
            ("mContentAlpha", "    public float mContentAlpha;\n"),
        ],
    }
    for rel_path, members in injections.items():
        target = os.path.join(merged_stubs, rel_path)
        with open(target, "r", encoding="utf-8") as f:
            src = f.read()
        additions = "".join(decl for marker, decl in members if marker not in src)
        if additions:
            rbrace = src.rfind("}")
            if rbrace < 0:
                raise RuntimeError(f"Malformed stub Java file: {rel_path}")
            src = src[:rbrace] + additions + src[rbrace:]
            with open(target, "w", encoding="utf-8") as f:
                f.write(src)


def compile_launcher_mod_dex(out_dex_dir: str, build_dir: str = BUILD_DIR) -> str:
    stubs_dir = os.path.join(A17_ROOT, "launcher-mod/stubs")
    src_dir = os.path.join(A17_ROOT, "launcher-mod/src")
    merged_stubs = os.path.join(build_dir, "merged_stubs")
    cls_stubs = os.path.join(build_dir, "cls_stubs")
    cls_runtime = os.path.join(build_dir, "cls_runtime")
    if os.path.exists(merged_stubs):
        shutil.rmtree(merged_stubs)
    shutil.copytree(stubs_dir, merged_stubs)
    augment_merged_stubs(merged_stubs)
    os.makedirs(cls_stubs, exist_ok=True)
    os.makedirs(cls_runtime, exist_ok=True)
    os.makedirs(out_dex_dir, exist_ok=True)

    stub_files = sorted(glob.glob(os.path.join(merged_stubs, "**/*.java"), recursive=True))
    src_files = sorted(glob.glob(os.path.join(src_dir, "**/*.java"), recursive=True))
    if not stub_files or not src_files:
        raise RuntimeError("Missing stub or runtime Java sources in android17/launcher-mod")

    run([JAVAC, "-source", "17", "-target", "17", "-classpath", PLATFORM_JAR, "-d", cls_stubs] + stub_files)
    run(
        [
            JAVAC,
            "-Xlint:all",
            "-Werror",
            "-source",
            "17",
            "-target",
            "17",
            "-classpath",
            f"{PLATFORM_JAR}:{cls_stubs}",
            "-d",
            cls_runtime,
        ]
        + src_files
    )

    runtime_classes = sorted(glob.glob(os.path.join(cls_runtime, "**/*.class"), recursive=True))
    run(
        [
            D8,
            "--lib",
            PLATFORM_JAR,
            "--classpath",
            cls_stubs,
            "--min-api",
            "33",
            "--output",
            out_dex_dir,
        ]
        + runtime_classes
    )
    dex_path = os.path.join(out_dex_dir, "classes.dex")
    if not os.path.isfile(dex_path):
        raise RuntimeError("d8 failed to produce classes.dex for launcher-mod")
    return dex_path


def main():
    parser = argparse.ArgumentParser(description="Build & sign patched NexusLauncherRelease.apk")
    parser.add_argument("--build-dir", default=BUILD_DIR, help="Scratch directory for smali/dex build")
    parser.add_argument("--out-apk", default=OUT_APK, help="Output signed APK path")
    args = parser.parse_args()

    build_dir = os.path.abspath(args.build_dir)
    out_apk = os.path.abspath(args.out_apk)

    if not os.path.isfile(STOCK_APK):
        raise FileNotFoundError(f"Stock NexusLauncherRelease.apk missing: {STOCK_APK}")
    if not os.path.isfile(APKTOOL_JAR):
        raise FileNotFoundError(f"apktool.jar missing: {APKTOOL_JAR}")
    if not os.path.isfile(KEYSTORE):
        raise FileNotFoundError(f"Release keystore missing: {KEYSTORE}")

    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(build_dir, exist_ok=True)
    os.makedirs(os.path.dirname(out_apk), exist_ok=True)

    decoded_dir = os.path.join(build_dir, "decoded")
    rebuilt_apk = os.path.join(build_dir, "rebuilt_smali.apk")

    print("[NexusLauncher v17.6.5] 1/5 Decoding stock smali via Apktool (-r)...")
    run([JAVA, "-jar", APKTOOL_JAR, "d", "-r", "-f", "-o", decoded_dir, STOCK_APK])

    print("[NexusLauncher v17.6.5] 2/5 Applying surgical smali hooks...")
    apply_smali_hooks(decoded_dir)

    print("[NexusLauncher v17.6.5] 3/5 Re-assembling patched classes.dex & classes2.dex...")
    run([JAVA, "-jar", APKTOOL_JAR, "b", "-o", rebuilt_apk, decoded_dir])

    print("[NexusLauncher v17.6.5] 4/5 Compiling pure-Java Liquid Glass & iOS 26 Icon runtime -> classes3.dex...")
    mod_dex = compile_launcher_mod_dex(os.path.join(build_dir, "mod_dex"), build_dir)

    with zipfile.ZipFile(rebuilt_apk, "r") as zreb:
        patched_dex1 = zreb.read("classes.dex")
        patched_dex2 = zreb.read("classes2.dex")
    with open(mod_dex, "rb") as f:
        patched_dex3 = f.read()

    with zipfile.ZipFile(STOCK_APK, "r") as zstock:
        patched_axml = patch_binary_axml(zstock.read("AndroidManifest.xml"))

    unsigned_apk = os.path.join(build_dir, "unsigned.apk")
    aligned_apk = os.path.join(build_dir, "aligned.apk")

    print("[NexusLauncher v17.6.5] 5/5 Repacking from pristine stock ZIP, page-aligning & signing...")
    with zipfile.ZipFile(STOCK_APK, "r") as zin, zipfile.ZipFile(unsigned_apk, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/"):
                up = name.upper()
                if up.endswith((".SF", ".RSA", ".DSA", ".EC")) or up == "META-INF/MANIFEST.MF":
                    continue
            new_info = zipfile.ZipInfo(filename=name, date_time=(2026, 11, 1, 6, 4, 0))
            new_info.external_attr = info.external_attr
            if name == "AndroidManifest.xml":
                new_info.compress_type = info.compress_type
                zout.writestr(new_info, patched_axml)
            elif name == "classes.dex":
                new_info.compress_type = zipfile.ZIP_STORED
                zout.writestr(new_info, patched_dex1)
            elif name == "classes2.dex":
                new_info.compress_type = zipfile.ZIP_STORED
                zout.writestr(new_info, patched_dex2)
            else:
                new_info.compress_type = info.compress_type
                zout.writestr(new_info, zin.read(name))

        dex3_info = zipfile.ZipInfo(filename="classes3.dex", date_time=(2026, 11, 1, 6, 4, 0))
        dex3_info.compress_type = zipfile.ZIP_STORED
        dex3_info.external_attr = 0o644 << 16
        zout.writestr(dex3_info, patched_dex3)

        bundled_icons = []
        if os.path.isdir(IOS26_ICON_ASSETS_DIR):
            bundled_icons = sorted(glob.glob(os.path.join(IOS26_ICON_ASSETS_DIR, "*.webp")))
        for icon_file in bundled_icons:
            arc_name = "assets/ios26_icons/" + os.path.basename(icon_file)
            ic_info = zipfile.ZipInfo(filename=arc_name, date_time=(2026, 11, 1, 6, 4, 0))
            ic_info.compress_type = zipfile.ZIP_STORED
            ic_info.external_attr = 0o644 << 16
            with open(icon_file, "rb") as f_ic:
                zout.writestr(ic_info, f_ic.read())
        print(f"[NexusLauncher v17.6.5] Bundled {len(bundled_icons)} WebP icon assets into assets/ios26_icons/")

    verify_dex_dir = os.path.join(build_dir, "verify_dex")
    os.makedirs(verify_dex_dir, exist_ok=True)
    print("[NexusLauncher v17.6.5] Running full-APK d8 Dalvik dataflow verification across classes1..3.dex...")
    run(
        [
            D8,
            "--no-desugaring",
            "--min-api",
            "34",
            "--lib",
            PLATFORM_JAR,
            "--output",
            verify_dex_dir,
            unsigned_apk,
        ]
    )

    run([ZIPALIGN, "-f", "-p", "4", unsigned_apk, aligned_apk])
    run(
        [
            APKSIGNER,
            "sign",
            "--ks",
            KEYSTORE,
            "--ks-key-alias",
            "samuraiota",
            "--ks-pass",
            "pass:samuraiota17",
            "--key-pass",
            "pass:samuraiota17",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--out",
            out_apk,
            aligned_apk,
        ]
    )

    run([ZIPALIGN, "-c", "-p", "4", out_apk])
    run([APKSIGNER, "verify", "--verbose", out_apk])
    badging = run([AAPT2, "dump", "badging", out_apk])
    perms = run([AAPT2, "dump", "permissions", out_apk])
    if f"versionCode='{TARGET_VERSION_CODE}'" not in badging or f"versionName='{TARGET_VERSION_NAME}'" not in badging:
        raise RuntimeError(f"Badging verification failed:\n{badging[:400]}")
    if ADDED_PERMISSION not in perms:
        raise RuntimeError("READ_WALLPAPER_INTERNAL missing from patched NexusLauncherRelease.apk permissions")

    size_mb = os.path.getsize(out_apk) / (1024 * 1024)
    print(
        f"[NexusLauncher v17.6.5] SUCCESS -> {out_apk} ({size_mb:.2f} MB, "
        f"versionCode={TARGET_VERSION_CODE}, versionName={TARGET_VERSION_NAME}, classes3.dex={len(patched_dex3)} B)"
    )


if __name__ == "__main__":
    main()
