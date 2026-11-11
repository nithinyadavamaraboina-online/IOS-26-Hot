package android.app;

import android.content.IContentProvider;
import android.content.pm.ProviderInfo;
import android.os.IBinder;

public class ContentProviderHolder {
    public final ProviderInfo info;
    public IContentProvider provider;
    public IBinder connection;
    public boolean noReleaseNeeded;
    public boolean mLocal;

    public ContentProviderHolder(ProviderInfo info) {
        this.info = info;
    }
}
