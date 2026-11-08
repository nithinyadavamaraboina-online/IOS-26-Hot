#!/usr/bin/env python3
"""
Build & Sign Clean-Room 90 Hz iOS 26 Liquid Glass SystemUI (SystemUI.apk v2.0.0):
1. Decodes pristine stock Android 17 (CP3A.260905.010) SystemUI_stock.apk smali via apktool.jar (-r).
2. Applies surgical smali hooks across 9 SystemUI classes:
   - NotificationShadeWindowView.smali (attach IosShadeOverlayHost + dispatch/intercept touch routing)
   - ShadeTouchableRegionManager.smali (dynamic full-screen touchability when CC / Expanded Island open)
   - KeyguardStateControllerImpl.smali (live Keyguard showing/occluded/goingAway/canDismiss tracking)
   - StatusBarStateControllerImpl.smali (90 Hz Doze/AOD interpolation tracking)
   - LockscreenElementFactoryImpl.smali (0-latency live-toggled hiding of stock Clock/Smartspace/Shortcuts while preserving notification & Goodix G3 UDFPS layout bounds)
   - AuthController.smali (exact Goodix G3 optical UDFPS sensor center coordinates)
   - PhoneStatusBarView.smali (centered sminPoly Liquid Glass Dynamic Island capsule & tap handling)
   - VolumeDialogControllerImpl.smali (left-edge expanding refractive iOS 26 Volume Pill HUD)
   - GlobalActionsDialogLite.smali (full-screen frosted iOS 26 Slide-to-Power-Off / Reboot / Recovery menu)
3. Re-assembles patched classes.dex, classes2.dex, classes3.dex via apktool.jar.
4. Compiles pure-Java android17/systemui-mod/src with javac 17 (-Xlint:all -Werror) and bundles
   com.realme.samurai.systemui.* + samurai-glass-core-classes.jar into classes4.dex via d8.
5. Repacks directly from pristine SystemUI_stock.apk (preserving AndroidManifest.xml and uncompressed
   resources.arsc untouched), 4-byte page-aligns via zipalign, signs with samurai-ota-release.jks
   (v1+v2+v3), and verifies alignment, signatures, resources.arsc compression, and dexdump integrity.
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
A17_ROOT = os.path.dirname(SCRIPT_DIR)
REPO_ROOT = os.path.dirname(A17_ROOT)

WORK_ROOT = os.environ.get("WORK_ROOT", "/var/tmp/rmx1931-a17")
STOCK_APK = os.environ.get(
    "STOCK_SYSTEMUI_APK",
    os.path.join(WORK_ROOT, "downloads/pixel_a17_cp3a/SystemUI_stock.apk"),
)
OUT_APK = os.environ.get(
    "OUT_SYSTEMUI_APK",
    os.path.join(WORK_ROOT, "out/apks/SystemUI.apk"),
)
GLASS_CORE_JAR = os.environ.get(
    "GLASS_CORE_JAR",
    os.path.join(WORK_ROOT, "out/framework/samurai-glass-core-classes.jar"),
)
BUILD_DIR = os.path.join(WORK_ROOT, "work/apk-build/SystemUIMod")

JAVA_HOME = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")
JAVA = os.path.join(JAVA_HOME, "bin/java")
JAVAC = os.path.join(JAVA_HOME, "bin/javac")

APKTOOL_JAR = os.path.join(WORK_ROOT, "tools/apktool.jar")
ANDROID_SDK = os.path.join(WORK_ROOT, "tools/android-sdk")
BUILD_TOOLS = os.path.join(ANDROID_SDK, "build-tools/35.0.0")
PLATFORM_JAR = os.path.join(ANDROID_SDK, "platforms/android-35/android.jar")
D8 = os.path.join(BUILD_TOOLS, "d8")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner")
DEXDUMP = os.path.join(BUILD_TOOLS, "dexdump")

KEYSTORE = os.path.join(REPO_ROOT, "work/a17/keys/samurai-ota-release.jks")


def run(cmd, **kwargs):
    res = subprocess.run(cmd, text=True, capture_output=True, **kwargs)
    if res.returncode != 0:
        sys.stderr.write(f"COMMAND FAILED ({res.returncode}): {' '.join(cmd)}\n")
        sys.stderr.write(res.stdout + "\n" + res.stderr + "\n")
        raise RuntimeError(f"Command failed: {cmd[0]}")
    return res.stdout


def find_smali(decoded_dir: str, rel_path: str) -> str:
    for sub in ("smali", "smali_classes2", "smali_classes3"):
        candidate = os.path.join(decoded_dir, sub, rel_path)
        if os.path.isfile(candidate):
            return candidate
    raise FileNotFoundError(f"Smali file not found: {rel_path}")


def replace_exact_once(path: str, old: str, new: str):
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    cnt = src.count(old)
    if cnt != 1:
        raise RuntimeError(
            f"Expected exactly 1 match in {os.path.basename(path)}, found {cnt}:\n{old}"
        )
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))


def apply_smali_hooks(decoded_dir: str):
    # 1. NotificationShadeWindowView.smali -> attach overlay host + dispatch/intercept touch routing
    nswv_path = find_smali(
        decoded_dir, "com/android/systemui/shade/NotificationShadeWindowView.smali"
    )
    replace_exact_once(
        nswv_path,
        ".method public final onAttachedToWindow()V\n"
        "    .locals 1\n\n"
        "    invoke-super {p0}, Lcom/android/systemui/scene/ui/view/WindowRootView;->onAttachedToWindow()V\n\n"
        "    const/4 v0, 0x1\n\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/FrameLayout;->setWillNotDraw(Z)V\n\n"
        "    return-void\n"
        ".end method",
        ".method public final onAttachedToWindow()V\n"
        "    .locals 1\n\n"
        "    invoke-super {p0}, Lcom/android/systemui/scene/ui/view/WindowRootView;->onAttachedToWindow()V\n\n"
        "    const/4 v0, 0x1\n\n"
        "    invoke-virtual {p0, v0}, Landroid/widget/FrameLayout;->setWillNotDraw(Z)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onShadeWindowAttached(Landroid/widget/FrameLayout;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        nswv_path,
        ".method public final dispatchTouchEvent(Landroid/view/MotionEvent;)Z\n"
        "    .locals 16\n\n"
        "    move-object/from16 v0, p0\n\n"
        "    move-object/from16 v1, p1\n\n"
        "    iget-object v2, v0, Lcom/android/systemui/shade/NotificationShadeWindowView;"
        "->mInteractionEventHandler:Lcom/android/systemui/shade/NotificationShadeWindowViewController$1;",
        ".method public final dispatchTouchEvent(Landroid/view/MotionEvent;)Z\n"
        "    .locals 16\n\n"
        "    move-object/from16 v0, p0\n\n"
        "    move-object/from16 v1, p1\n\n"
        "    invoke-static {v0, v1}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onShadeWindowDispatchTouch(Landroid/widget/FrameLayout;Landroid/view/MotionEvent;)Z\n\n"
        "    move-result v2\n\n"
        "    if-eqz v2, :cond_samurai_pass\n\n"
        "    const/4 v2, 0x1\n\n"
        "    return v2\n\n"
        "    :cond_samurai_pass\n"
        "    iget-object v2, v0, Lcom/android/systemui/shade/NotificationShadeWindowView;"
        "->mInteractionEventHandler:Lcom/android/systemui/shade/NotificationShadeWindowViewController$1;",
    )
    replace_exact_once(
        nswv_path,
        ".method public final onInterceptTouchEvent(Landroid/view/MotionEvent;)Z\n"
        "    .locals 8\n\n"
        "    iget-object v0, p0, Lcom/android/systemui/shade/NotificationShadeWindowView;"
        "->mInteractionEventHandler:Lcom/android/systemui/shade/NotificationShadeWindowViewController$1;",
        ".method public final onInterceptTouchEvent(Landroid/view/MotionEvent;)Z\n"
        "    .locals 8\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onShadeWindowInterceptTouch(Landroid/widget/FrameLayout;Landroid/view/MotionEvent;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_samurai_intercept_pass\n\n"
        "    const/4 v0, 0x1\n\n"
        "    return v0\n\n"
        "    :cond_samurai_intercept_pass\n"
        "    iget-object v0, p0, Lcom/android/systemui/shade/NotificationShadeWindowView;"
        "->mInteractionEventHandler:Lcom/android/systemui/shade/NotificationShadeWindowViewController$1;",
    )

    # 2. ShadeTouchableRegionManager.smali -> full-screen touch region while CC / Expanded Island open
    strm_path = find_smali(
        decoded_dir, "com/android/systemui/statusbar/phone/ShadeTouchableRegionManager.smali"
    )
    replace_exact_once(
        strm_path,
        ".method public shouldMakeEntireScreenTouchable()Z\n"
        "    .locals 3\n\n"
        "    iget-boolean v0, p0, Lcom/android/systemui/statusbar/phone/ShadeTouchableRegionManager;->mIsAnyShadeExpanded:Z\n\n"
        "    if-nez v0, :cond_3",
        ".method public shouldMakeEntireScreenTouchable()Z\n"
        "    .locals 3\n\n"
        "    invoke-static {}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->shouldMakeEntireShadeTouchable()Z\n\n"
        "    move-result v0\n\n"
        "    if-nez v0, :cond_3\n\n"
        "    iget-boolean v0, p0, Lcom/android/systemui/statusbar/phone/ShadeTouchableRegionManager;->mIsAnyShadeExpanded:Z\n\n"
        "    if-nez v0, :cond_3",
    )

    # 3. KeyguardStateControllerImpl.smali -> live showing/occluded/goingAway/canDismiss tracking
    ksc_path = find_smali(
        decoded_dir, "com/android/systemui/statusbar/policy/KeyguardStateControllerImpl.smali"
    )
    replace_exact_once(
        ksc_path,
        "    invoke-direct {p1, v0}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
        "    invoke-direct {p1, v0}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onKeyguardControllerUpdated(Ljava/lang/Object;)V\n\n"
        "    :cond_0\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        ksc_path,
        "    const/4 p2, 0x3\n\n"
        "    invoke-direct {p1, p2}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    return-void\n"
        ".end method",
        "    const/4 p2, 0x3\n\n"
        "    invoke-direct {p1, p2}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl$$ExternalSyntheticLambda0;-><init>(I)V\n\n"
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onKeyguardControllerUpdated(Ljava/lang/Object;)V\n\n"
        "    return-void\n"
        ".end method",
    )
    replace_exact_once(
        ksc_path,
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    invoke-static {}, Landroid/os/Trace;->endSection()V\n\n"
        "    :cond_3\n"
        "    invoke-static {}, Landroid/os/Trace;->endSection()V\n\n"
        "    return-void\n"
        ".end method",
        "    invoke-virtual {p0, p1}, Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;->invokeForEachCallback(Ljava/util/function/Consumer;)V\n\n"
        "    invoke-static {}, Landroid/os/Trace;->endSection()V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onKeyguardControllerUpdated(Ljava/lang/Object;)V\n\n"
        "    :cond_3\n"
        "    invoke-static {}, Landroid/os/Trace;->endSection()V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 4. StatusBarStateControllerImpl.smali -> Doze/AOD interpolation tracking
    sbsc_path = find_smali(
        decoded_dir, "com/android/systemui/statusbar/StatusBarStateControllerImpl.smali"
    )
    replace_exact_once(
        sbsc_path,
        "    :cond_0\n"
        "    iput p1, p0, Lcom/android/systemui/statusbar/StatusBarStateControllerImpl;->mDozeAmount:F\n\n"
        "    iget-object v0, p0, Lcom/android/systemui/statusbar/StatusBarStateControllerImpl;->mDozeInterpolator:Landroid/view/animation/Interpolator;",
        "    :cond_0\n"
        "    iput p1, p0, Lcom/android/systemui/statusbar/StatusBarStateControllerImpl;->mDozeAmount:F\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onDozeAmountChanged(Ljava/lang/Object;F)V\n\n"
        "    iget-object v0, p0, Lcom/android/systemui/statusbar/StatusBarStateControllerImpl;->mDozeInterpolator:Landroid/view/animation/Interpolator;",
    )

    # 5. LockscreenElementFactoryImpl.smali -> Live Compose alpha gating for stock Clock/Smartspace/Shortcuts
    lefi_path = find_smali(
        decoded_dir,
        "com/android/systemui/keyguard/ui/composable/elements/LockscreenElementFactoryImpl.smali",
    )
    replace_exact_once(
        lefi_path,
        "    invoke-interface {v0}, Lcom/android/systemui/plugins/keyguard/ui/composable/elements/BaseLockscreenElement;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object v2\n\n"
        "    invoke-virtual {v1, v2}, Landroidx/compose/runtime/ProvidableCompositionLocal;->provides(Ljava/lang/Object;)Landroidx/compose/runtime/ProvidedValue;\n\n"
        "    move-result-object v1\n\n"
        "    new-instance v2, Lcom/android/systemui/keyguard/ui/composable/elements/LockscreenElementFactoryImpl$$ExternalSyntheticLambda2;",
        "    invoke-interface {v0}, Lcom/android/systemui/plugins/keyguard/ui/composable/elements/BaseLockscreenElement;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object v2\n\n"
        "    invoke-virtual {v1, v2}, Landroidx/compose/runtime/ProvidableCompositionLocal;->provides(Ljava/lang/Object;)Landroidx/compose/runtime/ProvidedValue;\n\n"
        "    move-result-object v1\n\n"
        "    invoke-static {}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;->observeComposeEpoch()V\n\n"
        "    iget-object v3, p2, Lcom/android/compose/animation/scene/Key;->debugName:Ljava/lang/String;\n\n"
        "    invoke-static {v2, v3}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->getLockscreenElementAlpha(Landroid/content/Context;Ljava/lang/String;)F\n\n"
        "    move-result v3\n\n"
        "    invoke-static {p3, v3}, Landroidx/compose/ui/draw/AlphaKt;"
        "->alpha(Landroidx/compose/ui/Modifier;F)Landroidx/compose/ui/Modifier;\n\n"
        "    move-result-object p3\n\n"
        "    new-instance v2, Lcom/android/systemui/keyguard/ui/composable/elements/LockscreenElementFactoryImpl$$ExternalSyntheticLambda2;",
    )

    # 6. AuthController.smali -> Goodix G3 optical UDFPS sensor center coordinates
    auth_path = find_smali(
        decoded_dir, "com/android/systemui/biometrics/AuthController.smali"
    )
    replace_exact_once(
        auth_path,
        "    invoke-virtual {p0}, Lcom/android/systemui/biometrics/AuthController;->updateSensorLocations()V\n\n"
        "    iget-object p0, p0, Lcom/android/systemui/biometrics/AuthController;->mContext:Landroid/content/Context;",
        "    invoke-virtual {p0}, Lcom/android/systemui/biometrics/AuthController;->updateSensorLocations()V\n\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onAuthControllerUpdated(Ljava/lang/Object;)V\n\n"
        "    iget-object p0, p0, Lcom/android/systemui/biometrics/AuthController;->mContext:Landroid/content/Context;",
    )
    replace_exact_once(
        auth_path,
        "    invoke-interface {v1, v2}, Lcom/android/systemui/biometrics/AuthController$Callback;"
        "->onUdfpsLocationChanged(Lcom/android/systemui/biometrics/shared/model/UdfpsOverlayParams;)V\n\n"
        "    goto :goto_1\n\n"
        "    :cond_4\n"
        "    return-void\n"
        ".end method",
        "    invoke-interface {v1, v2}, Lcom/android/systemui/biometrics/AuthController$Callback;"
        "->onUdfpsLocationChanged(Lcom/android/systemui/biometrics/shared/model/UdfpsOverlayParams;)V\n\n"
        "    goto :goto_1\n\n"
        "    :cond_4\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onAuthControllerUpdated(Ljava/lang/Object;)V\n\n"
        "    return-void\n"
        ".end method",
    )

    # 7. PhoneStatusBarView.smali -> centered sminPoly Dynamic Island capsule & status bar touch
    psbv_path = find_smali(
        decoded_dir, "com/android/systemui/statusbar/phone/PhoneStatusBarView.smali"
    )
    replace_exact_once(
        psbv_path,
        "    :cond_0\n"
        "    invoke-virtual {p0}, Landroid/widget/FrameLayout;->getViewRootImpl()Landroid/view/ViewRootImpl;\n\n"
        "    move-result-object p0\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Landroid/view/ViewRootImpl;->setForceInvertAllowed(Z)V",
        "    :cond_0\n"
        "    invoke-static {p0}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onStatusBarAttached(Landroid/widget/FrameLayout;)V\n\n"
        "    invoke-virtual {p0}, Landroid/widget/FrameLayout;->getViewRootImpl()Landroid/view/ViewRootImpl;\n\n"
        "    move-result-object p0\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Landroid/view/ViewRootImpl;->setForceInvertAllowed(Z)V",
    )
    replace_exact_once(
        psbv_path,
        "    :cond_0\n"
        "    invoke-super {p0, p1}, Landroid/widget/FrameLayout;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z\n\n"
        "    move-result p0\n\n"
        "    return p0\n"
        ".end method",
        "    :cond_0\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onStatusBarDispatchTouch(Landroid/widget/FrameLayout;Landroid/view/MotionEvent;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_samurai_sb_pass\n\n"
        "    const/4 p0, 0x1\n\n"
        "    return p0\n\n"
        "    :cond_samurai_sb_pass\n"
        "    invoke-super {p0, p1}, Landroid/widget/FrameLayout;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z\n\n"
        "    move-result p0\n\n"
        "    return p0\n"
        ".end method",
    )

    # 8. VolumeDialogControllerImpl.smali -> left-edge refractive iOS 26 Volume Pill HUD
    vdci_path = find_smali(
        decoded_dir, "com/android/systemui/volume/VolumeDialogControllerImpl.smali"
    )
    replace_exact_once(
        vdci_path,
        "    :cond_5\n"
        "    if-eqz v0, :cond_6\n\n"
        "    iget-object p3, p0, Lcom/android/systemui/volume/VolumeDialogControllerImpl;->mKeyguardManager:Landroid/app/KeyguardManager;",
        "    :cond_5\n"
        "    if-eqz v0, :cond_6\n\n"
        "    invoke-static {p0, p1}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onVolumeShowRequested(Ljava/lang/Object;I)Z\n\n"
        "    move-result p3\n\n"
        "    if-nez p3, :cond_6\n\n"
        "    iget-object p3, p0, Lcom/android/systemui/volume/VolumeDialogControllerImpl;->mKeyguardManager:Landroid/app/KeyguardManager;",
    )

    # 9. GlobalActionsDialogLite.smali -> full-screen frosted iOS 26 Slide-to-Power-Off menu
    gadl_path = find_smali(
        decoded_dir, "com/android/systemui/globalactions/GlobalActionsDialogLite.smali"
    )
    replace_exact_once(
        gadl_path,
        ".method public final showOrHideDialog(ZZLcom/android/systemui/animation/Expandable;I)V\n"
        "    .locals 0\n\n"
        "    iput-boolean p1, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;->mKeyguardShowing:Z\n\n"
        "    iput-boolean p2, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;->mDeviceProvisioned:Z\n\n"
        "    iget-object p1, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;"
        "->mDelegate:Lcom/android/systemui/globalactions/GlobalActionsDialogLite$ActionsDialogLiteDelegate;",
        ".method public final showOrHideDialog(ZZLcom/android/systemui/animation/Expandable;I)V\n"
        "    .locals 0\n\n"
        "    iput-boolean p1, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;->mKeyguardShowing:Z\n\n"
        "    iput-boolean p2, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;->mDeviceProvisioned:Z\n\n"
        "    invoke-static {p0, p1, p2}, Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;"
        "->onGlobalActionsShowOrHide(Ljava/lang/Object;ZZ)Z\n\n"
        "    move-result p1\n\n"
        "    if-eqz p1, :cond_samurai_ga_stock\n\n"
        "    return-void\n\n"
        "    :cond_samurai_ga_stock\n"
        "    iget-object p1, p0, Lcom/android/systemui/globalactions/GlobalActionsDialogLite;"
        "->mDelegate:Lcom/android/systemui/globalactions/GlobalActionsDialogLite$ActionsDialogLiteDelegate;",
    )


def compile_systemui_mod_dex(out_dex_dir: str, build_dir: str) -> str:
    src_dir = os.path.join(A17_ROOT, "systemui-mod/src")
    cls_runtime = os.path.join(build_dir, "cls_runtime")
    if os.path.exists(cls_runtime):
        shutil.rmtree(cls_runtime)
    if os.path.exists(out_dex_dir):
        shutil.rmtree(out_dex_dir)
    os.makedirs(cls_runtime, exist_ok=True)
    os.makedirs(out_dex_dir, exist_ok=True)

    src_files = sorted(glob.glob(os.path.join(src_dir, "**/*.java"), recursive=True))
    if not src_files:
        raise RuntimeError(f"No Java sources found in {src_dir}")
    if not os.path.isfile(GLASS_CORE_JAR):
        raise FileNotFoundError(f"samurai-glass-core-classes.jar missing: {GLASS_CORE_JAR}")

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
            f"{PLATFORM_JAR}:{GLASS_CORE_JAR}",
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
            "--min-api",
            "33",
            "--output",
            out_dex_dir,
            GLASS_CORE_JAR,
        ]
        + runtime_classes
    )
    dex_path = os.path.join(out_dex_dir, "classes.dex")
    if not os.path.isfile(dex_path):
        raise RuntimeError("d8 failed to produce classes.dex for systemui-mod")
    if os.path.isfile(os.path.join(out_dex_dir, "classes2.dex")):
        raise RuntimeError("Unexpected classes2.dex split inside systemui-mod DEX")
    return dex_path


def dexdump_class_descriptors(dex_file: str) -> set[str]:
    run([DEXDUMP, "-c", dex_file])
    descriptors: set[str] = set()
    proc = subprocess.Popen(
        [DEXDUMP, dex_file],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        encoding="utf-8",
        errors="replace",
    )
    assert proc.stdout is not None and proc.stderr is not None
    for line in proc.stdout:
        if "Class descriptor" in line:
            q1 = line.find("'")
            q2 = line.rfind("'")
            if 0 <= q1 < q2:
                descriptors.add(line[q1 + 1 : q2])
    err = proc.stderr.read()
    rc = proc.wait()
    if rc != 0:
        raise RuntimeError(f"dexdump -c failed ({rc}) on {dex_file}:\n{err}")
    return descriptors


def verify_apk(out_apk: str, build_dir: str):
    run([ZIPALIGN, "-c", "-p", "4", out_apk])
    run([APKSIGNER, "verify", "--verbose", out_apk])

    verify_dex_dir = os.path.join(build_dir, "verify_dex")
    if os.path.exists(verify_dex_dir):
        shutil.rmtree(verify_dex_dir)
    os.makedirs(verify_dex_dir, exist_ok=True)

    found_descriptors: set[str] = set()
    with zipfile.ZipFile(STOCK_APK, "r") as zstock, zipfile.ZipFile(out_apk, "r") as zf:
        arsc = zf.getinfo("resources.arsc")
        if arsc.compress_type != zipfile.ZIP_STORED:
            raise RuntimeError(f"resources.arsc must be STORED (0), got {arsc.compress_type}")
        if zf.read("resources.arsc") != zstock.read("resources.arsc"):
            raise RuntimeError("resources.arsc differs from pristine SystemUI_stock.apk")
        if zf.read("AndroidManifest.xml") != zstock.read("AndroidManifest.xml"):
            raise RuntimeError("AndroidManifest.xml differs from pristine SystemUI_stock.apk")

        for dex_name in ("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"):
            di = zf.getinfo(dex_name)
            if di.compress_type != zipfile.ZIP_STORED:
                raise RuntimeError(f"{dex_name} must be STORED (0), got {di.compress_type}")
            dex_file = os.path.join(verify_dex_dir, dex_name)
            with open(dex_file, "wb") as df:
                df.write(zf.read(dex_name))
            found_descriptors.update(dexdump_class_descriptors(dex_file))

    shutil.rmtree(verify_dex_dir, ignore_errors=True)
    for expected in (
        "Lcom/realme/samurai/systemui/SamuraiSystemUiHooks;",
        "Lcom/realme/samurai/systemui/IosShadeOverlayHost;",
        "Lcom/realme/samurai/systemui/IosControlCenterView;",
        "Lcom/realme/samurai/systemui/IosLockScreenOverlay;",
        "Lcom/realme/samurai/systemui/DynamicIslandView;",
        "Lcom/realme/samurai/systemui/IosVolumeHudView;",
        "Lcom/realme/samurai/systemui/IosPowerMenuView;",
        "Lcom/realme/samurai/glass/SamuraiAgslEngine;",
        "Lcom/android/systemui/shade/NotificationShadeWindowView;",
        "Lcom/android/systemui/statusbar/phone/ShadeTouchableRegionManager;",
        "Lcom/android/systemui/statusbar/policy/KeyguardStateControllerImpl;",
        "Lcom/android/systemui/statusbar/StatusBarStateControllerImpl;",
        "Lcom/android/systemui/keyguard/ui/composable/elements/LockscreenElementFactoryImpl;",
        "Lcom/android/systemui/biometrics/AuthController;",
        "Lcom/android/systemui/statusbar/phone/PhoneStatusBarView;",
        "Lcom/android/systemui/volume/VolumeDialogControllerImpl;",
        "Lcom/android/systemui/globalactions/GlobalActionsDialogLite;",
    ):
        if expected not in found_descriptors:
            raise RuntimeError(f"Expected class descriptor missing from DEX headers: {expected}")


def main():
    parser = argparse.ArgumentParser(description="Build & sign patched iOS 26 SystemUI.apk")
    parser.add_argument("--build-dir", default=BUILD_DIR, help="Scratch directory for smali/dex build")
    parser.add_argument("--out-apk", default=OUT_APK, help="Output signed APK path")
    args = parser.parse_args()

    build_dir = os.path.abspath(args.build_dir)
    out_apk = os.path.abspath(args.out_apk)

    for required in (STOCK_APK, APKTOOL_JAR, KEYSTORE, GLASS_CORE_JAR, PLATFORM_JAR):
        if not os.path.isfile(required):
            raise FileNotFoundError(f"Required input missing: {required}")

    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(build_dir, exist_ok=True)
    os.makedirs(os.path.dirname(out_apk), exist_ok=True)

    decoded_dir = os.path.join(build_dir, "decoded")
    rebuilt_apk = os.path.join(build_dir, "rebuilt_smali.apk")

    print("[SystemUI v2.0.0] 1/5 Decoding stock smali via Apktool (-r)...")
    run([JAVA, "-jar", APKTOOL_JAR, "d", "-r", "-f", "-o", decoded_dir, STOCK_APK])

    print("[SystemUI v2.0.0] 2/5 Applying surgical smali hooks across 9 SystemUI classes...")
    apply_smali_hooks(decoded_dir)

    print("[SystemUI v2.0.0] 3/5 Re-assembling patched classes.dex, classes2.dex & classes3.dex...")
    run([JAVA, "-jar", APKTOOL_JAR, "b", "-o", rebuilt_apk, decoded_dir])

    print("[SystemUI v2.0.0] 4/5 Compiling pure-Java Liquid Glass SystemUI + glass-core -> classes4.dex...")
    mod_dex = compile_systemui_mod_dex(os.path.join(build_dir, "mod_dex"), build_dir)

    with zipfile.ZipFile(rebuilt_apk, "r") as zreb:
        rebuilt_names = set(zreb.namelist())
        if "classes4.dex" in rebuilt_names:
            raise RuntimeError("Unexpected classes4.dex produced by Apktool smali rebuild")
        patched_dex1 = zreb.read("classes.dex")
        patched_dex2 = zreb.read("classes2.dex")
        patched_dex3 = zreb.read("classes3.dex")
    with open(mod_dex, "rb") as f:
        patched_dex4 = f.read()

    unsigned_apk = os.path.join(build_dir, "unsigned.apk")
    aligned_apk = os.path.join(build_dir, "aligned.apk")

    print("[SystemUI v2.0.0] 5/5 Repacking from pristine stock ZIP, page-aligning, signing & verifying...")
    with zipfile.ZipFile(STOCK_APK, "r") as zin, zipfile.ZipFile(unsigned_apk, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/"):
                up = name.upper()
                if up.endswith((".SF", ".RSA", ".DSA", ".EC")) or up == "META-INF/MANIFEST.MF":
                    continue
            new_info = zipfile.ZipInfo(filename=name, date_time=(2026, 11, 3, 2, 0, 0))
            new_info.external_attr = info.external_attr
            if name == "classes.dex":
                new_info.compress_type = zipfile.ZIP_STORED
                zout.writestr(new_info, patched_dex1)
            elif name == "classes2.dex":
                new_info.compress_type = zipfile.ZIP_STORED
                zout.writestr(new_info, patched_dex2)
            elif name == "classes3.dex":
                new_info.compress_type = zipfile.ZIP_STORED
                zout.writestr(new_info, patched_dex3)
            else:
                new_info.compress_type = info.compress_type
                zout.writestr(new_info, zin.read(name))

        dex4_info = zipfile.ZipInfo(filename="classes4.dex", date_time=(2026, 11, 3, 2, 0, 0))
        dex4_info.compress_type = zipfile.ZIP_STORED
        dex4_info.external_attr = 0o644 << 16
        zout.writestr(dex4_info, patched_dex4)

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

    verify_apk(out_apk, build_dir)

    size_mb = os.path.getsize(out_apk) / (1024 * 1024)
    print(
        f"[SystemUI v2.0.0] SUCCESS -> {out_apk} ({size_mb:.2f} MB, "
        f"classes4.dex={len(patched_dex4)} B)"
    )


if __name__ == "__main__":
    main()
