package com.android.incallui;

import com.realme.samurai.dialer.IosInCallService;

/**
 * Android 17 Mainline Telecom system-UI InCallService entry point.
 * Matches TelecomResourcesGoogle's `incall_default_class` (`com.android.incallui.InCallServiceImpl`)
 * when `config_defaultDialer` == `com.realme.samurai.dialer` (`defaultDialer == systemDialer`),
 * while also handling standard `android.telecom.InCallService` binding cleanly.
 */
public final class InCallServiceImpl extends IosInCallService {
}
