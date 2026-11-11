#!/usr/bin/env python3
"""
QTI IMS, VoLTE, ViLTE, VoWiFi, SMS-over-IMS, Dual-SIM Provisioning, DPM,
RMX1931 CarrierConfig & Google Dialer Compatibility Injector for Android 17.

Injects into the mounted Android 17 /system root (`sys_sub`):
  1. Re-signed QTI telephony/IMS/DPM system_ext APKs & RMX1931 CarrierConfig RRO:
       - /system/system_ext/priv-app/ims/ims.apk (org.codeaurora.ims + JNI symlinks)
       - /system/system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk
       - /system/system_ext/priv-app/dpmserviceapp/dpmserviceapp.apk
       - /system/system_ext/app/QtiTelephonyService/QtiTelephonyService.apk
       - /system/system_ext/app/ImsRcsService/ImsRcsService.apk
       - /system/product/overlay/CarrierConfigRes.apk (MCC/MNC rules incl. Jio 405854)
     NOTE: qti-telephony-common.jar is intentionally NOT injected because its SDK 36
     QtiPhoneSwitcher overrides PhoneSwitcher.onEvaluate(boolean, String) which was
     refactored in Android 17 telephony-common.jar; AOSP PhoneSwitcher handles DSDS
     data switching cleanly while org.codeaurora.ims + extphonelib + qcrilhook +
     QtiTelephonyService + ImsRcsService handle MMTel/RCS/SMS-over-IP/UICC provisioning.
  2. 28 QTI framework JARs in /system/system_ext/framework/ + 2 in /system/product/framework/.
  3. QTI permissions XMLs, exact A17 0x10 privapp-permissions-qti-ims.xml, hiddenapi &
     power-save sysconfig whitelists, and qti-telephony-shareduid-allowlist.xml.
  4. QTI native libraries in /system/lib64/ & /system/system_ext/lib64/ (with rebuilt
     Android 17 ABI-compatible libgui_shim.so) + /system/system_ext/bin/dpmd + dpmd.rc + dpm.conf.
  5. Google Dialer device support library & feature XML:
       - /system/product/framework/com.google.android.dialer.support.jar
       - /system/product/etc/permissions/com.google.android.dialer.support.xml
     Satisfies GoogleDeviceCompatibilityChecker so Google Phone never shows
     "This device isn't supported and phone calls may not work correctly" after reboot.
  6. SELinux 202504 attribute mappings, plat_sepolicy.cil QTI domain/service/socket/prop
     rules (verified 0 neverallow violations via Android 17 secilc), and system_ext
     mac_permissions / seapp_contexts / file_contexts / service_contexts / property_contexts.
"""
import hashlib
import os
import shutil
import subprocess
import tempfile
import zipfile


QTI_SYSTEM_EXT_JARS = [
    "com.qti.dpmframework.jar",
    "com.qualcomm.qti.imscmservice-V2.0-java.jar",
    "com.qualcomm.qti.imscmservice-V2.1-java.jar",
    "com.qualcomm.qti.imscmservice-V2.2-java.jar",
    "com.qualcomm.qti.uceservice-V2.0-java.jar",
    "com.qualcomm.qti.uceservice-V2.1-java.jar",
    "dpmapi.jar",
    "extphonelib.jar",
    "qcrilhook.jar",
    "qti-telephony-hidl-wrapper.jar",
    "qti-telephony-utils.jar",
    "vendor.qti.ims.callcapability-V1.0-java.jar",
    "vendor.qti.ims.callinfo-V1.0-java.jar",
    "vendor.qti.ims.connection-V1.0-java.jar",
    "vendor.qti.ims.connectionaidlservice-V1-java.jar",
    "vendor.qti.ims.factory-V2.0-java.jar",
    "vendor.qti.ims.factory-V2.1-java.jar",
    "vendor.qti.ims.factory-V2.2-java.jar",
    "vendor.qti.ims.factoryaidlservice-V1-java.jar",
    "vendor.qti.ims.rcsconfig-V1.0-java.jar",
    "vendor.qti.ims.rcssip-V1.0-java.jar",
    "vendor.qti.ims.rcssip-V1.1-java.jar",
    "vendor.qti.ims.rcssip-V1.2-java.jar",
    "vendor.qti.ims.rcssipaidlservice-V1-java.jar",
    "vendor.qti.ims.rcsuce-V1.0-java.jar",
    "vendor.qti.ims.rcsuce-V1.1-java.jar",
    "vendor.qti.ims.rcsuce-V1.2-java.jar",
    "vendor.qti.ims.rcsuceaidlservice-V1-java.jar",
]

QTI_PRODUCT_JARS = [
    "ims-ext-common.jar",
    "qti-telephony-hidl-wrapper-prd.jar",
]

QTI_SYSTEM_EXT_PERM_XMLS = [
    "com.qti.dpmframework.xml",
    "com.qualcomm.qti.imscmservice-V2.0-java.xml",
    "com.qualcomm.qti.imscmservice-V2.1-java.xml",
    "com.qualcomm.qti.imscmservice-V2.2-java.xml",
    "dpmapi.xml",
    "extphonelib.xml",
    "qcrilhook.xml",
    "qti_libpermissions.xml",
    "qti_permissions.xml",
    "qti_telephony_hidl_wrapper.xml",
    "qti_telephony_utils.xml",
    "vendor.qti.ims.rcsservice.xml",
]

QTI_PRODUCT_PERM_XMLS = [
    "ims_ext_common.xml",
    "qti_telephony_hidl_wrapper_prd.xml",
]

# Donor /system/system_ext/lib64/*.so excluding libgui_shim.so (replaced by rebuilt A17 shim)
QTI_SYSTEM_EXT_LIB64_DONOR = [
    "com.qualcomm.qti.dpm.api@1.0.so",
    "lib-imsvideocodec.so",
    "lib-imsvt.so",
    "lib-imsvtextutils.so",
    "lib-imsvtutils.so",
    "libdiag_system.so",
    "libdpmctmgr.so",
    "libdpmfdmgr.so",
    "libdpmframework.so",
    "libdpmtcm.so",
    "libgralloc.system.qti.so",
    "libimscamera_jni.so",
    "libimsmedia_jni.so",
    "libqdMetaData.system.so",
    "libqti_vndfwk_detect.so",
    "vendor.qti.ImsRtpService-V1-ndk.so",
    "vendor.qti.diaghal@1.0.so",
    "vendor.qti.hardware.display.allocator@3.0.so",
    "vendor.qti.hardware.display.mapper@1.0.so",
    "vendor.qti.hardware.display.mapper@1.1.so",
    "vendor.qti.hardware.display.mapper@3.0.so",
    "vendor.qti.hardware.display.mapperextensions@1.0.so",
    "vendor.qti.imsrtpservice@3.0.so",
    "vendor.qti.imsrtpservice@3.1.so",
]

QTI_APKS_TO_RESIGN = [
    # (donor_rel_in_system, target_rel_in_system, cache_name)
    ("system_ext/priv-app/ims/ims.apk", "system_ext/priv-app/ims/ims.apk", "ims.apk"),
    (
        "system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk",
        "system_ext/priv-app/qcrilmsgtunnel/qcrilmsgtunnel.apk",
        "qcrilmsgtunnel.apk",
    ),
    (
        "system_ext/priv-app/dpmserviceapp/dpmserviceapp.apk",
        "system_ext/priv-app/dpmserviceapp/dpmserviceapp.apk",
        "dpmserviceapp.apk",
    ),
    (
        "system_ext/app/QtiTelephonyService/QtiTelephonyService.apk",
        "system_ext/app/QtiTelephonyService/QtiTelephonyService.apk",
        "QtiTelephonyService.apk",
    ),
    (
        "system_ext/app/ImsRcsService/ImsRcsService.apk",
        "system_ext/app/ImsRcsService/ImsRcsService.apk",
        "ImsRcsService.apk",
    ),
    (
        "product/overlay/CarrierConfigRes.apk",
        "product/overlay/CarrierConfigRes.apk",
        "CarrierConfigRes.apk",
    ),
]

PRIVAPP_PERMS_QTI_IMS_XML = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="org.codeaurora.ims">
        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
        <permission name="android.permission.MODIFY_PHONE_STATE"/>
        <permission name="android.permission.READ_PRECISE_PHONE_STATE"/>
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.SUBSTITUTE_NOTIFICATION_APP_NAME"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
    </privapp-permissions>

    <privapp-permissions package="com.qualcomm.qcrilmsgtunnel">
        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
    </privapp-permissions>

    <privapp-permissions package="com.qualcomm.qti.telephonyservice">
        <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
    </privapp-permissions>
</permissions>
"""

SHAREDUID_ALLOWLIST_QTI_XML = """<?xml version="1.0" encoding="utf-8"?>
<config>
    <allow-non-preload-system-shareduids package="com.qualcomm.qcrilmsgtunnel" shareduid="android.uid.qtiphone" />
    <allow-non-preload-system-shareduids package="com.qualcomm.qti.telephonyservice" shareduid="android.uid.qtiphone" />
    <allow-non-preload-system-shareduids package="com.qti.dpmserviceapp" shareduid="android.uid.phone" />
    <allow-non-preload-system-shareduids package="vendor.qti.imsrcs" shareduid="android.uid.phone" />
</config>
"""

GOOGLE_DIALER_SUPPORT_XML = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <library name="com.google.android.dialer.support"
          file="/product/framework/com.google.android.dialer.support.jar" />
    <feature name="com.google.android.apps.dialer.SUPPORTED" />
</permissions>
"""

QTI_SEAPP_CONTEXTS = """user=radio seinfo=platform name=.dataservices domain=vendor_dataservice_app type=radio_data_file
user=_app seinfo=platform name=org.codeaurora.ims isPrivApp=true domain=vendor_qtelephony type=app_data_file levelFrom=all
user=_app seinfo=platform name=com.qualcomm.qti.telephonyservice domain=vendor_qtelephony type=app_data_file levelFrom=all
user=_app seinfo=platform name=com.qti.phone domain=vendor_qtelephony type=app_data_file levelFrom=all
"""

QTI_FILE_CONTEXTS = """/dev/socket/dpmd                                u:object_r:vendor_dpmd_socket:s0
/dev/socket/dpmwrapper                          u:object_r:vendor_dpmwrapper_socket:s0
/dev/socket/tcm                                 u:object_r:vendor_dpmtcm_socket:s0
/dev/socket/tcmd                                u:object_r:vendor_dpmtcm_socket:s0
/(system_ext|system/system_ext)/bin/dpmd        u:object_r:vendor_dpmd_exec:s0
/data/dpm(/.*)?                                 u:object_r:vendor_dpmd_data_file:s0
"""

QTI_SERVICE_CONTEXTS = """cneservice                                                 u:object_r:vendor_cne_service:s0
vendor.imsdcservice                                        u:object_r:vendor_imsdc_service:s0
com.qualcomm.qti.ustaservice.USTAServiceImpl               u:object_r:vendor_usta_app_service:s0
dpmservice                                                 u:object_r:vendor_dpmservice:s0
sms-sec                                                    u:object_r:radio_service:s0
extphone                                                   u:object_r:radio_service:s0
qti.radio.extphone                                         u:object_r:radio_service:s0
vendor.qti.hardware.radio.atcmdfwd.IAtCmdFwd/AtCmdFwdAidl  u:object_r:radio_service:s0
vendor.qti.ImsVtService.IImsVtRequest/ImsVtService         u:object_r:vendor_hal_imsvt_service:s0
"""

QTI_PROPERTY_CONTEXTS = """persist.vendor.dpm.                              u:object_r:vendor_persist_dpm_prop:s0
persist.dpm.feature                              u:object_r:vendor_persist_dpm_prop:s0
persist.vendor.rcs.                              u:object_r:vendor_persist_rcs_prop:s0
persist.vendor.tcmd.                             u:object_r:vendor_persist_tcm_prop:s0
persist.vendor.ntn.                              u:object_r:vendor_persist_ntn_prop:s0
ctl.stop$dpmd                                    u:object_r:ctl_dpmd_prop:s0
ctl.stop$tcmd                                    u:object_r:ctl_tcmd_prop:s0
vendor.sys.ims.                                  u:object_r:vendor_sys_ims_prop:s0
persist.vendor.sys.ims.                          u:object_r:vendor_sys_ims_prop:s0
persist.vendor.sys.qti.telephony.vt_cam_interface u:object_r:vendor_sys_ims_prop:s0
persist.vendor.camera.privapp.list               u:object_r:vendor_persist_camera_prop:s0
vendor.camera.aux.packageexcludelist             u:object_r:vendor_persist_camera_prop:s0
vendor.camera.aux.packagelist                    u:object_r:vendor_persist_camera_prop:s0
vendor.camera.skip_unconfigure.packagelist       u:object_r:vendor_persist_camera_prop:s0
"""

QTI_202504_MAPPING_CIL = """
; RMX1931_QTI_202504_MAPPING
(typeattribute vendor_qtelephony_202504)
(expandtypeattribute (vendor_qtelephony_202504) true)
(typeattributeset vendor_qtelephony_202504 (vendor_qtelephony))
(typeattribute vendor_dataservice_app_202504)
(expandtypeattribute (vendor_dataservice_app_202504) true)
(typeattributeset vendor_dataservice_app_202504 (vendor_dataservice_app))
(typeattribute vendor_dpmd_202504)
(expandtypeattribute (vendor_dpmd_202504) true)
(typeattributeset vendor_dpmd_202504 (vendor_dpmd))
(typeattribute vendor_dpmtcm_socket_202504)
(expandtypeattribute (vendor_dpmtcm_socket_202504) true)
(typeattributeset vendor_dpmtcm_socket_202504 (vendor_dpmtcm_socket))
(typeattribute vendor_persist_dpm_prop_202504)
(expandtypeattribute (vendor_persist_dpm_prop_202504) true)
(typeattributeset vendor_persist_dpm_prop_202504 (vendor_persist_dpm_prop))
(typeattribute vendor_persist_rcs_prop_202504)
(expandtypeattribute (vendor_persist_rcs_prop_202504) true)
(typeattributeset vendor_persist_rcs_prop_202504 (vendor_persist_rcs_prop))
(typeattribute vendor_persist_tcm_prop_202504)
(expandtypeattribute (vendor_persist_tcm_prop_202504) true)
(typeattributeset vendor_persist_tcm_prop_202504 (vendor_persist_tcm_prop))
(typeattribute vendor_persist_ntn_prop_202504)
(expandtypeattribute (vendor_persist_ntn_prop_202504) true)
(typeattributeset vendor_persist_ntn_prop_202504 (vendor_persist_ntn_prop))
(typeattribute vendor_persist_camera_prop_202504)
(expandtypeattribute (vendor_persist_camera_prop_202504) true)
(typeattributeset vendor_persist_camera_prop_202504 (vendor_persist_camera_prop))
(typeattribute vendor_usta_app_service_202504)
(expandtypeattribute (vendor_usta_app_service_202504) true)
(typeattributeset vendor_usta_app_service_202504 (vendor_usta_app_service))
(typeattribute vendor_hal_atfwd_hwservice_202504)
(expandtypeattribute (vendor_hal_atfwd_hwservice_202504) true)
(typeattributeset vendor_hal_atfwd_hwservice_202504 (vendor_hal_atfwd_hwservice))
(typeattribute vendor_hal_telephony_service_202504)
(expandtypeattribute (vendor_hal_telephony_service_202504) true)
(typeattributeset vendor_hal_telephony_service_202504 (vendor_hal_telephony_service))
"""

QTI_PLAT_SEPOLICY_CIL = """
; RMX1931_QTI_IMS_TELEPHONY_SEPOLICY
(type vendor_dataservice_app)
(roletype object_r vendor_dataservice_app)
(type vendor_dataservice_app_userfaultfd)
(roletype object_r vendor_dataservice_app_userfaultfd)
(type vendor_qtelephony)
(roletype object_r vendor_qtelephony)
(type vendor_qtelephony_userfaultfd)
(roletype object_r vendor_qtelephony_userfaultfd)
(type vendor_dpmd)
(roletype object_r vendor_dpmd)
(type vendor_dpmd_exec)
(roletype object_r vendor_dpmd_exec)
(type vendor_dpmd_socket)
(roletype object_r vendor_dpmd_socket)
(type vendor_dpmd_data_file)
(roletype object_r vendor_dpmd_data_file)
(type vendor_dpmtcm_socket)
(roletype object_r vendor_dpmtcm_socket)
(type vendor_dpmwrapper_socket)
(roletype object_r vendor_dpmwrapper_socket)
(type vendor_persist_dpm_prop)
(roletype object_r vendor_persist_dpm_prop)
(type vendor_persist_tcm_prop)
(roletype object_r vendor_persist_tcm_prop)
(type vendor_persist_rcs_prop)
(roletype object_r vendor_persist_rcs_prop)
(type vendor_persist_ntn_prop)
(roletype object_r vendor_persist_ntn_prop)
(type vendor_persist_camera_prop)
(roletype object_r vendor_persist_camera_prop)
(type ctl_dpmd_prop)
(roletype object_r ctl_dpmd_prop)
(type ctl_tcmd_prop)
(roletype object_r ctl_tcmd_prop)
(type vendor_sys_ims_prop)
(roletype object_r vendor_sys_ims_prop)
(type vendor_cne_service)
(roletype object_r vendor_cne_service)
(type vendor_imsdc_service)
(roletype object_r vendor_imsdc_service)
(type vendor_dpmservice)
(roletype object_r vendor_dpmservice)
(type vendor_hal_imsvt_service)
(roletype object_r vendor_hal_imsvt_service)
(type vendor_usta_app_service)
(roletype object_r vendor_usta_app_service)
(type vendor_hal_atfwd_hwservice)
(roletype object_r vendor_hal_atfwd_hwservice)
(type vendor_hal_telephony_service)
(roletype object_r vendor_hal_telephony_service)
(typeattribute vendor_hal_diaghal_client)

(typeattributeset domain (vendor_dataservice_app vendor_dpmd vendor_qtelephony))
(typeattributeset coredomain (vendor_dataservice_app vendor_dpmd vendor_qtelephony))
(typeattributeset appdomain (vendor_dataservice_app vendor_qtelephony))
(typeattributeset netdomain (vendor_dataservice_app vendor_dpmd vendor_qtelephony))
(typeattributeset mlstrustedsubject (vendor_dataservice_app vendor_dpmd))
(typeattributeset halclientdomain (vendor_qtelephony vendor_dataservice_app))
(typeattributeset hal_telephony_client (vendor_qtelephony))
(typeattributeset vendor_hal_diaghal_client (vendor_qtelephony))
(typeattributeset exec_type (vendor_dpmd_exec))
(typeattributeset system_file_type (vendor_dpmd_exec))
(typeattributeset file_type (vendor_dpmd_data_file vendor_dpmd_exec vendor_dpmd_socket vendor_dpmtcm_socket vendor_dpmwrapper_socket))
(typeattributeset data_file_type (vendor_dpmd_data_file))
(typeattributeset core_data_file_type (vendor_dpmd_data_file))
(typeattributeset coredomain_socket (vendor_dpmd_socket vendor_dpmtcm_socket vendor_dpmwrapper_socket))
(typeattributeset mlstrustedobject (vendor_dpmtcm_socket vendor_dpmwrapper_socket))
(typeattributeset property_type (ctl_dpmd_prop ctl_tcmd_prop vendor_persist_camera_prop vendor_persist_dpm_prop vendor_persist_ntn_prop vendor_persist_rcs_prop vendor_persist_tcm_prop vendor_sys_ims_prop))
(typeattributeset system_property_type (ctl_dpmd_prop ctl_tcmd_prop vendor_persist_camera_prop vendor_persist_dpm_prop vendor_persist_ntn_prop vendor_persist_rcs_prop vendor_persist_tcm_prop vendor_sys_ims_prop))
(typeattributeset system_internal_property_type (ctl_dpmd_prop ctl_tcmd_prop vendor_sys_ims_prop))
(typeattributeset system_public_property_type (vendor_persist_camera_prop vendor_persist_dpm_prop vendor_persist_ntn_prop vendor_persist_rcs_prop vendor_persist_tcm_prop))
(typeattributeset service_manager_type (vendor_cne_service vendor_dpmservice vendor_hal_imsvt_service vendor_hal_telephony_service vendor_imsdc_service vendor_usta_app_service))
(typeattributeset hal_service_type (vendor_hal_imsvt_service vendor_hal_telephony_service))
(typeattributeset protected_service (vendor_hal_imsvt_service vendor_hal_telephony_service))
(typeattributeset hwservice_manager_type (vendor_hal_atfwd_hwservice))
(typeattributeset protected_hwservice (vendor_hal_atfwd_hwservice))
(typeattributeset app_api_service (vendor_usta_app_service))
(typeattributeset system_api_service (vendor_usta_app_service))

(allow appdomain vendor_persist_dpm_prop (file (read getattr map open)))
(allow appdomain vendor_persist_rcs_prop (file (read getattr map open)))
(allow appdomain vendor_persist_ntn_prop (file (read getattr map open)))
(allow appdomain vendor_persist_camera_prop (file (read getattr map open)))
(allow zygote vendor_persist_dpm_prop (file (read getattr map open)))

(typetransition vendor_dataservice_app tmpfs file appdomain_tmpfs)
(typetransition vendor_dataservice_app vendor_dataservice_app anon_inode "[userfaultfd]" vendor_dataservice_app_userfaultfd)
(allow vendor_dataservice_app vendor_dataservice_app_userfaultfd (anon_inode (ioctl read create)))
(allow vendor_dataservice_app appdomain_tmpfs (file (ioctl read write getattr map execute)))
(allowx vendor_dataservice_app appdomain_tmpfs (ioctl file ((range 0x7701 0x770b))))
(allow vendor_dataservice_app vendor_cne_service (service_manager (add find)))
(allow vendor_dataservice_app vendor_dpmservice (service_manager (add find)))
(allow vendor_dataservice_app uce_service (service_manager (add find)))
(allow vendor_dataservice_app vendor_imsdc_service (service_manager (add find)))
(allow vendor_dataservice_app app_api_service (service_manager (find)))
(allow vendor_dataservice_app system_api_service (service_manager (find)))
(allow vendor_dataservice_app audioserver_service (service_manager (find)))
(allow vendor_dataservice_app radio_service (service_manager (find)))
(allow vendor_dataservice_app radio_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow vendor_dataservice_app radio_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_dataservice_app radio_data_file (lnk_file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_dataservice_app system_app_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow vendor_dataservice_app system_app_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_dataservice_app hwservicemanager (binder (call transfer)))
(allow hwservicemanager vendor_dataservice_app (binder (call transfer)))
(allow vendor_dataservice_app hwservicemanager_prop (file (read getattr map open)))
(allow vendor_dataservice_app servicemanager (binder (call transfer)))
(allow servicemanager vendor_dataservice_app (binder (call transfer)))
(allow vendor_dataservice_app vendor_dpmd_socket (sock_file (write)))
(allow vendor_dataservice_app vendor_dpmtcm_socket (sock_file (write)))
(allow vendor_dataservice_app vendor_dpmd (unix_stream_socket (connectto)))
(allow vendor_dataservice_app vendor_dpmd_data_file (dir (ioctl read write getattr lock open watch watch_reads add_name remove_name search)))
(allow vendor_dataservice_app vendor_dpmd_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_dataservice_app property_socket (sock_file (write)))
(allow vendor_dataservice_app init (unix_stream_socket (connectto)))
(allow vendor_dataservice_app vendor_persist_ntn_prop (property_service (set)))
(allow vendor_dataservice_app vendor_persist_dpm_prop (property_service (set)))
(allow vendor_dataservice_app vendor_persist_rcs_prop (property_service (set)))

(typetransition vendor_qtelephony tmpfs file appdomain_tmpfs)
(typetransition vendor_qtelephony vendor_qtelephony anon_inode "[userfaultfd]" vendor_qtelephony_userfaultfd)
(allow vendor_qtelephony vendor_qtelephony_userfaultfd (anon_inode (ioctl read create)))
(allow vendor_qtelephony appdomain_tmpfs (file (ioctl read write getattr map execute)))
(allowx vendor_qtelephony appdomain_tmpfs (ioctl file ((range 0x7701 0x770b))))
(allow vendor_qtelephony hwservicemanager (binder (call transfer)))
(allow hwservicemanager vendor_qtelephony (binder (call transfer)))
(allow vendor_qtelephony hwservicemanager_prop (file (read getattr map open)))
(allow vendor_qtelephony servicemanager (binder (call transfer)))
(allow servicemanager vendor_qtelephony (binder (call transfer)))
(allow vendor_qtelephony audioserver_service (service_manager (find)))
(allow vendor_qtelephony cameraserver_service (service_manager (find)))
(allow vendor_qtelephony drmserver_service (service_manager (find)))
(allow vendor_qtelephony mediaserver_service (service_manager (find)))
(allow vendor_qtelephony mediametrics_service (service_manager (find)))
(allow vendor_qtelephony mediaextractor_service (service_manager (find)))
(allow vendor_qtelephony radio_service (service_manager (find)))
(allow vendor_qtelephony system_api_service (service_manager (find)))
(allow vendor_qtelephony app_api_service (service_manager (find)))
(allow vendor_qtelephony vendor_hal_imsvt_service (service_manager (find)))
(allow vendor_qtelephony vendor_dpmtcm_socket (sock_file (write)))
(allow vendor_qtelephony vendor_dpmd (unix_stream_socket (connectto)))
(allow vendor_qtelephony app_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow vendor_qtelephony app_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_qtelephony app_data_file (lnk_file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_qtelephony radio_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow vendor_qtelephony radio_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_qtelephony radio_data_file (lnk_file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_qtelephony vendor_sys_ims_prop (file (read getattr map open)))
(allow vendor_qtelephony ion_device (chr_file (ioctl read getattr open map)))
(allow vendor_qtelephony dmabuf_system_heap_device (chr_file (ioctl read getattr open map)))

(allow init vendor_dpmd_exec (file (read getattr map execute open)))
(allow init vendor_dpmd (process (transition siginh rlimitinh)))
(dontaudit init vendor_dpmd (process (noatsecure)))
(typetransition init vendor_dpmd_exec process vendor_dpmd)
(allow vendor_dpmd vendor_dpmd_exec (file (read getattr map execute open entrypoint execute_no_trans)))
(allow vendor_dpmd system_file (file (getattr map execute execute_no_trans)))
(allow vendor_dpmd shell_exec (file (ioctl read getattr lock map execute open watch watch_reads execute_no_trans)))
(allow vendor_dpmd vendor_dpmd_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow vendor_dpmd vendor_dpmd_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow vendor_dpmd proc_net (dir (ioctl read getattr lock open watch watch_reads search)))
(allow vendor_dpmd proc_net (file (ioctl read write getattr lock map open watch watch_reads)))
(allow vendor_dpmd proc_net (lnk_file (ioctl read getattr lock map open watch watch_reads)))
(allow vendor_dpmd self (capability (setuid net_admin net_raw kill)))
(dontaudit vendor_dpmd self (capability (sys_module)))
(allow vendor_dpmd self (capability2 (wake_alarm block_suspend)))
(allow vendor_dpmd self (cap2_userns (block_suspend)))
(allow vendor_dpmd netutils_wrapper (process (sigkill)))
(allow vendor_dpmd appdomain (dir (ioctl read getattr lock open watch watch_reads search)))
(allow vendor_dpmd appdomain (file (ioctl read getattr lock map open watch watch_reads)))
(allow vendor_dpmd appdomain (lnk_file (ioctl read getattr lock map open watch watch_reads)))
(allow vendor_dpmd sysfs_wake_lock (file (ioctl read write getattr lock append map open watch watch_reads)))
(allow vendor_dpmd system_suspend_server (binder (call transfer)))
(allow system_suspend_server vendor_dpmd (binder (transfer)))
(allow vendor_dpmd system_suspend_server (fd (use)))
(allow vendor_dpmd system_suspend_hwservice (hwservice_manager (find)))
(allow vendor_dpmd hidl_manager_hwservice (hwservice_manager (find)))
(allow vendor_dpmd hal_system_suspend_service (service_manager (find)))
(allow vendor_dpmd hwservicemanager (binder (call transfer)))
(allow hwservicemanager vendor_dpmd (binder (call transfer)))
(allow vendor_dpmd hwservicemanager_prop (file (read getattr map open)))
(allow vendor_dpmd servicemanager (binder (call transfer)))
(allow servicemanager vendor_dpmd (binder (call transfer)))
(allow vendor_dpmd property_socket (sock_file (write)))
(allow vendor_dpmd init (unix_stream_socket (connectto)))
(allow vendor_dpmd vendor_persist_dpm_prop (property_service (set)))
(allow vendor_dpmd vendor_persist_dpm_prop (file (read getattr map open)))
(allow vendor_dpmd ctl_dpmd_prop (property_service (set)))
(allow vendor_dpmd ctl_dpmd_prop (file (read getattr map open)))
(allow vendor_dpmd self (socket (read write create getattr setattr lock append map bind connect getopt setopt shutdown)))
(allow vendor_dpmd self (netlink_socket (read write create getattr setattr lock append map bind connect getopt setopt shutdown)))
(allow vendor_dpmd self (netlink_generic_socket (read write create getattr setattr lock append map bind connect getopt setopt shutdown)))
(allow vendor_dpmd appdomain (fd (use)))
(allow vendor_dpmd appdomain (tcp_socket (ioctl read write getattr setattr lock append map bind getopt setopt shutdown)))
(allow vendor_dpmd system_server (fd (use)))
(allow vendor_dpmd system_server (tcp_socket (ioctl read write getattr setattr lock append map bind getopt setopt shutdown)))

(allow platform_app vendor_dpmtcm_socket (sock_file (write)))
(allow platform_app vendor_dpmd_socket (sock_file (write)))
(allow platform_app vendor_dpmd (unix_stream_socket (connectto)))
(allow platform_app vendor_cne_service (service_manager (find)))
(allow platform_app vendor_dpmservice (service_manager (find)))
(allow platform_app_36 vendor_dpmtcm_socket (sock_file (write)))
(allow platform_app_36 vendor_dpmd_socket (sock_file (write)))
(allow platform_app_36 vendor_dpmd (unix_stream_socket (connectto)))
(allow platform_app_36 vendor_cne_service (service_manager (find)))
(allow platform_app_36 vendor_dpmservice (service_manager (find)))
(allow priv_app vendor_dpmtcm_socket (sock_file (write)))
(allow priv_app vendor_dpmd (unix_stream_socket (connectto)))
(allow priv_app_36 vendor_dpmtcm_socket (sock_file (write)))
(allow priv_app_36 vendor_dpmd (unix_stream_socket (connectto)))
(allow system_app vendor_dpmtcm_socket (sock_file (write)))
(allow system_app vendor_dpmd (unix_stream_socket (connectto)))
(allow radio vendor_dpmtcm_socket (sock_file (write)))
(allow radio vendor_dpmd_socket (sock_file (write)))
(allow radio vendor_dpmd (unix_stream_socket (connectto)))
(allow radio vendor_cne_service (service_manager (find)))
(allow radio vendor_dpmservice (service_manager (find)))
(allow radio uce_service (service_manager (add find)))
(allow radio vendor_imsdc_service (service_manager (find)))
(allow mediaprovider vendor_dpmtcm_socket (sock_file (write)))
(allow mediaprovider vendor_dpmd (unix_stream_socket (connectto)))
(allow mediaprovider_app vendor_dpmtcm_socket (sock_file (write)))
(allow mediaprovider_app vendor_dpmd (unix_stream_socket (connectto)))
(allow untrusted_app_all vendor_dpmtcm_socket (sock_file (write)))
(allow untrusted_app_all vendor_dpmd (unix_stream_socket (connectto)))
(allow system_server vendor_dpmd_socket (sock_file (write)))
(allow system_server vendor_dpmtcm_socket (sock_file (write)))
(allow system_server vendor_dpmd (unix_stream_socket (connectto)))
(allow system_server vendor_dpmd_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))
(allow system_server vendor_dpmd_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))
(allow system_server vendor_persist_dpm_prop (file (read getattr map open)))
(allow system_server vendor_persist_rcs_prop (file (read getattr map open)))
(allow system_server vendor_persist_ntn_prop (file (read getattr map open)))
"""

QTI_SYSTEM_BUILD_PROPS = {
    # Multi-SIM DSDS + QTI RIL / IMS / VoLTE / VoWiFi / RCS
    "persist.radio.multisim.config": "dsds",
    "persist.dbg.ims_volte_enable": "1",
    "persist.dbg.volte_avail_ovr": "1",
    "persist.dbg.vt_avail_ovr": "1",
    "persist.dbg.wfc_avail_ovr": "1",
    "persist.rcs.supported": "1",
    "persist.data.iwlan.enable": "true",
    "persist.data.iwlan.rekey": "4294967295",
    "persist.data.df.iwlan_mux": "9",
    "persist.radio.hvolte.enable": "1",
    "persist.radio.custom_exp_ecc": "1",
    "persist.vendor.radio.atfwd.start": "true",
    "persist.vendor.radio.data_con_rprt": "1",
    "persist.vendor.radio.lte_vrte_ltd": "1",
    "persist.vendor.radio.cs_srv_type": "1",
    "persist.vendor.radio.process_sups_ind": "1",
    "persist.vendor.radio.rat_on": "combine",
    "ril.subscription.types": "NV,RUIM",
    "ro.telephony.default_network": "22,22",
    "telephony.lteOnCdmaDevice": "1",
}

QTI_SYSTEM_EXT_BUILD_PROPS = {
    "persist.vendor.dpm.feature": "1",
    "persist.vendor.dpm.loglevel": "0",
    "persist.vendor.dpm.nsrm.bkg.evt": "3955",
}


def _set_selinux(path: str, context: str) -> None:
    subprocess.run(
        ["setfattr", "-h", "-n", "security.selinux", "-v", context, path],
        check=True,
    )


def _get_selinux(path: str, default: str = "u:object_r:system_file:s0") -> str:
    r = subprocess.run(
        ["getfattr", "-h", "--only-values", "-n", "security.selinux", path],
        capture_output=True,
    )
    if r.returncode == 0 and r.stdout:
        val = r.stdout.decode("utf-8", errors="ignore").strip("\x00\r\n ")
        if val:
            return val
    return default


def _ensure_tree(root_base: str, target_dir: str, dir_ctx: str = "u:object_r:system_file:s0") -> None:
    rel = os.path.relpath(target_dir, root_base)
    if rel in (".", ""):
        return
    cur = root_base
    for part in rel.split(os.sep):
        cur = os.path.join(cur, part)
        created = not os.path.exists(cur)
        if created:
            os.makedirs(cur, mode=0o755, exist_ok=True)
            os.chmod(cur, 0o755)
            os.chown(cur, 0, 0)
            _set_selinux(cur, dir_ctx)


def _install_file(
    sys_sub: str,
    src: str,
    dst_rel: str,
    mode: int = 0o644,
    ctx: str = "u:object_r:system_file:s0",
    dir_ctx: str = "u:object_r:system_file:s0",
) -> None:
    dst = os.path.join(sys_sub, dst_rel)
    _ensure_tree(sys_sub, os.path.dirname(dst), dir_ctx=dir_ctx)
    if os.path.lexists(dst):
        os.unlink(dst)
    shutil.copyfile(src, dst)
    os.chmod(dst, mode)
    os.chown(dst, 0, 0)
    _set_selinux(dst, ctx)


def _install_bytes(
    sys_sub: str,
    data: bytes,
    dst_rel: str,
    mode: int = 0o644,
    ctx: str = "u:object_r:system_file:s0",
    dir_ctx: str = "u:object_r:system_file:s0",
) -> None:
    dst = os.path.join(sys_sub, dst_rel)
    _ensure_tree(sys_sub, os.path.dirname(dst), dir_ctx=dir_ctx)
    if os.path.lexists(dst):
        os.unlink(dst)
    with open(dst, "wb") as f:
        f.write(data)
    os.chmod(dst, mode)
    os.chown(dst, 0, 0)
    _set_selinux(dst, ctx)


def _install_symlink(
    sys_sub: str,
    link_target: str,
    dst_rel: str,
    ctx: str = "u:object_r:system_file:s0",
    dir_ctx: str = "u:object_r:system_file:s0",
) -> None:
    dst = os.path.join(sys_sub, dst_rel)
    _ensure_tree(sys_sub, os.path.dirname(dst), dir_ctx=dir_ctx)
    if os.path.lexists(dst):
        os.unlink(dst)
    os.symlink(link_target, dst)
    subprocess.run(["chown", "-h", "0:0", dst], check=True)
    _set_selinux(dst, ctx)


def _strip_v1_sig_from_apk(src_apk: str, dst_apk: str) -> None:
    with zipfile.ZipFile(src_apk, "r") as zin, zipfile.ZipFile(dst_apk, "w") as zout:
        for info in zin.infolist():
            fn_up = info.filename.upper()
            if fn_up.startswith("META-INF/") and (
                fn_up.endswith(".SF")
                or fn_up.endswith(".RSA")
                or fn_up.endswith(".DSA")
                or fn_up.endswith(".EC")
                or fn_up == "META-INF/MANIFEST.MF"
            ):
                continue
            data = zin.read(info.filename)
            new_info = zipfile.ZipInfo(filename=info.filename, date_time=(2009, 1, 1, 0, 0, 0))
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            zout.writestr(new_info, data)


def _resolve_keystore_and_tools(work_root: str, a17_dir: str) -> tuple[str, str, str, str, str]:
    ks_candidates = [
        os.path.join(work_root, "keys", "samurai-ota-release.jks"),
        os.path.abspath(os.path.join(a17_dir, "..", "work", "a17", "keys", "samurai-ota-release.jks")),
    ]
    ks_path = next((p for p in ks_candidates if os.path.isfile(p)), "")
    if not ks_path:
        raise RuntimeError(f"Missing samurai-ota-release.jks in {ks_candidates}")

    sdk_bt = os.path.join(work_root, "tools", "android-sdk", "build-tools", "35.0.0")
    zipalign = os.path.join(sdk_bt, "zipalign")
    apksigner = os.path.join(sdk_bt, "apksigner")
    if not (os.path.isfile(zipalign) and os.path.isfile(apksigner)):
        raise RuntimeError(f"Missing zipalign/apksigner under {sdk_bt}")

    r = subprocess.run(
        [
            "keytool",
            "-exportcert",
            "-keystore",
            ks_path,
            "-alias",
            "samuraiota",
            "-storepass",
            "samuraiota17",
        ],
        capture_output=True,
        check=True,
    )
    cert_hex = r.stdout.hex()
    if len(cert_hex) < 512:
        raise RuntimeError(f"Unexpected short X.509 cert hex from {ks_path}: len={len(cert_hex)}")
    cert_sha256 = hashlib.sha256(r.stdout).hexdigest()
    return ks_path, zipalign, apksigner, cert_hex, cert_sha256


def _ensure_donor_system_image(work_root: str, a17_dir: str) -> str:
    donor_img = os.path.join(work_root, "work", "donor_s", "system.raw.img")
    if os.path.isfile(donor_img):
        return donor_img

    donor_zip = os.path.join(work_root, "downloads", "samurai-donor-a16.zip")
    if not os.path.isfile(donor_zip):
        raise RuntimeError(
            f"Missing donor image {donor_img} and donor archive {donor_zip} for QTI IMS injection"
        )

    out_dir = os.path.dirname(donor_img)
    os.makedirs(out_dir, exist_ok=True)
    sdat2img = os.path.join(a17_dir, "scripts", "sdat2img.py")
    print(f"    Extracting donor system.raw.img from {donor_zip} -> {donor_img} ...")
    with tempfile.TemporaryDirectory(dir=out_dir) as tmpd:
        with zipfile.ZipFile(donor_zip, "r") as zf:
            zf.extract("system.transfer.list", tmpd)
            zf.extract("system.new.dat.br", tmpd)
        dat_path = os.path.join(tmpd, "system.new.dat")
        subprocess.run(
            ["brotli", "-d", os.path.join(tmpd, "system.new.dat.br"), "-o", dat_path],
            check=True,
        )
        subprocess.run(
            ["python3", sdat2img, os.path.join(tmpd, "system.transfer.list"), dat_path, donor_img],
            check=True,
        )
    return donor_img


def _prepare_resigned_apks(
    donor_sys: str,
    work_root: str,
    ks_path: str,
    zipalign: str,
    apksigner: str,
    cert_sha256: str,
) -> dict[str, str]:
    cache_dir = os.path.join(work_root, "work", "qti_ims_stage", cert_sha256[:16])
    os.makedirs(cache_dir, exist_ok=True)
    staged: dict[str, str] = {}

    for donor_rel, _target_rel, cache_name in QTI_APKS_TO_RESIGN:
        src_apk = os.path.join(donor_sys, donor_rel)
        if not os.path.isfile(src_apk):
            raise RuntimeError(f"Missing donor QTI APK: {src_apk}")
        src_sha = hashlib.sha256(open(src_apk, "rb").read()).hexdigest()[:12]
        out_apk = os.path.join(cache_dir, f"{src_sha}_{cache_name}")
        if not os.path.isfile(out_apk):
            with tempfile.TemporaryDirectory() as tmpd:
                unsigned_apk = os.path.join(tmpd, "unsigned.apk")
                aligned_apk = os.path.join(tmpd, "aligned.apk")
                _strip_v1_sig_from_apk(src_apk, unsigned_apk)
                subprocess.run([zipalign, "-f", "-p", "4", unsigned_apk, aligned_apk], check=True)
                subprocess.run(
                    [
                        apksigner,
                        "sign",
                        "--ks",
                        ks_path,
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
                    ],
                    check=True,
                )
        staged[cache_name] = out_apk
    return staged


def _build_mac_permissions_xml(cert_hex: str) -> bytes:
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<policy>
    <signer signature="{cert_hex}">
        <package name="org.codeaurora.ims">
            <seinfo value="platform" />
        </package>
        <package name="com.qualcomm.qcrilmsgtunnel">
            <seinfo value="platform" />
        </package>
        <package name="com.qti.dpmserviceapp">
            <seinfo value="platform" />
        </package>
        <package name="com.qualcomm.qti.telephonyservice">
            <seinfo value="platform" />
        </package>
        <package name="vendor.qti.imsrcs">
            <seinfo value="platform" />
        </package>
    </signer>
</policy>
"""
    return xml.encode("utf-8")


def _append_unique_lines(existing: str, block: str) -> str:
    existing_lines = {ln.strip() for ln in existing.splitlines() if ln.strip() and not ln.strip().startswith("#")}
    to_add = []
    for ln in block.splitlines():
        s = ln.strip()
        if not s:
            continue
        if s not in existing_lines:
            to_add.append(ln)
            existing_lines.add(s)
    if not to_add:
        return existing if existing.endswith("\n") else (existing + "\n")
    base = existing.rstrip("\n")
    return (base + "\n" + "\n".join(to_add) + "\n") if base else ("\n".join(to_add) + "\n")


def inject_qti_ims_and_dialer_support(
    sys_sub: str,
    a17_dir: str,
    out_bin_dir: str,
    out_fwk_dir: str,
    work_root: str = "/var/tmp/rmx1931-a17",
) -> None:
    libgui_shim_src = os.path.join(out_bin_dir, "libgui_shim.so")
    dialer_jar_src = os.path.join(out_fwk_dir, "com.google.android.dialer.support.jar")
    if not os.path.isfile(libgui_shim_src):
        raise RuntimeError(f"Missing built A17 libgui_shim.so: {libgui_shim_src} (run build_apps.sh)")
    if not os.path.isfile(dialer_jar_src):
        raise RuntimeError(
            f"Missing built com.google.android.dialer.support.jar: {dialer_jar_src} (run build_apps.sh)"
        )

    ks_path, zipalign, apksigner, cert_hex, cert_sha256 = _resolve_keystore_and_tools(work_root, a17_dir)

    # Locate or temporarily mount donor /system read-only
    mounted_tmp = None
    donor_sys = ""
    try:
        for cand in [
            os.environ.get("DONOR_SYSTEM_ROOT", ""),
            os.path.join(work_root, "mnt_s_ro", "system"),
        ]:
            if cand and os.path.isfile(os.path.join(cand, "system_ext/priv-app/ims/ims.apk")):
                donor_sys = cand
                break

        if not donor_sys:
            donor_img = _ensure_donor_system_image(work_root, a17_dir)
            mounted_tmp = tempfile.mkdtemp(prefix="rmx1931_donor_qti_")
            subprocess.run(["mount", "-o", "ro,loop", donor_img, mounted_tmp], check=True)
            donor_sys = os.path.join(mounted_tmp, "system")

        # Defensive cleanup in case qti-telephony-common.jar was ever staged on a prior image
        stale_qti_tel = os.path.join(sys_sub, "system_ext/framework/qti-telephony-common.jar")
        if os.path.lexists(stale_qti_tel):
            os.unlink(stale_qti_tel)

        # 1. Re-sign and install the 5 QTI APKs + RMX1931 CarrierConfigRes.apk
        resigned = _prepare_resigned_apks(donor_sys, work_root, ks_path, zipalign, apksigner, cert_sha256)
        for _donor_rel, target_rel, cache_name in QTI_APKS_TO_RESIGN:
            apk_dir = os.path.dirname(os.path.join(sys_sub, target_rel))
            oat_dir = os.path.join(apk_dir, "oat")
            if os.path.isdir(oat_dir):
                shutil.rmtree(oat_dir)
            _install_file(sys_sub, resigned[cache_name], target_rel, 0o644, "u:object_r:system_file:s0")

        # Create JNI symlinks inside /system/system_ext/priv-app/ims/lib/arm64/
        _install_symlink(
            sys_sub,
            "/system_ext/lib64/libimscamera_jni.so",
            "system_ext/priv-app/ims/lib/arm64/libimscamera_jni.so",
            "u:object_r:system_file:s0",
        )
        _install_symlink(
            sys_sub,
            "/system_ext/lib64/libimsmedia_jni.so",
            "system_ext/priv-app/ims/lib/arm64/libimsmedia_jni.so",
            "u:object_r:system_file:s0",
        )

        # 2. Install QTI Java shared libraries (28 in system_ext/framework, 2 in product/framework)
        for jar in QTI_SYSTEM_EXT_JARS:
            _install_file(
                sys_sub,
                os.path.join(donor_sys, "system_ext/framework", jar),
                f"system_ext/framework/{jar}",
                0o644,
                "u:object_r:system_file:s0",
            )
        for jar in QTI_PRODUCT_JARS:
            _install_file(
                sys_sub,
                os.path.join(donor_sys, "product/framework", jar),
                f"product/framework/{jar}",
                0o644,
                "u:object_r:system_file:s0",
            )

        # 3. Install permissions, privapp-permissions, sysconfig & sharedUID allowlist XMLs
        for xml_fn in QTI_SYSTEM_EXT_PERM_XMLS:
            _install_file(
                sys_sub,
                os.path.join(donor_sys, "system_ext/etc/permissions", xml_fn),
                f"system_ext/etc/permissions/{xml_fn}",
                0o644,
                "u:object_r:system_file:s0",
            )
        for xml_fn in QTI_PRODUCT_PERM_XMLS:
            _install_file(
                sys_sub,
                os.path.join(donor_sys, "product/etc/permissions", xml_fn),
                f"product/etc/permissions/{xml_fn}",
                0o644,
                "u:object_r:system_file:s0",
            )

        _install_bytes(
            sys_sub,
            PRIVAPP_PERMS_QTI_IMS_XML.encode("utf-8"),
            "system_ext/etc/permissions/privapp-permissions-qti-ims.xml",
            0o644,
            "u:object_r:system_file:s0",
        )
        _install_file(
            sys_sub,
            os.path.join(donor_sys, "etc/sysconfig/qti_whitelist.xml"),
            "etc/sysconfig/qti_whitelist.xml",
            0o644,
            "u:object_r:system_file:s0",
        )
        _install_file(
            sys_sub,
            os.path.join(donor_sys, "system_ext/etc/sysconfig/qti_whitelist_system_ext.xml"),
            "system_ext/etc/sysconfig/qti_whitelist_system_ext.xml",
            0o644,
            "u:object_r:system_file:s0",
        )
        _install_bytes(
            sys_sub,
            SHAREDUID_ALLOWLIST_QTI_XML.encode("utf-8"),
            "system_ext/etc/sysconfig/qti-telephony-shareduid-allowlist.xml",
            0o644,
            "u:object_r:system_file:s0",
        )

        # 4. Install Google Dialer support JAR & feature XML
        _install_file(
            sys_sub,
            dialer_jar_src,
            "product/framework/com.google.android.dialer.support.jar",
            0o644,
            "u:object_r:system_file:s0",
        )
        _install_bytes(
            sys_sub,
            GOOGLE_DIALER_SUPPORT_XML.encode("utf-8"),
            "product/etc/permissions/com.google.android.dialer.support.xml",
            0o644,
            "u:object_r:system_file:s0",
        )

        # 5. Install QTI native libraries, rebuilt A17 libgui_shim.so, and dpmd daemon
        _install_file(
            sys_sub,
            os.path.join(donor_sys, "lib64/vendor.qti.diaghal-V1-ndk.so"),
            "lib64/vendor.qti.diaghal-V1-ndk.so",
            0o644,
            "u:object_r:system_lib_file:s0",
        )
        for so_fn in QTI_SYSTEM_EXT_LIB64_DONOR:
            _install_file(
                sys_sub,
                os.path.join(donor_sys, "system_ext/lib64", so_fn),
                f"system_ext/lib64/{so_fn}",
                0o644,
                "u:object_r:system_lib_file:s0",
            )
        _install_file(
            sys_sub,
            libgui_shim_src,
            "system_ext/lib64/libgui_shim.so",
            0o644,
            "u:object_r:system_lib_file:s0",
        )

        _install_file(
            sys_sub,
            os.path.join(donor_sys, "system_ext/bin/dpmd"),
            "system_ext/bin/dpmd",
            0o755,
            "u:object_r:vendor_dpmd_exec:s0",
        )
        _install_file(
            sys_sub,
            os.path.join(donor_sys, "system_ext/etc/init/dpmd.rc"),
            "system_ext/etc/init/dpmd.rc",
            0o644,
            "u:object_r:system_file:s0",
        )
        _install_file(
            sys_sub,
            os.path.join(donor_sys, "system_ext/etc/dpm/dpm.conf"),
            "system_ext/etc/dpm/dpm.conf",
            0o644,
            "u:object_r:system_file:s0",
        )

        # 6. Install / update system_ext SELinux contexts, mac_permissions, and 202504 mapping
        _install_bytes(
            sys_sub,
            _build_mac_permissions_xml(cert_hex),
            "system_ext/etc/selinux/system_ext_mac_permissions.xml",
            0o644,
            "u:object_r:mac_perms_file:s0",
        )

        for rel_ctx, block, selabel in [
            (
                "system_ext/etc/selinux/system_ext_seapp_contexts",
                QTI_SEAPP_CONTEXTS,
                "u:object_r:seapp_contexts_file:s0",
            ),
            (
                "system_ext/etc/selinux/system_ext_file_contexts",
                QTI_FILE_CONTEXTS,
                "u:object_r:file_contexts_file:s0",
            ),
            (
                "system_ext/etc/selinux/system_ext_service_contexts",
                QTI_SERVICE_CONTEXTS,
                "u:object_r:service_contexts_file:s0",
            ),
            (
                "system_ext/etc/selinux/system_ext_property_contexts",
                QTI_PROPERTY_CONTEXTS,
                "u:object_r:property_contexts_file:s0",
            ),
        ]:
            abs_p = os.path.join(sys_sub, rel_ctx)
            cur_txt = open(abs_p, "r", encoding="utf-8").read() if os.path.isfile(abs_p) else ""
            updated_txt = _append_unique_lines(cur_txt, block)
            _install_bytes(sys_sub, updated_txt.encode("utf-8"), rel_ctx, 0o644, selabel)

        ext_map_rel = "system_ext/etc/selinux/mapping/202504.cil"
        ext_map_abs = os.path.join(sys_sub, ext_map_rel)
        if os.path.isfile(ext_map_abs):
            ext_map_ctx = _get_selinux(ext_map_abs, "u:object_r:system_file:s0")
            map_txt = open(ext_map_abs, "r", encoding="utf-8").read()
            if "; RMX1931_QTI_202504_MAPPING" in map_txt:
                map_txt = map_txt.split("; RMX1931_QTI_202504_MAPPING")[0].rstrip()
            map_txt = map_txt.rstrip("\n") + "\n" + QTI_202504_MAPPING_CIL.strip() + "\n"
            _install_bytes(
                sys_sub,
                map_txt.encode("utf-8"),
                ext_map_rel,
                0o644,
                ext_map_ctx,
            )

        plat_cil_rel = "etc/selinux/plat_sepolicy.cil"
        plat_cil_abs = os.path.join(sys_sub, plat_cil_rel)
        if os.path.isfile(plat_cil_abs):
            plat_cil_ctx = _get_selinux(plat_cil_abs, "u:object_r:sepolicy_file:s0")
            cil_txt = open(plat_cil_abs, "r", encoding="utf-8").read()
            if "; RMX1931_QTI_IMS_TELEPHONY_SEPOLICY" in cil_txt:
                cil_txt = cil_txt.split("; RMX1931_QTI_IMS_TELEPHONY_SEPOLICY")[0].rstrip()
            cil_txt = cil_txt.rstrip("\n") + "\n" + QTI_PLAT_SEPOLICY_CIL.strip() + "\n"
            _install_bytes(
                sys_sub,
                cil_txt.encode("utf-8"),
                plat_cil_rel,
                0o644,
                plat_cil_ctx,
            )

        print(
            "    Injected QTI IMS/VoLTE/SMS/DSDS/DPM stack (6 signed APKs, 30 JARs, 26 .so incl. A17 libgui_shim, dpmd, 202504 SELinux) + Google Dialer support."
        )
    finally:
        if mounted_tmp:
            subprocess.run(["umount", mounted_tmp], check=False)
            shutil.rmtree(mounted_tmp, ignore_errors=True)
