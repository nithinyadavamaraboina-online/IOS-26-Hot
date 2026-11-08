package com.realme.samurai.dialer;

import android.net.Uri;
import android.provider.BlockedNumberContract;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Clean-room iOS 26.1 Call Screening & Filtering Engine (`IosCallScreeningService`):
 *  - Enforces Blocked Contacts (local blocked list + Android `BlockedNumberContract`)
 *  - Enforces iOS 26 `"Silence Unknown Callers"` when enabled in Phone Settings (silences incoming
 *    ringtone for numbers not saved in Contacts or recent outgoing calls while still logging to Recents)
 */
public final class IosCallScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details callDetails) {
        if (callDetails == null) {
            return;
        }
        if (callDetails.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, new CallResponse.Builder().build());
            return;
        }

        Uri handle = callDetails.getHandle();
        String rawNumber = (handle != null) ? handle.getSchemeSpecificPart() : "";

        // 1. Check Blocked Contacts (local Dialer preferences + system BlockedNumberContract)
        if (isBlockedNumber(rawNumber)) {
            CallResponse blockResponse = new CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build();
            respondToCall(callDetails, blockResponse);
            return;
        }

        // 2. Check iOS 26 "Silence Unknown Callers"
        if (TelecomHelper.isSilenceUnknownCallersEnabled(this)) {
            boolean known = isKnownCaller(rawNumber, callDetails);
            if (!known) {
                CallResponse silenceResponse = new CallResponse.Builder()
                        .setDisallowCall(false)
                        .setRejectCall(false)
                        .setSilenceCall(true)
                        .setSkipCallLog(false)
                        .setSkipNotification(false)
                        .build();
                respondToCall(callDetails, silenceResponse);
                return;
            }
        }

        respondToCall(callDetails, new CallResponse.Builder().build());
    }

    private boolean isBlockedNumber(String rawNumber) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) {
            return false;
        }
        if (TelecomHelper.isLocallyBlocked(this, rawNumber)) {
            return true;
        }
        try {
            if (BlockedNumberContract.canCurrentUserBlockNumbers(this)) {
                return BlockedNumberContract.isBlocked(this, rawNumber);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isKnownCaller(String rawNumber, Call.Details details) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) {
            return false;
        }
        CharSequence callerDisplay = details.getCallerDisplayName();
        if (callerDisplay != null && callerDisplay.length() > 0) {
            return true;
        }
        ContactOrOutgoingCheck check = checkContactsAndOutgoing(rawNumber);
        return check.isKnown;
    }

    private static final class ContactOrOutgoingCheck {
        final boolean isKnown;
        ContactOrOutgoingCheck(boolean isKnown) {
            this.isKnown = isKnown;
        }
    }

    private ContactOrOutgoingCheck checkContactsAndOutgoing(String rawNumber) {
        try {
            DialerDataRepository repo = DialerDataRepository.get(this);
            if (repo.findContactForNumber(rawNumber) != null) {
                return new ContactOrOutgoingCheck(true);
            }
            String norm = TelecomHelper.normalizeDigitsKey(rawNumber);
            if (!norm.isEmpty()) {
                for (DialerDataRepository.RecentCallGroup g : repo.getRecents()) {
                    if (g.isOutgoing() && norm.equals(g.normKey)) {
                        return new ContactOrOutgoingCheck(true);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ContactOrOutgoingCheck(false);
    }
}
