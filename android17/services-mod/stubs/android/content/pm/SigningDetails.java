package android.content.pm;

public class SigningDetails {
    public static final SigningDetails UNKNOWN = null;

    public Signature[] getSignatures() {
        return null;
    }

    public boolean checkCapability(SigningDetails other, int flags) {
        return false;
    }
}
